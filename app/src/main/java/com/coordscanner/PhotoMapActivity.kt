package com.coordscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.coordscanner.databinding.ActivityPhotoMapBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.viewmodel.PointViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class PhotoMapActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "photo_map_prefs"
        private const val KEY_MODE   = "coord_mode"
        private const val KEY_ZONE   = "default_zone"
    }

    private lateinit var binding: ActivityPhotoMapBinding
    private val viewModel: PointViewModel by viewModels()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private lateinit var imageCapture: ImageCapture
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var capturedBitmap: Bitmap? = null
    private var coordMode = "sk42"
    private var defaultZone = 7

    // Last successful query result
    private var lastSk42X: Double? = null
    private var lastSk42Y: Double? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show(); finish() }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) loadFromUri(uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        coordMode = prefs.getString(KEY_MODE, "sk42") ?: "sk42"
        defaultZone = prefs.getInt(KEY_ZONE, 7)

        setupCameraPhase()
        setupWorkPhase()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.workContainer.visibility == View.VISIBLE) showCameraPhase()
        else super.onBackPressed()
    }

    // ── Camera phase ──────────────────────────────────────────────────────────

    private fun setupCameraPhase() {
        setupZoom()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnZoomIn.setOnClickListener  { adjustZoom(1.4f) }
        binding.btnZoomOut.setOnClickListener { adjustZoom(1f / 1.4f) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
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
            v.performClick(); true
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
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                camera?.cameraInfo?.zoomState?.observe(this) {
                    binding.tvZoomLevel.text = "%.1f×".format(it.zoomRatio)
                }
            } catch (e: Exception) { /* camera unavailable */ }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val file = File(cacheDir, "photomap_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        binding.progressBarCamera.visibility = View.VISIBLE
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.progressBarCamera.visibility = View.GONE
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) showWorkPhase(bmp)
                    else Toast.makeText(this@PhotoMapActivity, "Ошибка съёмки", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    binding.progressBarCamera.visibility = View.GONE
                    Toast.makeText(this@PhotoMapActivity, "Ошибка: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun loadFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bmp != null) showWorkPhase(bmp)
                else Toast.makeText(this@PhotoMapActivity, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCameraPhase() {
        binding.cameraContainer.visibility = View.VISIBLE
        binding.workContainer.visibility   = View.GONE
    }

    // ── Work phase (геопривязка + запрос) ────────────────────────────────────

    private fun setupWorkPhase() {
        binding.btnWorkBack.setOnClickListener { showCameraPhase() }
        binding.btnCoordMode.setOnClickListener { showCoordModeDialog() }

        binding.btnAddX.setOnClickListener { setMode(PhotoMapOverlayView.Mode.ADD_X) }
        binding.btnAddY.setOnClickListener { setMode(PhotoMapOverlayView.Mode.ADD_Y) }
        binding.btnQuery.setOnClickListener { setMode(PhotoMapOverlayView.Mode.QUERY) }
        binding.btnSavePoint.setOnClickListener { saveCurrentPoint() }

        setupOverlayCallbacks()
        updateCoordModeUI()
    }

    private fun showWorkPhase(bitmap: Bitmap) {
        capturedBitmap = bitmap
        binding.cameraContainer.visibility = View.GONE
        binding.workContainer.visibility   = View.VISIBLE
        binding.ivPhoto.setImageBitmap(bitmap)
        binding.overlayView.clearAll()
        binding.resultPanel.visibility = View.GONE
        clearLastQuery()
        binding.ivPhoto.doOnLayout { computeImageRect(bitmap) }
        setMode(PhotoMapOverlayView.Mode.ADD_X)
        updateStatusLine()
    }

    private fun computeImageRect(bitmap: Bitmap) {
        val vw = binding.ivPhoto.width.toFloat()
        val vh = binding.ivPhoto.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val scale = minOf(vw / bitmap.width, vh / bitmap.height)
        val scaledW = bitmap.width * scale
        val scaledH = bitmap.height * scale
        binding.overlayView.imageRect = RectF(
            (vw - scaledW) / 2f, (vh - scaledH) / 2f,
            (vw + scaledW) / 2f, (vh + scaledH) / 2f
        )
        binding.overlayView.invalidate()
    }

    private fun setupOverlayCallbacks() {
        binding.overlayView.onXLineTapped = { yFrac ->
            val tmp = binding.overlayView.addXLine(yFrac, 0.0)
            val (title, hint, msg) = xLineDialogParams()
            showValueDialog(title, hint, msg) { value ->
                if (value != null) binding.overlayView.updateLineValue(true, tmp.id, value)
                else binding.overlayView.removeLineById(true, tmp.id)
                updateStatusLine()
            }
        }

        binding.overlayView.onYLineTapped = { xFrac ->
            val tmp = binding.overlayView.addYLine(xFrac, 0.0)
            val (title, hint, msg) = yLineDialogParams()
            showValueDialog(title, hint, msg) { value ->
                if (value != null) binding.overlayView.updateLineValue(false, tmp.id, value)
                else binding.overlayView.removeLineById(false, tmp.id)
                updateStatusLine()
            }
        }

        binding.overlayView.onQueryTapped = { xFrac, yFrac ->
            val ov = binding.overlayView
            val xVal = interpolate(yFrac, ov.xLines) // вертикальная позиция → X/northing/lat
            val yVal = interpolate(xFrac, ov.yLines) // горизонтальная позиция → Y/easting/lon
            if (xVal == null || yVal == null) {
                val need = buildString {
                    if (ov.xLines.size < 2) append("нужно ≥2 X линий")
                    if (ov.xLines.size < 2 && ov.yLines.size < 2) append(", ")
                    if (ov.yLines.size < 2) append("нужно ≥2 Y линий")
                }
                Toast.makeText(this, "Недостаточно линий: $need", Toast.LENGTH_SHORT).show()
            } else {
                ov.setQueryPoint(xFrac, yFrac)
                showQueryResult(xVal, yVal)
            }
        }

        binding.overlayView.onLineLongPressed = { isX, id ->
            AlertDialog.Builder(this)
                .setTitle("Удалить ${if (isX) "X" else "Y"} линию?")
                .setMessage("Удерживайте над линией и подтвердите удаление.")
                .setPositiveButton("Удалить") { _, _ ->
                    binding.overlayView.removeLineById(isX, id)
                    updateStatusLine()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    // ── Mode buttons ──────────────────────────────────────────────────────────

    private fun setMode(mode: PhotoMapOverlayView.Mode) {
        binding.overlayView.mode = mode
        updateModeButtons()
    }

    private fun updateModeButtons() {
        val m = binding.overlayView.mode
        styleBtn(binding.btnAddX, m == PhotoMapOverlayView.Mode.ADD_X,
            PhotoMapOverlayView.COLOR_X, "+ X ЗОНА")
        styleBtn(binding.btnAddY, m == PhotoMapOverlayView.Mode.ADD_Y,
            PhotoMapOverlayView.COLOR_Y, "+ Y ЗОНА")
        styleBtn(binding.btnQuery, m == PhotoMapOverlayView.Mode.QUERY,
            0xFF2196F3.toInt(), "ЗАПРОС ✚")
    }

    private fun styleBtn(
        btn: com.google.android.material.button.MaterialButton,
        active: Boolean, color: Int, label: String
    ) {
        btn.text = label
        if (active) {
            btn.backgroundTintList = ColorStateList.valueOf(color)
            btn.setTextColor(Color.WHITE)
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(color)
        }
        btn.strokeColor = ColorStateList.valueOf(color)
    }

    // ── Status & coord mode ───────────────────────────────────────────────────

    private fun updateStatusLine() {
        val xN = binding.overlayView.xLines.size
        val yN = binding.overlayView.yLines.size
        val max = PhotoMapOverlayView.MAX_LINES
        binding.tvStatus.text = "X: $xN/$max   Y: $yN/$max  •  долгое нажатие → удалить"
    }

    private fun updateCoordModeUI() {
        val isSk42 = coordMode == "sk42"
        binding.btnCoordMode.text = if (isSk42) "СК-42 ▼" else "WGS-84 ▼"
        val clr = if (isSk42) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt()
        binding.btnCoordMode.backgroundTintList = ColorStateList.valueOf(clr)
        binding.btnCoordMode.setTextColor(Color.WHITE)
    }

    private fun showCoordModeDialog() {
        val hasLines = binding.overlayView.xLines.isNotEmpty() || binding.overlayView.yLines.isNotEmpty()
        val items = arrayOf("СК-42  (Гаусс-Крюгер)", "WGS-84  (широта / долгота °)")
        val curIdx = if (coordMode == "sk42") 0 else 1
        var selIdx = curIdx
        var pendZone = defaultZone

        val density = resources.displayMetrics.density

        val zoneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
            visibility = if (coordMode == "sk42") View.VISIBLE else View.GONE
        }
        val zoneLabel = TextView(this).apply {
            text = "Зона по умолч.:  "
            setTextColor(0xFFDDDDDD.toInt())
        }
        val zoneEdit = EditText(this).apply {
            setText(pendZone.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.edit_bg)
            setPadding((16 * density).toInt(), (8 * density).toInt(),
                       (16 * density).toInt(), (8 * density).toInt())
            minWidth = (64 * density).toInt()
        }
        zoneRow.addView(zoneLabel)
        zoneRow.addView(zoneEdit)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), (8 * density).toInt())
            addView(zoneRow)
        }

        AlertDialog.Builder(this)
            .setTitle("Система координат")
            .setSingleChoiceItems(items, curIdx) { _, idx ->
                selIdx = idx
                zoneRow.visibility = if (idx == 0) View.VISIBLE else View.GONE
            }
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val newMode = if (selIdx == 0) "sk42" else "wgs84"
                val newZone = zoneEdit.text.toString().toIntOrNull()?.coerceIn(1, 9) ?: 7
                if (newMode != coordMode && hasLines) {
                    AlertDialog.Builder(this)
                        .setTitle("Сменить систему?")
                        .setMessage("Все опорные линии будут удалены.")
                        .setPositiveButton("Сменить") { _, _ -> applyCoordMode(newMode, newZone, true) }
                        .setNegativeButton("Отмена", null).show()
                } else {
                    applyCoordMode(newMode, newZone, false)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyCoordMode(mode: String, zone: Int, clearLines: Boolean) {
        coordMode = mode; defaultZone = zone
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode).putInt(KEY_ZONE, zone).apply()
        if (clearLines) {
            binding.overlayView.clearAll()
            binding.resultPanel.visibility = View.GONE
            clearLastQuery()
        }
        updateCoordModeUI()
        updateStatusLine()
        updateModeButtons()
    }

    // ── Query result & save ───────────────────────────────────────────────────

    private fun showQueryResult(xVal: Double, yVal: Double) {
        if (coordMode == "sk42") {
            val zone = if (yVal > 1_000_000.0) (yVal / 1_000_000.0).toInt() else defaultZone
            val (lat, lon) = CoordConverter.sk42ToWgs84(xVal, yVal, zone)
            lastSk42X = xVal; lastSk42Y = yVal; lastLat = lat; lastLon = lon
            binding.tvCoordResult.text =
                "СК-42:  X = %.0f\n        Y = %.0f\nWGS-84: %.6f°N   %.6f°E"
                    .format(xVal, yVal, lat, lon)
        } else {
            lastLat = xVal; lastLon = yVal; lastSk42X = null; lastSk42Y = null
            binding.tvCoordResult.text =
                "WGS-84: %.6f°N\n        %.6f°E".format(xVal, yVal)
        }
        binding.resultPanel.visibility = View.VISIBLE
    }

    private fun saveCurrentPoint() {
        val lat = lastLat ?: return
        val lon = lastLon ?: return
        val name = binding.etPointName.text.toString().trim().ifEmpty { "Точка" }
        val sk42x = lastSk42X ?: 0.0
        val sk42y = lastSk42Y ?: 0.0
        val zone  = if (sk42y > 1_000_000.0) (sk42y / 1_000_000.0).toInt()
                    else if (lastSk42X != null) defaultZone else 0
        viewModel.insert(Point(
            name     = name,
            xSk42    = sk42x,
            ySk42    = sk42y,
            zone     = zone,
            latWgs84 = lat,
            lonWgs84 = lon,
            source   = "photomap"
        ))
        binding.etPointName.text?.clear()
        Toast.makeText(this, "Сохранено: $name", Toast.LENGTH_SHORT).show()
    }

    private fun clearLastQuery() {
        lastSk42X = null; lastSk42Y = null; lastLat = null; lastLon = null
    }

    // ── Interpolation ─────────────────────────────────────────────────────────

    private fun interpolate(fraction: Float, lines: List<PhotoMapOverlayView.RefLine>): Double? {
        if (lines.size < 2) return null
        val s = lines.sortedBy { it.position }
        val lo = s.lastOrNull  { it.position <= fraction }
        val hi = s.firstOrNull { it.position > fraction }
        fun lerp(f0: Double, v0: Double, f1: Double, v1: Double, t: Float): Double {
            if (f1 == f0) return v0
            return v0 + (t - f0) * (v1 - v0) / (f1 - f0)
        }
        return when {
            lo == null -> lerp(s[0].position.toDouble(), s[0].value,
                               s[1].position.toDouble(), s[1].value, fraction)
            hi == null -> lerp(s[s.size-2].position.toDouble(), s[s.size-2].value,
                               s[s.size-1].position.toDouble(), s[s.size-1].value, fraction)
            else       -> lerp(lo.position.toDouble(), lo.value,
                               hi.position.toDouble(), hi.value, fraction)
        }
    }

    // ── Dialog helpers ────────────────────────────────────────────────────────

    private fun xLineDialogParams(): Triple<String, String, String?> {
        return if (coordMode == "sk42")
            Triple("X (northing)", "напр. 5432100", null)
        else
            Triple("Широта (latitude)", "напр. 48.1234", null)
    }

    private fun yLineDialogParams(): Triple<String, String, String?> {
        return if (coordMode == "sk42")
            Triple("Y (easting)", "напр. 7456000",
                   "Введите Y с зональным префиксом:\n7456000 = зона 7")
        else
            Triple("Долгота (longitude)", "напр. 37.5678", null)
    }

    private fun showValueDialog(
        title: String, hint: String, message: String?,
        onResult: (Double?) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
            setHint(hint)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF556070.toInt())
            setBackgroundResource(R.drawable.edit_bg)
            setPadding((20 * density).toInt(), (14 * density).toInt(),
                       (20 * density).toInt(), (14 * density).toInt())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(),
                       (24 * density).toInt(), (4 * density).toInt())
            if (message != null) {
                addView(TextView(this@PhotoMapActivity).apply {
                    text = message
                    setTextColor(0xFF8BB4D8.toInt())
                    textSize = 12f
                    setPadding(0, 0, 0, (10 * density).toInt())
                })
            }
            addView(editText)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val raw = editText.text.toString().trim().replace(" ", "").replace(",", ".")
                onResult(raw.toDoubleOrNull())
            }
            .setNegativeButton("Отмена") { _, _ -> onResult(null) }
            .create()
        dlg.show()
        editText.requestFocus()
        editText.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }
}
