package com.coordscanner

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.coordscanner.adapter.NamedCoordAdapter
import com.coordscanner.databinding.ActivityPhotoZoneBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.AiBadge
import com.coordscanner.utils.AiPrefs
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.GeminiScanner
import com.coordscanner.utils.GpxExporter
import com.coordscanner.utils.MatchedRow
import com.coordscanner.utils.OcrParser
import com.coordscanner.utils.WgsParser
import com.coordscanner.viewmodel.PointViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class PhotoZoneActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhotoZoneActivity"
        private const val MODE_TEXT  = "text"
        private const val MODE_TABLE = "table"
        private const val SYS_SK42 = "sk42"
        private const val SYS_WGS  = "wgs"
    }

    private lateinit var binding: ActivityPhotoZoneBinding
    private val viewModel: PointViewModel by viewModels()
    private val recognizer  = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private lateinit var imageCapture: ImageCapture
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var capturedBitmap: Bitmap? = null
    private lateinit var adapter: NamedCoordAdapter

    private var parseMode = MODE_TEXT      // текст или таблица
    private var coordSystem = SYS_WGS      // СК-42 или WGS-84

    // ── Разрешения и галерея ─────────────────────────────────

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show(); finish() }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) loadFromUri(uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoZoneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCamera()
        setupSelectionPanel()
        setupResultsPanel()
        AiBadge.attach(this, binding.tvAi)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }

    // ── Фаза 1: камера ───────────────────────────────────────

    private fun setupCamera() {
        setupZoom()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnZoomIn.setOnClickListener  { adjustZoom(1.4f) }
        binding.btnZoomOut.setOnClickListener { adjustZoom(1f / 1.4f) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun setupZoom() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val state = cam.cameraInfo.zoomState.value ?: return true
                    val newZoom = (state.zoomRatio * det.scaleFactor)
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
        binding.seekbarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun adjustZoom(factor: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val newZoom = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(newZoom)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                camera?.cameraInfo?.zoomState?.observe(this) {
                    binding.tvZoomLevel.text = "%.1f×".format(it.zoomRatio)
                    binding.seekbarZoom.progress = (it.linearZoom * 100).toInt()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val file = File(cacheDir, "photozone_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        binding.progressBarCamera.visibility = View.VISIBLE
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.progressBarCamera.visibility = View.GONE
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) showSelectionPhase(bmp)
                    else Toast.makeText(this@PhotoZoneActivity, "Ошибка съёмки", Toast.LENGTH_SHORT).show()
                }
                override fun onError(e: ImageCaptureException) {
                    binding.progressBarCamera.visibility = View.GONE
                    Toast.makeText(this@PhotoZoneActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun loadFromUri(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.onFailure { Log.e(TAG, "loadFromUri failed", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bmp != null) showSelectionPhase(bmp)
                else Toast.makeText(this@PhotoZoneActivity,
                    "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Фаза 2: выбор режима ─────────────────────────────────

    private fun setupSelectionPanel() {
        binding.btnModeText.setOnClickListener  { setMode(MODE_TEXT) }
        binding.btnModeTable.setOnClickListener { setMode(MODE_TABLE) }
        binding.btnSysSk42.setOnClickListener   { setSystem(SYS_SK42) }
        binding.btnSysWgs.setOnClickListener    { setSystem(SYS_WGS) }
        binding.btnScanZones.setOnClickListener { runOcr() }
        setMode(MODE_TEXT)
        setSystem(SYS_WGS)
    }

    private fun setMode(mode: String) {
        parseMode = mode
        val inactiveColor = ColorStateList.valueOf(Color.TRANSPARENT)
        if (mode == MODE_TEXT) {
            binding.btnModeText.backgroundTintList  = ColorStateList.valueOf(0xFF4CAF50.toInt())
            binding.btnModeText.setTextColor(Color.WHITE)
            binding.btnModeTable.backgroundTintList = inactiveColor
            binding.btnModeTable.setTextColor(Color.parseColor("#E53935"))
        } else {
            binding.btnModeTable.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935"))
            binding.btnModeTable.setTextColor(Color.WHITE)
            binding.btnModeText.backgroundTintList  = inactiveColor
            binding.btnModeText.setTextColor(Color.parseColor("#4CAF50"))
        }
        updateHint()
    }

    private fun setSystem(sys: String) {
        coordSystem = sys
        val inactive = ColorStateList.valueOf(Color.TRANSPARENT)
        if (sys == SYS_SK42) {
            binding.btnSysSk42.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF8800"))
            binding.btnSysSk42.setTextColor(Color.WHITE)
            binding.btnSysWgs.backgroundTintList  = inactive
            binding.btnSysWgs.setTextColor(Color.parseColor("#2196F3"))
        } else {
            binding.btnSysWgs.backgroundTintList  = ColorStateList.valueOf(Color.parseColor("#2196F3"))
            binding.btnSysWgs.setTextColor(Color.WHITE)
            binding.btnSysSk42.backgroundTintList = inactive
            binding.btnSysSk42.setTextColor(Color.parseColor("#FF8800"))
        }
        updateHint()
    }

    private fun updateHint() {
        val sys = if (coordSystem == SYS_SK42) "СК-42 • X / Y в метрах" else "WGS-84 • широта / долгота"
        val mode = if (parseMode == MODE_TEXT) "текст — координаты в строчку" else "таблица — колонками"
        binding.tvParseHint.text = "$sys  •  $mode"
    }

    private fun showSelectionPhase(bitmap: Bitmap) {
        capturedBitmap = bitmap
        binding.ivPhoto.setImageBitmap(bitmap)
        binding.cameraContainer.visibility  = View.GONE
        binding.selectionContainer.visibility = View.VISIBLE
        binding.resultsContainer.visibility = View.GONE
    }

    // ── OCR ──────────────────────────────────────────────────

    private fun runOcr() {
        val bitmap = capturedBitmap ?: return
        binding.progressBarOcr.visibility = View.VISIBLE
        binding.btnScanZones.isEnabled = false

        lifecycleScope.launch {
            val rows = scanWgs(bitmap)
            binding.progressBarOcr.visibility = View.GONE
            binding.btnScanZones.isEnabled = true
            if (rows.isEmpty()) {
                Toast.makeText(
                    this@PhotoZoneActivity,
                    "Координаты не найдены. Попробуйте другой режим.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                showResultsPhase(rows)
            }
        }
    }

    private suspend fun scanWgs(bitmap: Bitmap): List<MatchedRow> {
        if (AiPrefs.isReadyToTry()) {
            Log.i(TAG, "AI attempt: source=${AiPrefs.source()} sys=$coordSystem mode=$parseMode")
            val mode = if (parseMode == MODE_TEXT) GeminiScanner.WgsMode.TEXT else GeminiScanner.WgsMode.TABLE
            val result = runCatching {
                if (coordSystem == SYS_SK42)
                    GeminiScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = AiPrefs.apiKey())
                else
                    GeminiScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = AiPrefs.apiKey())
            }
            val rows = result.getOrNull()
            if (!rows.isNullOrEmpty()) return rows
            val reason = AiBadge.describe(result.exceptionOrNull())
            Log.w(TAG, "AI fallback → ML Kit: $reason", result.exceptionOrNull())
            Toast.makeText(this, "AI не сработал ($reason) — ML Kit", Toast.LENGTH_SHORT).show()
        } else {
            val why = if (!AiPrefs.isEnabled()) "выкл" else "нет ключа"
            Log.i(TAG, "AI skipped: $why")
            Toast.makeText(this, "AI $why — ML Kit", Toast.LENGTH_SHORT).show()
        }
        return runMlKitFallback(bitmap)
    }

    private suspend fun runMlKitFallback(bitmap: Bitmap): List<MatchedRow> = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    val rows: List<MatchedRow> = when {
                        coordSystem == SYS_SK42 -> {
                            OcrParser.parseText(visionText.text)
                                .filter { !it.isWgs84 }
                                .map { MatchedRow(name = it.name.ifEmpty { "Точка" }, x = it.x, y = it.y, zone = it.zone) }
                        }
                        parseMode == MODE_TEXT  -> WgsParser.parseTextMode(visionText.text)
                        else                    -> WgsParser.parseTableMode(visionText, bitmap.height)
                    }
                    cont.resume(rows) {}
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit scan failed", e)
                    cont.resume(emptyList()) {}
                }
        }
    }

    // ── Фаза 3: результаты ───────────────────────────────────

    private fun setupResultsPanel() {
        adapter = NamedCoordAdapter(onSelectionChanged = { updateResultButtons() }, showWgs84 = true)
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.checkboxSelectAll.setOnCheckedChangeListener { _, checked ->
            adapter.selectAll(checked)
        }

        binding.btnBackToSelection.setOnClickListener {
            binding.resultsContainer.visibility  = View.GONE
            binding.selectionContainer.visibility = View.VISIBLE
        }

        // Кнопка переключения отображения не нужна для WGS-84 — прячем её
        binding.btnToggleDisplay.visibility = View.GONE

        binding.btnSaveSelected.setOnClickListener { saveSelected() }
        binding.btnExportGpx.setOnClickListener   { exportGpx() }
    }

    private fun showResultsPhase(rows: List<MatchedRow>) {
        adapter.setData(rows)
        binding.checkboxSelectAll.isChecked = true
        binding.selectionContainer.visibility = View.GONE
        binding.resultsContainer.visibility  = View.VISIBLE
        updateResultButtons()
        Toast.makeText(this, "Найдено точек: ${rows.size}", Toast.LENGTH_SHORT).show()
    }

    private fun updateResultButtons() {
        val selected = adapter.getSelectedCount()
        val total    = adapter.getTotalCount()
        binding.tvResultCount.text = "Найдено: $total  •  Выбрано: $selected"
        binding.btnSaveSelected.text = "СОХРАНИТЬ ($selected)"
        binding.btnSaveSelected.isEnabled = selected > 0
        binding.btnExportGpx.isEnabled   = selected > 0
    }

    // ── Сохранение и экспорт ─────────────────────────────────

    private fun saveSelected() {
        val rows = adapter.getSelected().ifEmpty { return }
        viewModel.insertAll(rows.map { rowToPoint(it) })
        Toast.makeText(this, "Сохранено ${rows.size} точек", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun exportGpx() {
        val rows = adapter.getSelected().ifEmpty { return }
        GpxExporter.exportAndShare(this, rows.map { rowToPoint(it) })
    }

    private fun rowToPoint(row: MatchedRow): Point {
        return if (row.isWgs84) {
            Point(
                name     = row.name.ifEmpty { "Точка" },
                xSk42    = 0.0,
                ySk42    = 0.0,
                zone     = 0,
                latWgs84 = row.lat,
                lonWgs84 = row.lon,
                source   = "scan"
            )
        } else {
            val (lat, lon) = CoordConverter.sk42ToWgs84(row.x, row.y, row.zone)
            Point(
                name     = row.name.ifEmpty { "Точка" },
                xSk42    = row.x,
                ySk42    = row.y,
                zone     = row.zone,
                latWgs84 = lat,
                lonWgs84 = lon,
                source   = "scan"
            )
        }
    }
}
