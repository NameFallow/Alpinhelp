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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.coordscanner.databinding.ActivityScanBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.OcrParser
import com.coordscanner.viewmodel.PointViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
class ScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScanActivity"
        private const val DEDUP_TIMEOUT_MS = 5000L
        private const val MIN_FRAME_INTERVAL_MS = 400L
    }

    private lateinit var binding: ActivityScanBinding
    private val viewModel: PointViewModel by viewModels()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Deduplication: "x_y_zone" -> timestamp of last save
    private val recentDetections = ConcurrentHashMap<String, Long>()

    private val lastFrameTime = AtomicLong(0L)
    private val isProcessing = AtomicBoolean(false)

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
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Сканирование"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestPermission.launch(Manifest.permission.CAMERA)
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
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        // Throttle: skip frames that arrive too fast
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

                val highlightBoxes = mutableListOf<RectF>()

                // Highlight all blocks that look like coordinates
                for (block in visionText.textBlocks) {
                    if (OcrParser.hasCoordinatePattern(block.text)) {
                        block.boundingBox?.let { bb ->
                            highlightBoxes.add(transformRect(bb, imgW, imgH, rotation))
                        }
                    }
                }

                runOnUiThread { binding.overlayView.setRects(highlightBoxes) }

                // Parse and save new points
                val parsed = OcrParser.parseText(visionText.text)
                for (p in parsed) {
                    val key = if (p.isWgs84) {
                        "wgs_${"%.5f".format(p.lat)}_${"%.5f".format(p.lon)}"
                    } else {
                        "${p.x.toLong()}_${p.y.toLong()}_${p.zone}"
                    }

                    val lastSeen = recentDetections[key]
                    if (lastSeen == null || System.currentTimeMillis() - lastSeen > DEDUP_TIMEOUT_MS) {
                        recentDetections[key] = System.currentTimeMillis()
                        savePoint(p)
                    }
                }
            }
            .addOnFailureListener {
                imageProxy.close()
                isProcessing.set(false)
            }
    }

    private fun savePoint(p: com.coordscanner.utils.ParsedCoord) {
        val lat: Double
        val lon: Double
        val x: Double
        val y: Double
        val zone: Int

        if (p.isWgs84) {
            lat = p.lat
            lon = p.lon
            x = 0.0; y = 0.0; zone = 0
        } else {
            val (la, lo) = CoordConverter.sk42ToWgs84(p.x, p.y, p.zone)
            lat = la; lon = lo; x = p.x; y = p.y; zone = p.zone
        }

        val point = Point(
            name = p.name,
            xSk42 = x, ySk42 = y, zone = zone,
            latWgs84 = lat, lonWgs84 = lon,
            source = "scan"
        )
        viewModel.insert(point)

        runOnUiThread {
            binding.overlayView.triggerFlash()
            binding.tvLastPoint.text = "✓ Добавлено: ${p.name}"
            binding.tvStatus.text = "Продолжаю сканирование..."
            vibrateShort()
            playBeep()
        }

        Log.d(TAG, "Point saved: ${p.name}")
    }

    // Transform bounding box from image space to PreviewView screen space
    private fun transformRect(rect: android.graphics.Rect, imgW: Int, imgH: Int, rotation: Int): RectF {
        val vw = binding.viewFinder.width.toFloat()
        val vh = binding.viewFinder.height.toFloat()
        if (vw == 0f || vh == 0f) return RectF()

        // After rotation, effective image dimensions
        val (rotW, rotH) = if (rotation == 90 || rotation == 270) Pair(imgH, imgW) else Pair(imgW, imgH)

        // CenterCrop scaling (same as PreviewView default)
        val scaleX = vw / rotW
        val scaleY = vh / rotH
        val scale = maxOf(scaleX, scaleY)
        val offsetX = (vw - rotW * scale) / 2f
        val offsetY = (vh - rotH * scale) / 2f

        // Rotate rect corners, then apply scale + offset
        val pts = when (rotation) {
            90 -> floatArrayOf(
                rect.top.toFloat(), (imgW - rect.right).toFloat(),
                rect.bottom.toFloat(), (imgW - rect.left).toFloat()
            )
            180 -> floatArrayOf(
                (imgW - rect.right).toFloat(), (imgH - rect.bottom).toFloat(),
                (imgW - rect.left).toFloat(), (imgH - rect.top).toFloat()
            )
            270 -> floatArrayOf(
                (imgH - rect.bottom).toFloat(), rect.left.toFloat(),
                (imgH - rect.top).toFloat(), rect.right.toFloat()
            )
            else -> floatArrayOf(
                rect.left.toFloat(), rect.top.toFloat(),
                rect.right.toFloat(), rect.bottom.toFloat()
            )
        }

        return RectF(
            pts[0] * scale + offsetX,
            pts[1] * scale + offsetY,
            pts[2] * scale + offsetX,
            pts[3] * scale + offsetY
        )
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
