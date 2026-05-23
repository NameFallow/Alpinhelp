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
