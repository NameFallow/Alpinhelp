package com.coordscanner.model

data class WayPoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val color: String = "#FF0000",
    val photoPath: String? = null,
    val description: String? = null
)
