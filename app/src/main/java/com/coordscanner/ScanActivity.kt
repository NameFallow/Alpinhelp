package com.coordscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import android.util.Size
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.coordscanner.databinding.ActivityScanBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.CoordsPrefs
import com.coordscanner.utils.OcrParser
import com.coordscanner.utils.ParsedCoord
import com.coordscanner.viewmodel.PointViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
class ScanActivity : AppCompatActivity() {

    private enum class ScanMode { SK42, WGS84 }

    companion object {
        private const val TAG = "ScanActivity"
        private const val DEDUP_TIMEOUT_MS = 15_000L
        private const val MIN_FRAME_INTERVAL_MS = 500L
        private const val REQUIRED_FRAMES = 3
    }

    private lateinit var binding: ActivityScanBinding
    private val viewModel: PointViewModel by viewModels()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val recentDetections = ConcurrentHashMap<String, Long>()
    private val lastFrameTime = AtomicLong(0L)
    private val isProcessing = AtomicBoolean(false)

    // Stability state: same coord must appear REQUIRED_FRAMES in a row
    @Volatile private var candidateKey: String? = null
    @Volatile private var candidatePoint: ParsedCoord? = null
    private val candidateFrames = AtomicInteger(0)

    // Confirmation state
    @Volatile private var confirmationShowing = false
    private var pendingPoint: ParsedCoord? = null

    private var scanMode = ScanMode.SK42

    // Selected color for the point
    private var selectedColor = "#0055FF"

    private var debugVisible = false

    // Map: View id → hex color
    private val colorMap: Map<Int, String> by lazy {
        mapOf(
            R.id.colorDotBlue   to "#0055FF",
            R.id.colorDotRed    to "#FF2020",
            R.id.colorDotGreen  to "#00BB00",
            R.id.colorDotOrange to "#FF8800",
            R.id.colorDotYellow to "#FFD700",
            R.id.colorDotPurple to "#9900CC"
        )
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.confirmCard.translationY = 1200f

        binding.btnBack.setOnClickListener { finish() }

        binding.btnDebug.setOnClickListener {
            debugVisible = !debugVisible
            binding.scrollDebug.visibility = if (debugVisible) View.VISIBLE else View.GONE
            binding.btnDebug.text = if (debugVisible) "OCR ▲" else "OCR"
        }

        // Apply saved coordinate mode preference
        if (CoordsPrefs.getMode(this) == CoordsPrefs.WGS84) {
            scanMode = ScanMode.WGS84
            binding.toggleCoordsMode.check(R.id.btnModeWgs84)
        }

        setupColorPicker()
        setupModeToggle()
        setupZoom()

        binding.btnSave.setOnClickListener {
            val p = pendingPoint ?: return@setOnClickListener
            val name = binding.etConfirmName.text.toString().trim().ifEmpty { "Точка" }
            savePoint(p.copy(name = name), selectedColor)
            hideKeyboard()
            hideConfirmation(confirmed = true)
        }

        binding.btnNo.setOnClickListener {
            val p = pendingPoint
            if (p != null) recentDetections[makeKey(p)] = System.currentTimeMillis()
            resetCandidate()
            hideKeyboard()
            hideConfirmation(confirmed = false)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun setupModeToggle() {
        binding.toggleCoordsMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                scanMode = if (checkedId == R.id.btnModeSk42) ScanMode.SK42 else ScanMode.WGS84
                resetCandidate()
                recentDetections.clear()
                binding.tvStatus.text = "Наведите на координаты"
            }
        }
    }

    private fun setupColorPicker() {
        for ((viewId, color) in colorMap) {
            findViewById<View>(viewId).setOnClickListener { selectColor(color) }
        }
        selectColor(selectedColor)
    }

