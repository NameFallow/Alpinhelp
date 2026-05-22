package com.coordscanner

import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coordscanner.databinding.ActivityGpxManagerBinding
import com.coordscanner.model.WayPoint
import com.coordscanner.utils.FilePickerHelper
import com.coordscanner.utils.GpxParser
import com.coordscanner.viewmodel.GpxManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker

class GpxManagerActivity : AppCompatActivity() {

    private lateinit var b: ActivityGpxManagerBinding
    val vm: GpxManagerViewModel by viewModels()

    private lateinit var mapView: MapView
    private var markersOverlay = FolderOverlay()
    private var selectionOverlay: MapSelectionOverlay? = null
    private val iconCache = mutableMapOf<String, Drawable>()

    private var isSelectMode = false
    private var isAddMode = false

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { dispatchFile(it) }
    }

    private lateinit var filePickerHelper: FilePickerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        b = ActivityGpxManagerBinding.inflate(layoutInflater)
        setContentView(b.root)

        filePickerHelper = FilePickerHelper(this) { folder ->
            toast("Папка выбрана: ${folder.name}")
            val files = filePickerHelper.listGpxKmzFiles()
            toast("Найдено файлов: ${files.size}")
        }

        setupMap()
        setupButtons()
        observeViewModel()
    }

    // --- Карта ---

    private fun setupMap() {
        mapView = b.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(48.0, 37.8))
        mapView.overlays.add(markersOverlay)
    }

    // --- Кнопки ---

    private fun setupButtons() {
        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        b.btnOpenFile.setOnClickListener {
            openFileLauncher.launch(arrayOf("*/*"))
        }

        b.btnOpenFile.setOnLongClickListener {
            filePickerHelper.requestFolderAccess()
            true
        }

        b.btnSelectArea.setOnClickListener {
            if (isSelectMode) disableSelectionMode() else enableSelectionMode(addMode = false)
        }

        b.btnAddArea.setOnClickListener {
            enableSelectionMode(addMode = true)
        }

        b.btnPaste.setOnClickListener {
            if (vm.isBufferEmpty) { toast("Буфер пуст"); return@setOnClickListener }
            vm.pasteToNewFile()
            toast("Вставлено ${vm.buffer.value?.size ?: 0} точек")
        }

        b.btnManageFile.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("newfile") == null) {
                NewFileFragment().show(supportFragmentManager, "newfile")
            }
        }
    }

    // --- Наблюдение за ViewModel ---

    private fun observeViewModel() {
        vm.allPoints.observe(this) { pts ->
            refreshMarkers(pts, vm.selected.value.orEmpty())
            b.tvLoadedCount.text = "Точек: ${pts.size}"
        }

        vm.selected.observe(this) { sel ->
            refreshMarkers(vm.allPoints.value.orEmpty(), sel)
            b.tvSelectedCount.text = "Выбрано: ${sel.size}"
            b.tvSelectedCount.visibility = if (sel.isEmpty()) View.GONE else View.VISIBLE
        }

        vm.buffer.observe(this) { buf ->
            b.tvBufferCount.text   = "Буфер: ${buf.size}"
            b.btnPaste.isEnabled   = buf.isNotEmpty()
        }

        vm.newFile.observe(this) { pts ->
            b.tvNewFileCount.text = "В файле: ${pts.size}"
        }
    }

    // --- Режим выделения ---

    private fun enableSelectionMode(addMode: Boolean) {
        isSelectMode = true
        isAddMode    = addMode
        mapView.setMultiTouchControls(false)

        if (selectionOverlay == null) {
            selectionOverlay = MapSelectionOverlay { box ->
                onBoxDrawn(box)
            }
        }
        selectionOverlay!!.reset()
        if (selectionOverlay !in mapView.overlays) {
            mapView.overlays.add(selectionOverlay)
        }
        mapView.invalidate()

        b.btnSelectArea.text = "✕ Отмена"
        b.btnAddArea.visibility = View.GONE
    }

    private fun disableSelectionMode() {
        isSelectMode = false
        isAddMode    = false
        mapView.setMultiTouchControls(true)
        selectionOverlay?.let { mapView.overlays.remove(it) }
        mapView.invalidate()

        val hasSel = vm.selected.value.orEmpty().isNotEmpty()
        b.btnSelectArea.text = if (hasSel) "Выделить ещё" else "Выделить область"
        b.btnAddArea.visibility = if (hasSel) View.VISIBLE else View.GONE
    }

    private fun onBoxDrawn(box: BoundingBox) {
        vm.selectInBox(box, addToExisting = isAddMode)
        disableSelectionMode()

        val count = vm.selected.value?.size ?: 0
        if (count > 0 && supportFragmentManager.findFragmentByTag("actions") == null) {
            PointsBottomSheet().show(supportFragmentManager, "actions")
        }
    }

    // --- Маркеры на карте ---

    private fun refreshMarkers(all: List<WayPoint>, selected: Set<WayPoint>) {
        mapView.overlays.remove(markersOverlay)
        markersOverlay = FolderOverlay()
        all.forEach { wp ->
            val m = Marker(mapView).apply {
                position  = GeoPoint(wp.lat, wp.lon)
                title     = wp.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon      = getMarkerIcon(if (wp in selected) "SELECTED" else wp.color)
            }
            markersOverlay.add(m)
        }
        // Маркеры — под оверлеем выделения (если он есть)
        val selIdx = mapView.overlays.indexOf(selectionOverlay)
        if (selIdx >= 0) mapView.overlays.add(selIdx, markersOverlay)
        else mapView.overlays.add(0, markersOverlay)
        mapView.invalidate()
    }

    private fun getMarkerIcon(colorKey: String): Drawable {
        return iconCache.getOrPut(colorKey) {
            val color = when (colorKey) {
                "SELECTED" -> Color.YELLOW
                else -> try { Color.parseColor(colorKey) } catch (e: Exception) { Color.RED }
            }
            val dp = resources.displayMetrics.density
            val r  = (dp * 8).toInt()
            val sz = r * 2
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = color
            canvas.drawCircle(r.toFloat(), r.toFloat(), r - 1.5f, paint)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            canvas.drawCircle(r.toFloat(), r.toFloat(), r - 1.5f, paint)
            BitmapDrawable(resources, bmp)
        }
    }

    // --- Маршрутизация файла: KMZ → KmzMapActivity, GPX → загрузить здесь ---

    private fun dispatchFile(uri: Uri) {
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
        val isKmz = name.endsWith(".kmz") || mime.contains("kmz") || mime.contains("vnd.google-earth")
        if (isKmz) {
            startActivity(Intent(this, KmzMapActivity::class.java).apply { data = uri })
        } else {
            loadGpxFile(uri)
        }
    }

    // --- Загрузка GPX файла ---

    private fun loadGpxFile(uri: Uri) {
        lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) } ?: emptyList()
                }.getOrElse { e ->
                    withContext(Dispatchers.Main) { toast("Ошибка: ${e.message}") }
                    emptyList()
                }
            }
            vm.loadPoints(pts)
            vm.sourceUri = uri
            if (pts.isNotEmpty()) {
                val bbox = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) })
                mapView.zoomToBoundingBox(bbox.increaseByScale(1.3f), true)
                toast("Загружено: ${pts.size} точек")
            }
        }
    }

    // --- Сохранение исходного файла после вырезания ---

    fun promptSaveSourceFile() {
        if (!vm.sourceModified || vm.sourceUri == null) return
        AlertDialog.Builder(this)
            .setTitle("Сохранить изменения в исходном файле?")
            .setMessage("Вырезанные точки будут удалены из источника.")
            .setPositiveButton("Сохранить") { _, _ -> saveSourceFile() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveSourceFile() {
        val uri = vm.sourceUri ?: return
        val pts = vm.allPoints.value.orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    GpxParser.write(pts, out.writer())
                }
                vm.sourceModified = false
            }.onSuccess {
                withContext(Dispatchers.Main) { toast("Исходный файл сохранён") }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { toast("Ошибка сохранения: ${e.message}") }
            }
        }
    }

    // --- Выход ---

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        when {
            vm.sourceModified -> {
                AlertDialog.Builder(this)
                    .setTitle("Несохранённые изменения")
                    .setMessage("Исходный файл был изменён (вырезаны точки). Сохранить?")
                    .setPositiveButton("Сохранить") { _, _ -> saveSourceFile(); finish() }
                    .setNegativeButton("Не сохранять") { _, _ -> @Suppress("DEPRECATION") super.onBackPressed() }
                    .setNeutralButton("Отмена", null)
                    .show()
            }
            !vm.isNewFileEmpty || !vm.isBufferEmpty -> {
                AlertDialog.Builder(this)
                    .setTitle("Выход")
                    .setMessage("Буфер и новый файл будут потеряны. Выйти?")
                    .setPositiveButton("Выйти") { _, _ -> @Suppress("DEPRECATION") super.onBackPressed() }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause()  { super.onPause();  mapView.onPause()  }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
