package com.coordscanner.viewmodel

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.coordscanner.model.WayPoint
import org.osmdroid.util.BoundingBox

class GpxManagerViewModel : ViewModel() {

    private val _allPoints = MutableLiveData<List<WayPoint>>(emptyList())
    private val _selected  = MutableLiveData<Set<WayPoint>>(emptySet())
    private val _buffer    = MutableLiveData<List<WayPoint>>(emptyList())
    private val _newFile   = MutableLiveData<List<WayPoint>>(emptyList())

    val allPoints: LiveData<List<WayPoint>> = _allPoints
    val selected:  LiveData<Set<WayPoint>>  = _selected
    val buffer:    LiveData<List<WayPoint>> = _buffer
    val newFile:   LiveData<List<WayPoint>> = _newFile

    var sourceUri: Uri? = null
    var sourceModified = false

    fun loadPoints(pts: List<WayPoint>) {
        _allPoints.value = pts
        _selected.value  = emptySet()
        sourceModified   = false
    }

    fun selectInBox(box: BoundingBox, addToExisting: Boolean) {
        val inside = _allPoints.value.orEmpty().filter { box.contains(it.lat, it.lon) }.toSet()
        _selected.value = if (addToExisting) (_selected.value.orEmpty() + inside) else inside
    }

    /**
     * Выделить все точки внутри произвольного полигона (≥3 вершины). Используется
     * ray-casting (метод чётного пересечения) по координатам lat/lon — для сегодня
     * (карты до сотни км) погрешность сферичности пренебрежимая.
     */
    fun selectInPolygon(vertices: List<org.osmdroid.util.GeoPoint>, addToExisting: Boolean) {
        if (vertices.size < 3) return
        val pts = _allPoints.value.orEmpty()
        val inside = pts.filter { pointInPolygon(it.lat, it.lon, vertices) }.toSet()
        _selected.value = if (addToExisting) (_selected.value.orEmpty() + inside) else inside
    }

    private fun pointInPolygon(
        lat: Double,
        lon: Double,
        poly: List<org.osmdroid.util.GeoPoint>,
    ): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].longitude; val yi = poly[i].latitude
            val xj = poly[j].longitude; val yj = poly[j].latitude
            val intersect = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun cut() {
        val sel = _selected.value.orEmpty()
        _buffer.value    = _buffer.value.orEmpty() + sel
        _allPoints.value = _allPoints.value.orEmpty().filter { it !in sel }
        _selected.value  = emptySet()
        sourceModified   = true
    }

    fun copy() {
        _buffer.value   = _buffer.value.orEmpty() + _selected.value.orEmpty()
        _selected.value = emptySet()
    }

    fun deleteSelected() {
        val sel = _selected.value.orEmpty()
        _allPoints.value = _allPoints.value.orEmpty().filter { it !in sel }
        _selected.value  = emptySet()
    }

    fun pasteToNewFile() {
        _newFile.value = _newFile.value.orEmpty() + _buffer.value.orEmpty()
    }

    fun removeFromNewFile(p: WayPoint) {
        _newFile.value = _newFile.value.orEmpty().filter { it != p }
    }

    fun setBuiltInIcon(wp: WayPoint, iconName: String) {
        _newFile.value = _newFile.value.orEmpty().map {
            if (it === wp) it.copy(builtInIconName = iconName) else it
        }
    }

    fun clearNewFile()  { _newFile.value  = emptyList() }
    fun clearBuffer()   { _buffer.value   = emptyList() }

    val isBufferEmpty   get() = _buffer.value.isNullOrEmpty()
    val isNewFileEmpty  get() = _newFile.value.isNullOrEmpty()
}
