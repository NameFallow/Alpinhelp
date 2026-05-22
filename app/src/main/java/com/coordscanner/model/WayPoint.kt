package com.coordscanner.model

data class WayPoint(
    val lat: Double,
    val lon: Double,
    val name: String,
    val color: String = "#FF0000"
)
