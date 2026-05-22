package com.coordscanner.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.coordscanner.model.WayPoint
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipInputStream

object KmzParser {

    private const val TAG = "KmzParser"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun parse(context: Context, uri: Uri): List<WayPoint> {
        val photoDir = File(context.cacheDir, "kmz_photos")
        photoDir.deleteRecursively()
        photoDir.mkdirs()

        val photoMap = mutableMapOf<String, File>()
        var kmlBytes: ByteArray? = null

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryName = entry.name
                        val ext = entryName.substringAfterLast('.').lowercase()
                        val bytes = zip.readBytes()

                        when {
                            ext == "kml" && kmlBytes == null -> kmlBytes = bytes
                            ext in IMAGE_EXTENSIONS -> {
                                val shortName = entryName.substringAfterLast('/')
                                val outFile = File(photoDir, shortName)
                                outFile.writeBytes(bytes)

                                // Регистрируем по всем возможным ключам
                                photoMap[entryName] = outFile
                                photoMap[entryName.lowercase()] = outFile
                                photoMap[shortName] = outFile
                                photoMap[shortName.lowercase()] = outFile

                                // URL-декодированные варианты (кириллица, пробелы)
                                try {
                                    val decoded = URLDecoder.decode(entryName, "UTF-8")
                                    photoMap[decoded] = outFile
                                    photoMap[decoded.substringAfterLast('/')] = outFile
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        Log.d(TAG, "Файлов в photoMap: ${photoMap.size}, в папке: ${photoDir.listFiles()?.size}")

        val points = kmlBytes?.let { KmlParser.parse(it.inputStream()) } ?: emptyList()

        val result = points.map { point ->
            val rawRef = extractPhotoRef(point.description ?: "")
            val photoFile = if (rawRef != null) {
                val decoded = try { URLDecoder.decode(rawRef, "UTF-8") } catch (_: Exception) { rawRef }
                findPhoto(decoded, photoMap, photoDir)
            } else null

            // Resolve icon file for KMZ-relative symbol hrefs (e.g. "files/aq_wpt_circle.png")
            val iconFile = point.symbol
                ?.takeIf { !it.startsWith("http", ignoreCase = true) }
                ?.let { sym ->
                    val decoded = try { URLDecoder.decode(sym, "UTF-8") } catch (_: Exception) { sym }
                    findPhoto(decoded, photoMap, photoDir)
                }

            Log.d(TAG, "Точка: ${point.name} | ref=$rawRef | file=${photoFile?.name} | icon=${iconFile?.name}")

            point.copy(
                photoPath      = photoFile?.absolutePath ?: point.photoPath,
                photoOriginalName = photoFile?.name ?: point.photoOriginalName,
                iconFilePath   = iconFile?.absolutePath
            )
        }

        val withPhoto = result.count { it.photoPath != null }
        Log.d(TAG, "Итого: ${result.size} точек, с фото: $withPhoto, без фото: ${result.size - withPhoto}")

        return result
    }

    private fun findPhoto(name: String, photoMap: Map<String, File>, photoDir: File): File? {
        val short = name.substringAfterLast('/')
        return photoMap[name]
            ?: photoMap[name.lowercase()]
            ?: photoMap[short]
            ?: photoMap[short.lowercase()]
            // Последний шанс — перебрать все файлы в папке
            ?: photoDir.listFiles()?.firstOrNull { it.name.equals(short, ignoreCase = true) }
    }

    private fun extractPhotoRef(desc: String): String? {
        if (desc.isBlank()) return null
        val clean = desc.replace("<![CDATA[", "").replace("]]>", "")
        val patterns = listOf(
            Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""src=([^\s>]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(clean)) {
                val src = match.groupValues[1].trim()
                val ext = src.substringAfterLast('.').lowercase()
                if (ext in IMAGE_EXTENSIONS) return src
            }
        }
        return null
    }
}
