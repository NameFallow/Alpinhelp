package com.coordscanner.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "points")
data class Point(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val xSk42: Double,
    val ySk42: Double,
    val zone: Int,
    val latWgs84: Double,
    val lonWgs84: Double,
    @ColumnInfo(defaultValue = "manual") val source: String = "manual",
    @ColumnInfo(defaultValue = "#0055FF") val color: String = "#0055FF",
    val timestamp: Long = System.currentTimeMillis()
)
