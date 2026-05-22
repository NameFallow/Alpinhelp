package com.coordscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.NamedCoordAdapter
import com.coordscanner.databinding.ActivityColumnSelectorBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.GpxExporter
import com.coordscanner.utils.MatchedRow
import com.coordscanner.utils.RowMatcher
import com.coordscanner.viewmodel.PointViewModel
import android.content.res.ColorStateList
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class ColumnSelectorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ColumnSelector"
        private const val COLOR_NAME = 0xFFFF8800.toInt()
        private const val COLOR_X    = 0xFF2196F3.toInt()
        private const val COLOR_Y    = 0xFF4CAF50.toInt()
    }

    // Какую колонку сейчас назначаем
    private enum class SelectMode { NONE, NAME, X, Y }

    private lateinit var binding: ActivityColumnSelectorBinding
    private val viewModel: PointViewModel by viewModels()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private lateinit var imageCapture: ImageCapture
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var capturedBitmap: Bitmap? = null
    private var imageRect = RectF()

    // Дроби 0..1 от ширины изображения — центры выбранных колонок
    private var nameFraction: Float? = null
    private var xFraction: Float? = null
    private var yFraction: Float? = null
    private var selectMode = SelectMode.NONE

    private lateinit var adapter: NamedCoordAdapter

    // ── Запрос разрешений и галерея ──────────────────────────

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
        binding = ActivityColumnSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val file = File(cacheDir, "colsel_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        binding.progressBarCamera.visibility = View.VISIBLE
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    binding.progressBarCamera.visibility = View.GONE
                    if (bmp != null) showSelectionPhase(bmp)
                    else Toast.makeText(this@ColumnSelectorActivity, "Ошибка съёмки", Toast.LENGTH_SHORT).show()
                }
                override fun onError(e: ImageCaptureException) {
                    binding.progressBarCamera.visibility = View.GONE
                    Toast.makeText(this@ColumnSelectorActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun loadFromUri(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: return
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            if (bmp != null) showSelectionPhase(bmp)
            else Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "loadFromUri failed", e)
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Фаза 2: выбор колонок ────────────────────────────────

    private fun setupSelectionPanel() {
        binding.btnColName.setOnClickListener { activateMode(SelectMode.NAME) }
        binding.btnColX.setOnClickListener   { activateMode(SelectMode.X) }
        binding.btnColY.setOnClickListener   { activateMode(SelectMode.Y) }
        binding.btnScanColumns.setOnClickListener { runOcr() }

        binding.overlayView.onTap = { x, y -> onImageTap(x, y) }
    }

    private fun showSelectionPhase(bitmap: Bitmap) {
        capturedBitmap = bitmap
        nameFraction = null; xFraction = null; yFraction = null
        selectMode = SelectMode.NONE

        binding.ivPhoto.setImageBitmap(bitmap)
        binding.cameraContainer.visibility = View.GONE
        binding.selectionContainer.visibility = View.VISIBLE
        binding.resultsContainer.visibility = View.GONE

        // imageRect вычисляем после layout, когда размеры ImageView известны
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
        val left = (vw - scaledW) / 2f
        val top  = (vh - scaledH) / 2f
        imageRect = RectF(left, top, left + scaledW, top + scaledH)
        binding.overlayView.imageRect = imageRect
    }

    private fun activateMode(mode: SelectMode) {
        // Повторный тап по активной кнопке — сбросить эту колонку
        if (selectMode == mode) {
            selectMode = SelectMode.NONE
            when (mode) {
                SelectMode.NAME -> nameFraction = null
                SelectMode.X    -> xFraction = null
                SelectMode.Y    -> yFraction = null
                SelectMode.NONE -> {}
            }
        } else {
            selectMode = mode
        }
        updateSelectionUI()
    }

    private fun onImageTap(touchX: Float, touchY: Float) {
        if (selectMode == SelectMode.NONE) return
        if (imageRect.isEmpty) return

        // Переводим координату тапа в дробь от ширины изображения
        val fraction = ((touchX - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)

        when (selectMode) {
            SelectMode.NAME -> nameFraction = fraction
            SelectMode.X    -> xFraction    = fraction
            SelectMode.Y    -> yFraction    = fraction
            SelectMode.NONE -> return
        }
        selectMode = SelectMode.NONE
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        // Обновляем оверлей
        val marks = buildList {
            nameFraction?.let { add(ColumnOverlayView.ColumnMark(it, COLOR_NAME, "НАЗВАНИЕ")) }
            xFraction?.let    { add(ColumnOverlayView.ColumnMark(it, COLOR_X,    "X")) }
            yFraction?.let    { add(ColumnOverlayView.ColumnMark(it, COLOR_Y,    "Y")) }
        }
        binding.overlayView.setMarks(marks)

        // Состояния кнопок колонок
        styleColumnButton(binding.btnColName, nameFraction != null, selectMode == SelectMode.NAME, COLOR_NAME, "НАЗВАНИЕ")
        styleColumnButton(binding.btnColX,    xFraction    != null, selectMode == SelectMode.X,    COLOR_X,    "X")
        styleColumnButton(binding.btnColY,    yFraction    != null, selectMode == SelectMode.Y,    COLOR_Y,    "Y")

        // Подсказка посередине фото
        if (selectMode != SelectMode.NONE) {
            val label = when (selectMode) {
                SelectMode.NAME -> "НАЗВАНИЕ"
                SelectMode.X    -> "X"
                SelectMode.Y    -> "Y"
                SelectMode.NONE -> ""
            }
            binding.tvTapHint.text = "Тапните по колонке\n$label"
            binding.tvTapHint.visibility = View.VISIBLE
        } else {
            binding.tvTapHint.visibility = View.GONE
        }

        // Статус
        val ready = nameFraction != null && xFraction != null && yFraction != null
        binding.tvSelectionStatus.text = when {
            ready -> "Все колонки выбраны. Нажмите СКАНИРОВАТЬ"
            selectMode != SelectMode.NONE -> "Тапните по нужной колонке на фото"
            else -> "Нажмите кнопку, затем тапните по нужной колонке"
        }

        binding.btnScanColumns.isEnabled = ready
    }

    private fun styleColumnButton(
        btn: com.google.android.material.button.MaterialButton,
        confirmed: Boolean,
        active: Boolean,
        color: Int,
        label: String
    ) {
        when {
            active -> {
                btn.backgroundTintList = ColorStateList.valueOf(color)
                btn.setTextColor(0xFFFFFFFF.toInt())
                btn.text = "↓ $label"
            }
            confirmed -> {
                btn.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(color)
                btn.text = "✓ $label"
            }
            else -> {
                btn.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                btn.setTextColor(color)
                btn.text = label
            }
        }
    }

    // ── OCR и сопоставление строк ────────────────────────────

    private fun runOcr() {
        val bitmap = capturedBitmap ?: return
        val nf = nameFraction ?: return
        val xf = xFraction    ?: return
        val yf = yFraction    ?: return

        binding.progressBarOcr.visibility = View.VISIBLE
        binding.btnScanColumns.isEnabled = false

        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rows = RowMatcher.match(
                    visionText   = visionText,
                    bitmapWidth  = bitmap.width,
                    nameFraction = nf,
                    xFraction    = xf,
                    yFraction    = yf
                )
                runOnUiThread {
                    binding.progressBarOcr.visibility = View.GONE
                    if (rows.isEmpty()) {
                        Toast.makeText(
                            this,
                            "Строк не найдено. Проверьте выбор колонок.",
                            Toast.LENGTH_LONG
                        ).show()
                        binding.btnScanColumns.isEnabled = true
                    } else {
                        showResultsPhase(rows)
                    }
                }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    binding.progressBarOcr.visibility = View.GONE
                    binding.btnScanColumns.isEnabled = true
                    Toast.makeText(this, "Ошибка OCR: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ── Фаза 3: результаты ───────────────────────────────────

    private fun setupResultsPanel() {
        adapter = NamedCoordAdapter { updateResultButtons() }
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.checkboxSelectAll.setOnCheckedChangeListener { _, checked ->
            adapter.selectAll(checked)
        }

        binding.btnBackToSelection.setOnClickListener {
            // Возвращаемся к экрану выбора колонок без сброса фракций
            binding.resultsContainer.visibility = View.GONE
            binding.selectionContainer.visibility = View.VISIBLE
            binding.btnScanColumns.isEnabled =
                nameFraction != null && xFraction != null && yFraction != null
        }

        binding.btnSaveSelected.setOnClickListener { saveSelected() }
        binding.btnExportGpx.setOnClickListener   { exportGpx() }
    }

    private fun showResultsPhase(rows: List<MatchedRow>) {
        adapter.setData(rows)
        binding.checkboxSelectAll.isChecked = true

        binding.selectionContainer.visibility = View.GONE
        binding.resultsContainer.visibility = View.VISIBLE

        updateResultButtons()
        Toast.makeText(this, "Найдено строк: ${rows.size}", Toast.LENGTH_SHORT).show()
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
        val rows = adapter.getSelected()
        if (rows.isEmpty()) return

        val points = rows.map { row -> rowToPoint(row, source = "scan") }
        viewModel.insertAll(points)
        Toast.makeText(this, "Сохранено ${points.size} точек", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun exportGpx() {
        val rows = adapter.getSelected()
        if (rows.isEmpty()) return
        val points = rows.map { row -> rowToPoint(row, source = "scan") }
        GpxExporter.exportAndShare(this, points)
    }

    private fun rowToPoint(row: MatchedRow, source: String): Point {
        val (lat, lon) = CoordConverter.sk42ToWgs84(row.x, row.y, row.zone)
        return Point(
            name     = row.name.ifEmpty { "Точка" },
            xSk42    = row.x,
            ySk42    = row.y,
            zone     = row.zone,
            latWgs84 = lat,
            lonWgs84 = lon,
            source   = source
        )
    }
}
