package com.coordscanner.data

import androidx.lifecycle.LiveData
import com.coordscanner.model.Point

class PointRepository(private val dao: PointDao) {
    val allPoints: LiveData<List<Point>> = dao.getAllPoints()

    suspend fun insert(point: Point) = dao.insert(point)
    suspend fun update(point: Point) = dao.update(point)
    suspend fun delete(point: Point) = dao.delete(point)
    suspend fun getById(id: Long) = dao.getById(id)
}
