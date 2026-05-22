package com.coordscanner.utils.conversion

enum class FileFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    val description: String
) {
    GPX("gpx", "application/gpx+xml",                     "GPX", "для AlpineQuest"),
    KML("kml", "application/vnd.google-earth.kml+xml",     "KML", "для Google Earth"),
    KMZ("kmz", "application/vnd.google-earth.kmz",         "KMZ", "KML + фото"),
    DXF("dxf", "application/dxf",                          "DXF", "для AutoCAD");

    companion object {
        fun fromExtension(ext: String): FileFormat? =
            values().firstOrNull { it.extension.equals(ext, ignoreCase = true) }
    }
}
