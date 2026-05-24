package com.coordscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.CoordResultAdapter
import com.coordscanner.databinding.ActivityPhotoBatchBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.OcrParser
import com.coordscanner.viewmodel.PointViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class PhotoBatchActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhotoBatch"
        const val EXTRA_GALLERY_ONLY = "gallery_only"
    }

    private lateinit var binding: ActivityPhotoBatchBinding
    private val viewModel: PointViewModel by viewModels()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private lateinit var imageCapture: ImageCapture
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var adapter: CoordResultAdapter

    private var galleryOnly = false
    private var selectedBatchColor = "#0055FF"

    private val batchColorMap: Map<Int, String> by lazy {
        mapOf(
            R.id.batchColorBlue   to "#0055FF",
            R.id.batchColorRed    to "#FF2020",
            R.id.batchColorGreen  to "#00BB00",
            R.id.batchColorOrange to "#FF8800",
            R.id.batchColorYellow to "#FFD700",
            R.id.batchColorPurple to "#9900CC",
            R.id.batchColorWhite  to "#FFFFFF"
        )
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            processImageUri(uri)
        } else if (galleryOnly && adapter.itemCount == 0) {
            finish()
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        galleryOnly = intent.getBooleanExtra(EXTRA_GALLERY_ONLY, false)

        adapter = CoordResultAdapter { updateSaveButton() }
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        setupBatchColorPicker()

        if (!galleryOnly) {
            setupZoom()
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddMore.setOnClickListener {
            if (galleryOnly) {
                binding.resultsContainer.visibility = View.GONE
                pickImageLauncher.launch("image/*")
            } else {
                showCamera()
            }
        }
        binding.btnSaveSelected.setOnClickListener { saveSelected() }
        binding.btnClearResults.setOnClickListener {
            adapter.clearAll()
            if (galleryOnly) {
                pickImageLauncher.launch("image/*")
            } else {
                showCamera()
            }
        }
        binding.checkboxSelectAll.setOnCheckedChangeListener { _, checked ->
            adapter.selectAll(checked)
        }

        if (galleryOnly) {
            binding.cameraContainer.visibility = View.GONE
            pickImageLauncher.launch("image/*")
        } else {
            binding.btnCapture.setOnClickListener { takePhoto() }
            binding.btnGallery.setOnClickListener { pickImageLauncher.launch("image/*") }
            binding.btnZoomIn.setOnClickListener { adjustZoom(1.4f) }
            binding.btnZoomOut.setOnClickListener { adjustZoom(1f / 1.4f) }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) startCamera()
            else requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ── Batch color picker ────────────────────────────────────

    private fun setupBatchColorPicker() {
        for ((viewId, color) in batchColorMap) {
            findViewById<View>(viewId).setOnClickListener { selectBatchColor(color) }
        }
        selectBatchColor(selectedBatchColor)
    }

    private fun selectBatchColor(color: String) {
        selectedBatchColor = color
        for ((viewId, dotColor) in batchColorMap) {
            val dot = findViewById<View>(viewId)
            val scale = if (dotColor == color) 1.4f else 1.0f
            dot.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
        }
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

    // ── Camera ────────────────────────────────────────────────

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

    private fun showCamera() {
        binding.cameraContainer.visibility = View.VISIBLE
        binding.resultsContainer.visibility = View.GONE
    }

    // ── Photo capture ─────────────────────────────────────────

    private fun takePhoto() {
        val photoFile = File(cacheDir, "batch_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        binding.progressBar.visibility = View.VISIBLE
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(photoFile.absolutePath)
                    if (bmp != null) processImage(InputImage.fromBitmap(bmp, 0))
                    else {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@PhotoBatchActivity, "Ошибка съёмки", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@PhotoBatchActivity, "Ошибка: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun processImageUri(uri: Uri) {
        try {
            binding.progressBar.visibility = View.VISIBLE
            processImage(InputImage.fromFilePath(this, uri))
        } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage(image: InputImage) {
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                val parsed = OcrParser.parseText(rawText)
                val detectedTitle = OcrParser.extractTableTitle(rawText)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    if (parsed.isNotEmpty()) {
                        adapter.addAll(parsed)
                        if (detectedTitle.isNotEmpty() && binding.etBatchName.text.isBlank()) {
                            binding.etBatchName.setText(detectedTitle)
                        }
                        showResults()
                        Toast.makeText(this, "Найдено: ${parsed.size} координат", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Координаты не найдены — попробуйте ближе или другой угол", Toast.LENGTH_LONG).show()
                        if (galleryOnly) pickImageLauncher.launch("image/*")
                    }
                }
            }
            .addOnFailureListener {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Ошибка распознавания", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ── Results ───────────────────────────────────────────────

    private fun showResults() {
        binding.cameraContainer.visibility = View.GONE
        binding.resultsContainer.visibility = View.VISIBLE
        binding.checkboxSelectAll.isChecked = true
        buildColumnPicker()
        updateSaveButton()
    }

    private fun buildColumnPicker() {
        val options = adapter.getColumnOptions()
        if (options.size < 2) {
            binding.columnPickerContainer.visibility = View.GONE
            return
        }
        binding.columnPickerContainer.visibility = View.VISIBLE
        binding.columnPickerRow.removeAllViews()

        options.forEach { opt ->
            val btn = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = opt.label
                textSize = 11f
                @Suppress("DEPRECATION")
                isAllCaps = false
                setPadding(24, 0, 24, 0)
                minWidth = 0
                minimumWidth = 0
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    (40 * resources.displayMetrics.density).toInt()
                )
                lp.marginEnd = 8
                layoutParams = lp
                setOnClickListener {
                    adapter.setNamesFromMode(opt.mode)
                    binding.etBatchName.text?.clear()
                }
            }
            binding.columnPickerRow.addView(btn)
        }
    }

    private fun updateSaveButton() {
        val selected = adapter.getSelectedCount()
        val total = adapter.itemCount
        binding.tvResultCount.text = "Найдено: $total  •  Выбрано: $selected"
        binding.btnSaveSelected.text = "СОХРАНИТЬ ($selected)"
        binding.btnSaveSelected.isEnabled = selected > 0
    }

    // ── Save ──────────────────────────────────────────────────

    private fun saveSelected() {
        val selected = adapter.getSelected()
        if (selected.isEmpty()) return
        val manualName = binding.etBatchName.text.toString().trim()
        val color = selectedBatchColor
        val points = selected.map { p ->
            val (lat, lon) = if (!p.isWgs84)
                CoordConverter.sk42ToWgs84(p.x, p.y, p.zone)
            else Pair(p.lat, p.lon)
            // p.name is already currentName (set by column selection or default).
            // Manual field overrides only if column selection was NOT used.
            val name = when {
                adapter.hasColumnSelection -> p.name.ifEmpty { "Точка" }
                manualName.isNotEmpty()    -> manualName
                else                       -> p.name.ifEmpty { "Точка" }
            }
            Point(
                name     = name,
                xSk42    = if (!p.isWgs84) p.x else 0.0,
                ySk42    = if (!p.isWgs84) p.y else 0.0,
                zone     = p.zone,
                latWgs84 = lat,
                lonWgs84 = lon,
                source   = "scan",
                color    = color
            )
        }
        viewModel.insertAll(points)
        Toast.makeText(this, "Сохранено ${points.size} точек", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
