package com.coordscanner.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.coordscanner.data.PointDatabase
import com.coordscanner.data.PointRepository
import com.coordscanner.model.Point
import kotlinx.coroutines.launch

class PointViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PointRepository
    val allPoints: LiveData<List<Point>>

    init {
        val dao = PointDatabase.getDatabase(application).pointDao()
        repository = PointRepository(dao)
        allPoints = repository.allPoints
    }

    fun insert(point: Point) = viewModelScope.launch {
        repository.insert(point)
    }

    fun insertAll(points: List<Point>) = viewModelScope.launch {
        repository.insertAll(points)
    }

    fun update(point: Point) = viewModelScope.launch {
        repository.update(point)
    }

    fun delete(point: Point) = viewModelScope.launch {
        repository.delete(point)
    }

    fun deleteAll() = viewModelScope.launch {
        repository.deleteAll()
    }
}
