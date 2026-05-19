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
    // "scan" = added by real-time camera, "manual" = typed by user
    @ColumnInfo(defaultValue = "manual") val source: String = "manual"
)
