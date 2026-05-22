package com.coordscanner.model

data class WayPoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val color: String = "#FF0000",
    val photoPath: String? = null,         // абсолютный путь к кэшированному фото
    val photoOriginalName: String? = null, // оригинальное имя файла из ZIP (напр. "photo.jpg")
    val description: String? = null
)
