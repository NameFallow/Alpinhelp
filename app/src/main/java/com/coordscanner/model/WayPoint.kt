package com.coordscanner.model

data class WayPoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val color: String = "#FF0000",
    val photoPath: String? = null,
    val photoOriginalName: String? = null,
    val description: String? = null,
    val symbol: String? = null,           // <sym> для GPX или <Icon><href> для KML/KMZ
    val iconFilePath: String? = null,     // абсолютный путь к извлечённому файлу иконки из KMZ
    val builtInIconName: String? = null   // имя встроенной иконки из assets/alpinequest_icons/
)
