package com.coordscanner.utils

import android.content.Context
import android.net.Uri
import com.coordscanner.model.WayPoint
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object KmzParser {

    fun parse(context: Context, uri: Uri): List<WayPoint> {
        val photoDir = File(context.cacheDir, "kmz_photos").apply { mkdirs() }
        photoDir.listFiles()?.forEach { it.delete() }

        // Имя-в-zip → абсолютный путь к кэшированному файлу
        val photoMap = mutableMapOf<String, String>()
        var kmlBytes: ByteArray? = null

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    when {
                        entryName.equals("doc.kml", ignoreCase = true) ->
                            kmlBytes = zip.readBytes()
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

        return kmlBytes?.let { bytes ->
            // KmlParser даёт цвета, имена, координаты и description
            KmlParser.parse(bytes.inputStream()).map { point ->
                val photoPath = extractPhotoPath(point.description ?: "", photoMap)
                if (photoPath != null) point.copy(photoPath = photoPath) else point
            }
        } ?: emptyList()
    }

    // Ищем <img src="files/photo.jpg"> в description (может быть в CDATA)
    private fun extractPhotoPath(desc: String, photoMap: Map<String, String>): String? {
        if (desc.isBlank()) return null
        val match = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(desc) ?: return null
        val src = match.groupValues[1]
        return photoMap[src] ?: photoMap[src.substringAfterLast('/')]
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp")
    }
}