    private fun selectColor(color: String) {
        selectedColor = color
        for ((viewId, dotColor) in colorMap) {
            val dot = findViewById<View>(viewId)
            val scale = if (dotColor == color) 1.4f else 1.0f
            dot.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                camera?.cameraInfo?.zoomState?.observe(this) { updateZoomLabel() }
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastFrameTime.get() < MIN_FRAME_INTERVAL_MS || isProcessing.get()) {
            imageProxy.close()
            return
        }
        lastFrameTime.set(now)
        isProcessing.set(true)

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isProcessing.set(false)
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val imgW = imageProxy.width
        val imgH = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                imageProxy.close()
                isProcessing.set(false)

                val rawText = visionText.text
                val highlightBoxes = mutableListOf<RectF>()

                for (block in visionText.textBlocks) {
                    if (OcrParser.hasCoordinatePattern(block.text)) {
                        block.boundingBox?.let { bb ->
                            highlightBoxes.add(transformRect(bb, imgW, imgH, rotation))
                        }
                    }
                }

                runOnUiThread {
                    binding.overlayView.setRects(highlightBoxes)
                    if (debugVisible && rawText.isNotBlank()) {
                        binding.tvDebugOcr.text = rawText
                    }
                }

                if (!confirmationShowing) {
                    processStability(rawText, now)
                }
            }
            .addOnFailureListener {
                imageProxy.close()
                isProcessing.set(false)
            }
    }

    private fun processStability(rawText: String, now: Long) {
        val parsed = OcrParser.parseText(rawText)

        // Filter by selected coordinate system, then deduplicate
        val newPoint = parsed
            .filter { p -> if (scanMode == ScanMode.SK42) !p.isWgs84 else p.isWgs84 }
            .firstOrNull { p ->
                val k = makeKey(p)
                val lastSeen = recentDetections[k]
                lastSeen == null || now - lastSeen > DEDUP_TIMEOUT_MS
            }

        when {
            newPoint == null -> {
                // No valid coordinates visible — reset candidate
                if (candidateFrames.get() > 0) {
                    resetCandidate()
                    runOnUiThread { binding.tvStatus.text = "Наведите на координаты" }
                }
            }
            makeKey(newPoint) == candidateKey -> {
                // Same candidate — increment stability counter
                val frames = candidateFrames.incrementAndGet()
                val progress = "●".repeat(frames) + "○".repeat(maxOf(0, REQUIRED_FRAMES - frames))
                runOnUiThread { binding.tvStatus.text = "Держите камеру... $progress" }

                if (frames >= REQUIRED_FRAMES) {
                    val confirmed = newPoint
                    resetCandidate()
                    runOnUiThread { showConfirmation(confirmed) }
                }
            }
            else -> {
                // New different candidate — start fresh
                candidateKey = makeKey(newPoint)
                candidatePoint = newPoint
                candidateFrames.set(1)
                runOnUiThread { binding.tvStatus.text = "Держите камеру... ●○○" }
            }
        }
    }

    private fun resetCandidate() {
        candidateKey = null
        candidatePoint = null
        candidateFrames.set(0)
    }

    private fun showConfirmation(p: ParsedCoord) {
        pendingPoint = p
        confirmationShowing = true
        recentDetections[makeKey(p)] = System.currentTimeMillis()

        val (lat, lon) = if (!p.isWgs84)
            CoordConverter.sk42ToWgs84(p.x, p.y, p.zone)
        else
            Pair(p.lat, p.lon)

        binding.etConfirmName.setText(p.name)
        binding.tvConfirmSystem.text = p.system

        binding.tvConfirmSk42.text = if (!p.isWgs84)
            "X: %,d\nY: %,d  зона %d".format(p.x.toLong(), p.y.toLong(), p.zone)
        else
            "WGS-84 (градусы)"

        binding.tvConfirmWgs84.text = "%.5f° N\n%.5f° E".format(lat, lon)

        binding.tvLastPoint.visibility = View.INVISIBLE
        binding.tvLastPoint.text = ""

        // Reset color to default blue on each new confirmation
        selectColor("#0055FF")

        binding.statusPill.animate().alpha(0f).setDuration(200).start()
        binding.confirmCard.animate()
            .translationY(0f)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()

        vibrateShort()
    }

    private fun hideConfirmation(confirmed: Boolean) {
        confirmationShowing = false
        pendingPoint = null

        binding.confirmCard.animate()
            .translationY(1200f)
            .setDuration(280)
            .setInterpolator(AccelerateInterpolator())
            .start()

        binding.statusPill.animate().alpha(1f).setDuration(200).start()
        binding.tvStatus.text = "Наведите на координаты"
    }

    private fun savePoint(p: ParsedCoord, color: String) {
        val lat: Double; val lon: Double
        val x: Double; val y: Double; val zone: Int

        if (p.isWgs84) {
            lat = p.lat; lon = p.lon
            x = 0.0; y = 0.0; zone = 0
        } else {
            val (la, lo) = CoordConverter.sk42ToWgs84(p.x, p.y, p.zone)
            lat = la; lon = lo; x = p.x; y = p.y; zone = p.zone
        }

        viewModel.insert(
            Point(name = p.name, xSk42 = x, ySk42 = y, zone = zone,
                  latWgs84 = lat, lonWgs84 = lon, source = "scan", color = color)
        )

        binding.overlayView.triggerFlash()
        binding.tvLastPoint.text = "✓ Сохранено: ${p.name}"
        binding.tvLastPoint.visibility = View.VISIBLE
        playBeep()

        Log.d(TAG, "Saved: ${p.name}  color=$color  x=${p.x}  y=${p.y}")
    }

    private fun makeKey(p: ParsedCoord) = if (p.isWgs84)
        "wgs_${"%.5f".format(p.lat)}_${"%.5f".format(p.lon)}"
    else
        "${p.x.toLong()}_${p.y.toLong()}_${p.zone}"

    private fun transformRect(rect: android.graphics.Rect, imgW: Int, imgH: Int, rotation: Int): RectF {
        val vw = binding.viewFinder.width.toFloat()
        val vh = binding.viewFinder.height.toFloat()
        if (vw == 0f || vh == 0f) return RectF()

        val (rotW, rotH) = if (rotation == 90 || rotation == 270) Pair(imgH, imgW) else Pair(imgW, imgH)
        val scale = maxOf(vw / rotW, vh / rotH)
        val offsetX = (vw - rotW * scale) / 2f
        val offsetY = (vh - rotH * scale) / 2f

        val pts = when (rotation) {
            90  -> floatArrayOf(rect.top.toFloat(), (imgW - rect.right).toFloat(),
                                rect.bottom.toFloat(), (imgW - rect.left).toFloat())
            180 -> floatArrayOf((imgW - rect.right).toFloat(), (imgH - rect.bottom).toFloat(),
                                (imgW - rect.left).toFloat(), (imgH - rect.top).toFloat())
            270 -> floatArrayOf((imgH - rect.bottom).toFloat(), rect.left.toFloat(),
                                (imgH - rect.top).toFloat(), rect.right.toFloat())
            else -> floatArrayOf(rect.left.toFloat(), rect.top.toFloat(),
                                 rect.right.toFloat(), rect.bottom.toFloat())
        }

        return RectF(pts[0] * scale + offsetX, pts[1] * scale + offsetY,
                     pts[2] * scale + offsetX, pts[3] * scale + offsetY)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun vibrateShort() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    }

    private fun playBeep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 300)
        } catch (_: Exception) {}
    }

    // ── Zoom ─────────────────────────────────────────────────

    private fun setupZoom() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val state = cam.cameraInfo.zoomState.value ?: return true
                    val newZoom = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(newZoom)
                    return true
                }
            })
        binding.viewFinder.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            v.performClick()
            true
        }
        binding.btnZoomIn.setOnClickListener { adjustZoom(1.4f) }
        binding.btnZoomOut.setOnClickListener { adjustZoom(1f / 1.4f) }
    }

    private fun adjustZoom(factor: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val newZoom = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(newZoom)
    }

    private fun updateZoomLabel() {
        val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        binding.tvZoomLevel.text = "%.1f×".format(zoom)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
