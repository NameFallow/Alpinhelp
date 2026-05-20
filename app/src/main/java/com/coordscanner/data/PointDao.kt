package com.coordscanner.data

import androidx.lifecycle.LiveData
import androidx.room.*
import com.coordscanner.model.Point

@Dao
interface PointDao {
    @Query("SELECT * FROM points ORDER BY id DESC")
    fun getAllPoints(): LiveData<List<Point>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: Point): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<Point>)

    @Update
    suspend fun update(point: Point)

    @Delete
    suspend fun delete(point: Point)

    @Query("SELECT * FROM points WHERE id = :id")
    suspend fun getById(id: Long): Point?
}
