package com.coordscanner

import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.coordscanner.databinding.ActivityKmzMapBinding
import com.coordscanner.model.WayPoint
import com.coordscanner.utils.GpxParser
import com.coordscanner.utils.KmzParser
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
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.io.File

class KmzMapActivity : AppCompatActivity() {

    private lateinit var b: ActivityKmzMapBinding
    private lateinit var mapView: MapView
    private var markersOverlay = FolderOverlay()
    private var loadedPoints: List<WayPoint> = emptyList()

    // Иконки маркеров создаются один раз и переиспользуются на всех точках —
    // иначе при 50+ маркерах в LRU оседают 50+ Bitmap-ов, и карта рано или поздно
    // ловит OutOfMemoryError на больших KMZ.
    private val defaultMarkerIcon: Drawable by lazy { makeDefaultIcon() }
    private val cameraMarkerIcon: Drawable by lazy { makeCameraIcon() }

    private val openKmzLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadKmzFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        b = ActivityKmzMapBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupMap()
        setupButtons()

        // Поддержка открытия через Intent (напр. из GpxManagerActivity)
        intent.data?.let { loadKmzFile(it) }
    }

    private fun setupMap() {
        mapView = b.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(48.0, 37.8))
        mapView.overlays.add(markersOverlay)
    }

    private fun setupButtons() {
        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        b.btnOpenKmz.setOnClickListener {
            openKmzLauncher.launch(arrayOf("*/*"))
        }

        b.btnConvertGpx.setOnClickListener {
            if (loadedPoints.isEmpty()) { toast("Нет точек для конвертации"); return@setOnClickListener }
            convertAndShareGpx()
        }
    }

    private fun loadKmzFile(uri: Uri) {
        lifecycleScope.launch {
            toast("Загрузка KMZ...")
            val points = withContext(Dispatchers.IO) {
                runCatching { KmzParser.parse(this@KmzMapActivity, uri) }
                    .getOrElse { e ->
                        withContext(Dispatchers.Main) { toast("Ошибка: ${e.message}") }
                        emptyList()
                    }
            }
            loadedPoints = points
            showMarkers(points)
            b.tvPointCount.text = "Точек: ${points.size}"
            if (points.isNotEmpty()) {
                val bbox = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
                mapView.zoomToBoundingBox(bbox.increaseByScale(1.3f), true)
                toast("Загружено: ${points.size} точек")
            }
        }
    }

    private fun showMarkers(points: List<WayPoint>) {
        mapView.overlays.remove(markersOverlay)
        markersOverlay = FolderOverlay()

        // Закрываем все открытые InfoWindow перед перестройкой маркеров
        InfoWindow.closeAllInfoWindowsOn(mapView)

        points.forEach { pt ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(pt.lat, pt.lon)
                title = pt.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = if (pt.photoPath != null) cameraMarkerIcon else defaultMarkerIcon
                infoWindow = CustomInfoWindow(mapView, pt)
            }
            markersOverlay.add(marker)
        }

        mapView.overlays.add(0, markersOverlay)
        mapView.invalidate()
    }

    private fun convertAndShareGpx() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(cacheDir, "gpx").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "kmz_export_${System.currentTimeMillis()}.gpx")
                java.io.OutputStreamWriter(java.io.FileOutputStream(file), Charsets.UTF_8)
                    .use { GpxParser.write(loadedPoints, it) }
                file
            }.onSuccess { file ->
                withContext(Dispatchers.Main) { shareGpxFile(file) }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { toast("Ошибка экспорта: ${e.message}") }
            }
        }
    }

    private fun shareGpxFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent.createChooser(intent, "Открыть в...").also {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    // Красный кружок — обычная точка без фото
    private fun makeDefaultIcon(): Drawable {
        val dp = resources.displayMetrics.density
        val r = (dp * 10).toInt()
        val sz = r * 2
        val bmp = try {
            Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            return android.graphics.drawable.ColorDrawable(Color.RED)
        }
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.RED
        canvas.drawCircle(r.toFloat(), r.toFloat(), r - 2f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(r.toFloat(), r.toFloat(), r - 2f, paint)
        return BitmapDrawable(resources, bmp)
    }

    // Синий кружок с иконкой фотоаппарата — точка с фото
    private fun makeCameraIcon(): Drawable {
        val dp = resources.displayMetrics.density
        val sz = (dp * 28).toInt()
        val bmp = try {
            Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            return android.graphics.drawable.ColorDrawable(Color.parseColor("#1565C0"))
        }
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Фон
        paint.color = Color.parseColor("#1565C0")
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f - 1, paint)

        // Корпус фотоаппарата
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        val pad = sz * 0.22f
        canvas.drawRoundRect(
            RectF(pad, sz * 0.32f, sz - pad, sz * 0.78f),
            sz * 0.1f, sz * 0.1f, paint
        )

        // Объектив (дырка в корпусе)
        paint.color = Color.parseColor("#1565C0")
        canvas.drawCircle(sz / 2f, sz * 0.55f, sz * 0.15f, paint)

        return BitmapDrawable(resources, bmp)
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause()  { super.onPause();  mapView.onPause()  }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
