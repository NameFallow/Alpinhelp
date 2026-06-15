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
import android.widget.SeekBar
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
import com.coordscanner.utils.AiBadge
import com.coordscanner.utils.AiPrefs
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.GeminiScanner
import com.coordscanner.utils.GpxExporter
import com.coordscanner.utils.MatchedRow
import com.coordscanner.utils.RowMatcher
import com.coordscanner.viewmodel.PointViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@OptIn(ExperimentalGetImage::class)
class ColumnSelectorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ColumnSelector"
    }

    private lateinit var binding: ActivityColumnSelectorBinding
    private val viewModel: PointViewModel by viewModels()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private lateinit var imageCapture: ImageCapture
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var capturedBitmap: Bitmap? = null
    private var imageRect = RectF()

    private lateinit var adapter: NamedCoordAdapter

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
        binding = ActivityColumnSelectorBinding.inflate(layoutInflater)
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
        val file = File(cacheDir, "colsel_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        binding.progressBarCamera.visibility = View.VISIBLE
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.progressBarCamera.visibility = View.GONE
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
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
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.onFailure { Log.e(TAG, "loadFromUri failed", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bmp != null) showSelectionPhase(bmp)
                else Toast.makeText(this@ColumnSelectorActivity,
                    "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Фаза 2: выделение колонок прямоугольниками ───────────

    private fun setupSelectionPanel() {
        // Кнопки устанавливают activeType на оверлей; оверлей сам обрабатывает рисование
        binding.btnColName.setOnClickListener { toggleColumnMode(SelectionOverlayView.ColumnType.NAME) }
        binding.btnColX.setOnClickListener   { toggleColumnMode(SelectionOverlayView.ColumnType.X) }
        binding.btnColY.setOnClickListener   { toggleColumnMode(SelectionOverlayView.ColumnType.Y) }
        binding.btnScanColumns.setOnClickListener { runOcr() }

        // Callback: прямоугольник подтверждён (ACTION_UP с нормальным размером)
        binding.overlayView.onRectConfirmed = { _, _ -> updateSelectionUI() }

        // Callback: состояние изменилось (activeType сменился)
        binding.overlayView.onSelectionStateChanged = { updateSelectionUI() }
    }

    // Переключает режим выделения: повторный тап отменяет, не очищая готовый прямоугольник
    private fun toggleColumnMode(type: SelectionOverlayView.ColumnType) {
        binding.overlayView.activeType = if (binding.overlayView.activeType == type) null else type
        updateSelectionUI()
    }

    private fun showSelectionPhase(bitmap: Bitmap) {
        capturedBitmap = bitmap
        binding.overlayView.clearAll()

        binding.ivPhoto.setImageBitmap(bitmap)
        binding.cameraContainer.visibility = View.GONE
        binding.selectionContainer.visibility = View.VISIBLE
        binding.resultsContainer.visibility = View.GONE

        // imageRect вычисляем после layout
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
        styleColumnButton(
            binding.btnColName,
            confirmed = overlay.getConfirmedRect(SelectionOverlayView.ColumnType.NAME) != null,
            active    = overlay.activeType == SelectionOverlayView.ColumnType.NAME,
            color     = SelectionOverlayView.COLOR_NAME,
            label     = "НАЗВАНИЕ"
        )
        styleColumnButton(
            binding.btnColX,
            confirmed = overlay.getConfirmedRect(SelectionOverlayView.ColumnType.X) != null,
            active    = overlay.activeType == SelectionOverlayView.ColumnType.X,
            color     = SelectionOverlayView.COLOR_X,
            label     = "X"
        )
        styleColumnButton(
            binding.btnColY,
            confirmed = overlay.getConfirmedRect(SelectionOverlayView.ColumnType.Y) != null,
            active    = overlay.activeType == SelectionOverlayView.ColumnType.Y,
            color     = SelectionOverlayView.COLOR_Y,
            label     = "Y"
        )

        val allReady = overlay.hasAllRects()
        val anyActive = overlay.activeType != null
        binding.tvSelectionStatus.text = when {
            anyActive  -> "Нарисуйте прямоугольник вокруг нужной колонки"
            allReady   -> "Все колонки выделены. Нажмите СКАНИРОВАТЬ"
            else       -> "Нажмите кнопку, затем нарисуйте область на фото"
        }
        binding.btnScanColumns.isEnabled = allReady && !anyActive
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
                btn.setTextColor(Color.WHITE)
                btn.text = "✏ $label"
            }
            confirmed -> {
                btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btn.setTextColor(color)
                btn.text = "✓ $label"
            }
            else -> {
                btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btn.setTextColor(color)
                btn.text = label
            }
        }
    }

    // ── OCR ──────────────────────────────────────────────────

    private fun runOcr() {
        val bitmap = capturedBitmap ?: return
        val overlay = binding.overlayView
        val nameRect = overlay.getConfirmedRect(SelectionOverlayView.ColumnType.NAME) ?: return
        val xRect    = overlay.getConfirmedRect(SelectionOverlayView.ColumnType.X)    ?: return
        val yRect    = overlay.getConfirmedRect(SelectionOverlayView.ColumnType.Y)    ?: return

        binding.progressBarOcr.visibility = View.VISIBLE
        binding.btnScanColumns.isEnabled = false

        lifecycleScope.launch {
            val rows = scanColumns(bitmap, nameRect, xRect, yRect)
            binding.progressBarOcr.visibility = View.GONE
            if (rows.isEmpty()) {
                binding.btnScanColumns.isEnabled = true
                Toast.makeText(
                    this@ColumnSelectorActivity,
                    "Строк не найдено. Проверьте выделенные области.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                showResultsPhase(rows)
            }
        }
    }

    private suspend fun scanColumns(
        bitmap: Bitmap,
        nameRect: RectF,
        xRect: RectF,
        yRect: RectF,
    ): List<MatchedRow> {
        if (AiPrefs.isReadyToTry()) {
            Log.i(TAG, "AI attempt: source=${AiPrefs.source()}")
            val result = runCatching {
                GeminiScanner.scanSk42Columns(
                    bitmap = bitmap,
                    nameRect = nameRect,
                    xRect = xRect,
                    yRect = yRect,
                    imageViewRect = imageRect,
                    apiKey = AiPrefs.apiKey(),
                )
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
        return runMlKitMatch(bitmap, nameRect, xRect, yRect)
    }

    private suspend fun runMlKitMatch(
        bitmap: Bitmap,
        nameRect: RectF,
        xRect: RectF,
        yRect: RectF,
    ): List<MatchedRow> = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    val rows = RowMatcher.match(
                        visionText    = visionText,
                        bitmapWidth   = bitmap.width,
                        bitmapHeight  = bitmap.height,
                        nameViewRect  = nameRect,
                        xViewRect     = xRect,
                        yViewRect     = yRect,
                        imageViewRect = imageRect
                    )
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
        adapter = NamedCoordAdapter(onSelectionChanged = { updateResultButtons() })
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.checkboxSelectAll.setOnCheckedChangeListener { _, checked ->
            adapter.selectAll(checked)
        }

        binding.btnBackToSelection.setOnClickListener {
            binding.resultsContainer.visibility = View.GONE
            binding.selectionContainer.visibility = View.VISIBLE
            binding.btnScanColumns.isEnabled = binding.overlayView.hasAllRects()
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
