package com.coordscanner.utils

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class KmzPoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val photoPath: String?  // абсолютный путь к кэшированному фото, null если нет
)

object KmzParser {

    fun parse(context: Context, uri: Uri): List<KmzPoint> {
        val photoDir = File(context.cacheDir, "kmz_photos").apply { mkdirs() }
        photoDir.listFiles()?.forEach { it.delete() }

        // Имя-в-zip → локальный путь
        val photoMap = mutableMapOf<String, String>()
        var kmlBytes: ByteArray? = null

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    when {
                        entryName.equals("doc.kml", ignoreCase = true) -> {
                            kmlBytes = zip.readBytes()
                        }
                        isImageFile(entryName) -> {
                            val shortName = entryName.substringAfterLast('/')
                            val outFile = File(photoDir, shortName)
                            FileOutputStream(outFile).use { zip.copyTo(it) }
                            // Регистрируем и по полному пути ("files/photo.jpg"), и по короткому
                            photoMap[entryName] = outFile.absolutePath
                            photoMap[shortName] = outFile.absolutePath
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        return kmlBytes?.let { parseKml(it, photoMap) } ?: emptyList()
    }

    private fun parseKml(kmlBytes: ByteArray, photoMap: Map<String, String>): List<KmzPoint> {
        val points = mutableListOf<KmzPoint>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(kmlBytes.inputStream(), "UTF-8")
        }

        var inPlacemark = false
        var inPoint = false      // внутри <Point> — только тогда берём координаты
        var inName = false
        var inCoords = false
        var inDesc = false
        var name = ""
        var coordsText = ""
        var description = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Placemark"   -> { inPlacemark = true; name = ""; coordsText = ""; description = "" }
                    "Point"       -> if (inPlacemark) inPoint = true
                    "name"        -> if (inPlacemark) inName  = true
                    "coordinates" -> if (inPlacemark && inPoint) inCoords = true
                    "description" -> if (inPlacemark) inDesc  = true
                }
                XmlPullParser.TEXT -> when {
                    inName   -> { name        = parser.text.trim(); inName   = false }
                    inCoords -> { coordsText  = parser.text.trim(); inCoords = false }
                    inDesc   -> { description = parser.text.trim(); inDesc   = false }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Point"     -> inPoint = false
                    "Placemark" -> if (inPlacemark) {
                        val (lat, lon) = parseCoords(coordsText)
                        if (lat != 0.0 || lon != 0.0) {
                            val photo = extractPhotoPath(description, photoMap)
                            points.add(KmzPoint(name.ifBlank { "Точка ${points.size + 1}" }, lat, lon, photo))
                        }
                        inPlacemark = false
                    }
                }
            }
            event = parser.next()
        }
        return points
    }

    // KML формат: "lon,lat,alt" — ВНИМАНИЕ: сначала LON, потом LAT
    private fun parseCoords(text: String): Pair<Double, Double> {
        val parts = text.trim().split(Regex("[,\\s]+"))
        if (parts.size < 2) return 0.0 to 0.0
        val lon = parts[0].toDoubleOrNull() ?: return 0.0 to 0.0
        val lat = parts[1].toDoubleOrNull() ?: return 0.0 to 0.0
        return lat to lon
    }

    // Ищем <img src="files/photo.jpg"> в description (может быть в CDATA)
    private fun extractPhotoPath(desc: String, photoMap: Map<String, String>): String? {
        if (desc.isBlank()) return null
        val match = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(desc)
            ?: return null
        val src = match.groupValues[1]
        return photoMap[src] ?: photoMap[src.substringAfterLast('/')]
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp")
    }
}
