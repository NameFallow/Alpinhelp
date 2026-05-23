package com.coordscanner

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.NamedCoordAdapter
import com.coordscanner.databinding.ActivityPhotoZoneBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.GpxExporter
import com.coordscanner.utils.MatchedRow
import com.coordscanner.utils.RowMatcher
import com.coordscanner.viewmodel.PointViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class PhotoZoneActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhotoZoneActivity"
        private const val PREFS_NAME = "photo_zone_prefs"
        private const val KEY_COORD_MODE = "coord_mode"
        private const val MODE_SK42 = "sk42"
        private const val MODE_WGS84 = "wgs84"
    }

    private lateinit var binding: ActivityPhotoZoneBinding
    private val viewModel: PointViewModel by viewModels()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private lateinit var imageCapture: ImageCapture
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var capturedBitmap: Bitmap? = null
    private var imageRect = RectF()

    private lateinit var adapter: NamedCoordAdapter

    private var coordMode = MODE_SK42

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

        coordMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_COORD_MODE, MODE_SK42) ?: MODE_SK42

        setupCamera()
        setupSelectionPanel()
        setupResultsPanel()
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
        lifecycleScope.launch(Dispatchers.IO) {
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

    // ── Фаза 2: выделение зон ────────────────────────────────

    private fun setupSelectionPanel() {
        binding.btnAddX.setOnClickListener { toggleZoneMode(MultiZoneOverlayView.ZoneType.X) }
        binding.btnAddY.setOnClickListener { toggleZoneMode(MultiZoneOverlayView.ZoneType.Y) }
        binding.btnScanZones.setOnClickListener { runOcr() }

        binding.btnCoordMode.setOnClickListener { toggleCoordMode() }

        binding.overlayView.onZoneAdded = { updateSelectionUI() }
        binding.overlayView.onStateChanged = { updateSelectionUI() }
        binding.overlayView.onZoneLongPressed = { zone ->
            binding.overlayView.removeZone(zone.id)
            updateSelectionUI()
        }
    }

    private fun toggleZoneMode(type: MultiZoneOverlayView.ZoneType) {
        val overlay = binding.overlayView
        if (!overlay.canAdd(type) && overlay.activeType != type) {
            Toast.makeText(this, "Максимум ${MultiZoneOverlayView.MAX_PER_TYPE} зон", Toast.LENGTH_SHORT).show()
            return
        }
        overlay.activeType = if (overlay.activeType == type) null else type
        updateSelectionUI()
    }

    private fun showSelectionPhase(bitmap: Bitmap) {
        capturedBitmap = bitmap
        binding.overlayView.clearAll()

        binding.ivPhoto.setImageBitmap(bitmap)
        binding.cameraContainer.visibility = View.GONE
        binding.selectionContainer.visibility = View.VISIBLE
        binding.resultsContainer.visibility = View.GONE

        binding.ivPhoto.doOnLayout { computeImageRect(bitmap) }
        updateSelectionUI()
    }

    private fun computeImageRect(bitmap: Bitmap) {
        val vw = binding.ivPhoto.width.toFloat()
        val vh = binding.ivPhoto.height.toFloat()
        if (vw == 0f || vh == 0f) return

        val scale = minOf(vw / bitmap.width, vh / bitmap.height)
        val scaledW = bitmap.width * scale
        val scaledH = bitmap.height * scale
        imageRect = RectF(
            (vw - scaledW) / 2f,
            (vh - scaledH) / 2f,
            (vw + scaledW) / 2f,
            (vh + scaledH) / 2f
        )
        binding.overlayView.imageRect = imageRect
    }

    private fun updateSelectionUI() {
        val overlay = binding.overlayView
        val xCount = overlay.xZones().size
        val yCount = overlay.yZones().size
        val max = MultiZoneOverlayView.MAX_PER_TYPE

        binding.tvZoneStatus.text = "X: $xCount/$max   Y: $yCount/$max  •  долгое нажатие → удалить"

        val activeType = overlay.activeType
        styleZoneButton(
            binding.btnAddX,
            active = activeType == MultiZoneOverlayView.ZoneType.X,
            color  = MultiZoneOverlayView.COLOR_X,
            label  = "+ X ЗОНА"
        )
        styleZoneButton(
            binding.btnAddY,
            active = activeType == MultiZoneOverlayView.ZoneType.Y,
            color  = MultiZoneOverlayView.COLOR_Y,
            label  = "+ Y ЗОНА"
        )

        binding.tvSelectionStatus.text = when {
            activeType != null        -> "Нарисуйте прямоугольник вокруг нужной колонки"
            overlay.hasMinimum()      -> "Зоны выделены. Нажмите СКАН"
            else                      -> "Нажмите кнопку, затем нарисуйте прямоугольник на фото"
        }

        binding.btnScanZones.isEnabled = overlay.hasMinimum() && activeType == null

        val modeLabel = if (coordMode == MODE_WGS84) "WGS-84" else "СК-42"
        binding.btnCoordMode.text = modeLabel
    }

    private fun styleZoneButton(
        btn: com.google.android.material.button.MaterialButton,
        active: Boolean,
        color: Int,
        label: String
    ) {
        if (active) {
            btn.backgroundTintList = ColorStateList.valueOf(color)
            btn.setTextColor(Color.WHITE)
            btn.text = "✏ ${label.removePrefix("+ ")}"
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(color)
            btn.text = label
        }
    }

    private fun toggleCoordMode() {
        coordMode = if (coordMode == MODE_SK42) MODE_WGS84 else MODE_SK42
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_COORD_MODE, coordMode).apply()
        updateSelectionUI()
        updateToggleDisplay()
    }

    // ── OCR ──────────────────────────────────────────────────

    private fun runOcr() {
        val bitmap = capturedBitmap ?: return
        val overlay = binding.overlayView
        if (!overlay.hasMinimum()) return

        binding.progressBarOcr.visibility = View.VISIBLE
        binding.btnScanZones.isEnabled = false

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val rows = RowMatcher.matchMultiZone(
                    visionText   = visionText,
                    bitmapWidth  = bitmap.width,
                    bitmapHeight = bitmap.height,
                    xViewRects   = overlay.xRects(),
                    yViewRects   = overlay.yRects(),
                    imageViewRect = imageRect
                )
                runOnUiThread {
                    binding.progressBarOcr.visibility = View.GONE
                    if (rows.isEmpty()) {
                        Toast.makeText(
                            this,
                            "Строк не найдено. Проверьте выделенные области.",
                            Toast.LENGTH_LONG
                        ).show()
                        binding.btnScanZones.isEnabled = true
                    } else {
                        showResultsPhase(rows)
                    }
                }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    binding.progressBarOcr.visibility = View.GONE
                    binding.btnScanZones.isEnabled = true
                    Toast.makeText(this, "Ошибка OCR: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ── Фаза 3: результаты ───────────────────────────────────

    private fun setupResultsPanel() {
        adapter = NamedCoordAdapter(
            onSelectionChanged = { updateResultButtons() },
            showWgs84 = coordMode == MODE_WGS84
        )
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.checkboxSelectAll.setOnCheckedChangeListener { _, checked ->
            adapter.selectAll(checked)
        }

        binding.btnBackToSelection.setOnClickListener {
            binding.resultsContainer.visibility = View.GONE
            binding.selectionContainer.visibility = View.VISIBLE
            binding.btnScanZones.isEnabled = binding.overlayView.hasMinimum()
        }

        binding.btnToggleDisplay.setOnClickListener {
            toggleCoordMode()
            adapter.showWgs84 = coordMode == MODE_WGS84
            adapter.notifyDataSetChanged()
        }

        binding.btnSaveSelected.setOnClickListener { saveSelected() }
        binding.btnExportGpx.setOnClickListener   { exportGpx() }
    }

    private fun showResultsPhase(rows: List<MatchedRow>) {
        adapter.showWgs84 = coordMode == MODE_WGS84
        adapter.setData(rows)
        binding.checkboxSelectAll.isChecked = true
        binding.selectionContainer.visibility = View.GONE
        binding.resultsContainer.visibility = View.VISIBLE
        updateResultButtons()
        updateToggleDisplay()
        Toast.makeText(this, "Найдено строк: ${rows.size}", Toast.LENGTH_SHORT).show()
    }

    private fun updateResultButtons() {
        val selected = adapter.getSelectedCount()
        val total    = adapter.getTotalCount()
        binding.tvResultCount.text = "Знайдено: $total  •  Вибрано: $selected"
        binding.btnSaveSelected.text = "ЗБЕРЕГТИ ($selected)"
        binding.btnSaveSelected.isEnabled = selected > 0
        binding.btnExportGpx.isEnabled   = selected > 0
    }

    private fun updateToggleDisplay() {
        val modeLabel = if (coordMode == MODE_WGS84) "WGS-84" else "СК-42"
        binding.btnToggleDisplay.text = modeLabel
    }

    // ── Сохранение и экспорт ─────────────────────────────────

    private fun saveSelected() {
        val rows = adapter.getSelected().ifEmpty { return }
        viewModel.insertAll(rows.map { rowToPoint(it) })
        Toast.makeText(this, "Збережено ${rows.size} точок", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun exportGpx() {
        val rows = adapter.getSelected().ifEmpty { return }
        GpxExporter.exportAndShare(this, rows.map { rowToPoint(it) })
    }

    private fun rowToPoint(row: MatchedRow): Point {
        val (lat, lon) = CoordConverter.sk42ToWgs84(row.x, row.y, row.zone)
        return Point(
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
