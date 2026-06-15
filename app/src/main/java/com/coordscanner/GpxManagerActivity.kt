package com.coordscanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.DocumentsContract
import android.util.LruCache
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coordscanner.databinding.ActivityGpxManagerBinding
import com.coordscanner.model.WayPoint
import com.coordscanner.utils.AllFilesAccessHelper
import com.coordscanner.utils.FileImporter
import com.coordscanner.utils.FilePickerHelper
import com.coordscanner.utils.GpxParser
import com.coordscanner.viewmodel.GpxManagerViewModel
import org.osmdroid.tileprovider.tilesource.ITileSource
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

class GpxManagerActivity : AppCompatActivity(), LayerSwitcherBottomSheet.OnLayerSelectedListener {

    private lateinit var b: ActivityGpxManagerBinding
    val vm: GpxManagerViewModel by viewModels()

    private lateinit var mapView: MapView
    private var markersOverlay = FolderOverlay()
    private var polygonOverlay: MapPolygonOverlay? = null
    private val iconCache = LruCache<String, Drawable>(32)

    private var isSelectMode = false
    private var isAddMode = false

    // Расширенный SAF-picker: вызывает системный DocumentsUI с показом всех источников
    // (внутренняя память, SD, OTG-флешка, Downloads, Google Drive). Если переданa initialUri
    // (через input) — picker откроется сразу в этой папке. Полезно чтобы каждый раз
    // открываться в папке AlpineQuest которую юзер один раз указал.
    // Mime-фильтр включает gpx, kml, kmz и xml, плюс wildcard как запасной.
    private class OpenAdvanced : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/gpx+xml",
                "application/vnd.google-earth.kml+xml",
                "application/vnd.google-earth.kmz",
                "application/xml",
                "text/xml",
                "application/octet-stream",
                "*/*",
            ))
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initial = input ?: DocumentsContract.buildRootUri(
                    "com.android.externalstorage.documents", "primary"
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    private val openFileLauncher = registerForActivityResult(OpenAdvanced()) { uri ->
        uri?.let { dispatchFile(it) }
    }

    /**
     * Открыть встроенный файловый браузер (видит ВСЁ что видит USB, включая
     * Android/data/AlpineQuest/). Если нет разрешения MANAGE_EXTERNAL_STORAGE —
     * сначала предложить включить в настройках.
     */
    private fun openFileSmart() {
        AllFilesAccessHelper.ensureAccess(this) {
            FileBrowserBottomSheet.show(supportFragmentManager) { file ->
                dispatchFile(Uri.fromFile(file))
            }
        }
    }

    /** Запасной flow: системный SAF picker (на случай если юзер не дал разрешение). */
    private fun openFileFallback() {
        val saved = filePickerHelper.getSavedFolder()
        openFileLauncher.launch(saved?.uri)
    }

    private lateinit var filePickerHelper: FilePickerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        b = ActivityGpxManagerBinding.inflate(layoutInflater)
        setContentView(b.root)

        filePickerHelper = FilePickerHelper(this) { folder ->
            toast("Папка «${folder.name}» запомнена — открываю файлы")
            // Сразу запустить обычный picker уже в этой папке.
            openFileLauncher.launch(folder.uri)
        }

        setupMap()
        setupButtons()
        observeViewModel()
    }

    // --- Карта ---

    private fun setupMap() {
        mapView = b.mapView
        mapView.setTileSource(LayerSwitcherBottomSheet.GOOGLE_HYBRID)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(48.0, 37.8))
        mapView.overlays.add(markersOverlay)
    }

    // --- Кнопки ---

    private fun setupButtons() {
        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        b.btnOpenFile.setOnClickListener { openFileSmart() }
        // Long-press — запасной системный SAF picker (если по какой-то причине
        // не дали MANAGE_EXTERNAL_STORAGE и хочется через стандартный обзор).
        b.btnOpenFile.setOnLongClickListener { openFileFallback(); true }

        // Переключатели слоёв карты
        selectLayerChip(R.id.btnChipHybrid)
        b.btnChipOsm.setOnClickListener {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.invalidate()
            selectLayerChip(R.id.btnChipOsm)
        }
        b.btnChipSat.setOnClickListener {
            mapView.setTileSource(LayerSwitcherBottomSheet.GOOGLE_SATELLITE)
            mapView.invalidate()
            selectLayerChip(R.id.btnChipSat)
        }
        b.btnChipHybrid.setOnClickListener {
            mapView.setTileSource(LayerSwitcherBottomSheet.GOOGLE_HYBRID)
            mapView.invalidate()
            selectLayerChip(R.id.btnChipHybrid)
        }

        b.btnSelectArea.setOnClickListener {
            if (isSelectMode) cancelSelectionMode() else enableSelectionMode(addMode = false)
        }
        b.btnAddArea.setOnClickListener { enableSelectionMode(addMode = true) }
        b.btnApplyPolygon.setOnClickListener { applyPolygon() }
        b.btnUndoVertex.setOnClickListener {
            polygonOverlay?.undoLastVertex()
            mapView.invalidate()
            updatePolygonToolbar()
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

    private fun selectLayerChip(chipId: Int) {
        val primary = resources.getColor(R.color.primary, theme)
        val transparent = Color.TRANSPARENT
        val secondary = resources.getColor(R.color.text_secondary, theme)
        listOf(
            b.btnChipOsm  to (R.id.btnChipOsm  == chipId),
            b.btnChipSat  to (R.id.btnChipSat  == chipId),
            b.btnChipHybrid to (R.id.btnChipHybrid == chipId)
        ).forEach { (btn, selected) ->
            btn.backgroundTintList = ColorStateList.valueOf(if (selected) primary else transparent)
            btn.setTextColor(if (selected) Color.WHITE else secondary)
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

    // --- Режим выделения (полигональное лассо) ---

    private fun enableSelectionMode(addMode: Boolean) {
        isSelectMode = true
        isAddMode    = addMode
        // multitouch оставляем включённым — pan/zoom карты работает; overlay перехватывает
        // только короткие тапы без сдвига и ставит вершину.
        mapView.setMultiTouchControls(true)

        if (polygonOverlay == null) {
            polygonOverlay = MapPolygonOverlay(onVertexAdded = { updatePolygonToolbar() })
        }
        polygonOverlay!!.clear()
        if (polygonOverlay !in mapView.overlays) {
            mapView.overlays.add(polygonOverlay)
        }
        mapView.invalidate()

        b.btnSelectArea.text = "✕ Отмена"
        b.btnAddArea.visibility = View.GONE
        updatePolygonToolbar()
        toast("Тапай по карте чтобы поставить вершины (от 3-х). Внизу — «Применить».")
    }

    private fun cancelSelectionMode() {
        isSelectMode = false
        isAddMode    = false
        polygonOverlay?.let { mapView.overlays.remove(it) }
        mapView.invalidate()

        val hasSel = vm.selected.value.orEmpty().isNotEmpty()
        b.btnSelectArea.text = if (hasSel) "Выделить ещё" else "Выделить"
        b.btnAddArea.visibility = if (hasSel) View.VISIBLE else View.GONE
        updatePolygonToolbar()
    }

    private fun applyPolygon() {
        val verts = polygonOverlay?.getVertices().orEmpty()
        if (verts.size < 3) {
            toast("Нужно минимум 3 вершины")
            return
        }
        vm.selectInPolygon(verts, addToExisting = isAddMode)
        cancelSelectionMode()

        val count = vm.selected.value?.size ?: 0
        if (count > 0 && supportFragmentManager.findFragmentByTag("actions") == null) {
            PointsBottomSheet().show(supportFragmentManager, "actions")
        }
    }

    /** Показать/спрятать FAB «Применить» и «Откатить» по числу вершин и режиму. */
    private fun updatePolygonToolbar() {
        val count = polygonOverlay?.vertexCount() ?: 0
        val showApply = isSelectMode && count >= 3
        val showUndo  = isSelectMode && count >= 1
        b.btnApplyPolygon.visibility = if (showApply) View.VISIBLE else View.GONE
        b.btnUndoVertex.visibility   = if (showUndo)  View.VISIBLE else View.GONE
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
        val selIdx = mapView.overlays.indexOf(polygonOverlay)
        if (selIdx >= 0) mapView.overlays.add(selIdx, markersOverlay)
        else mapView.overlays.add(0, markersOverlay)
        mapView.invalidate()
    }

    private fun getMarkerIcon(colorKey: String): Drawable {
        iconCache[colorKey]?.let { return it }
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
        return BitmapDrawable(resources, bmp).also { iconCache.put(colorKey, it) }
    }

    // --- Загрузка файла любого формата через FileImporter ---

    private fun dispatchFile(uri: Uri) = loadFile(uri)

    private fun loadFile(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FileImporter(this@GpxManagerActivity).importFile(uri) }
                    .getOrElse { e ->
                        withContext(Dispatchers.Main) { toast("Ошибка: ${e.message}") }
                        null
                    }
            } ?: return@launch

            vm.loadPoints(result.points)
            // sourceUri только для GPX — только его можно перезаписать в том же формате
            vm.sourceUri = if (result.fileName.endsWith(".gpx", ignoreCase = true)) uri else null

            if (result.points.isNotEmpty()) {
                val bbox = BoundingBox.fromGeoPoints(result.points.map { GeoPoint(it.lat, it.lon) })
                mapView.zoomToBoundingBox(bbox.increaseByScale(1.3f), true)
                toast("Загружено: ${result.points.size} точек из ${result.fileName}")
            } else {
                toast("Точки не найдены: ${result.fileName}")
            }
        }
    }

    override fun onLayerSelected(source: ITileSource) {
        mapView.setTileSource(source)
        mapView.invalidate()
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
    override fun onDestroy() { super.onDestroy(); java.io.File(cacheDir, "kmz_photos").deleteRecursively() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
