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
    private val ICON_EXTENSIONS  = setOf("svg", "png", "gif", "webp", "bmp")

    // Пытается сопоставить иконку из KMZ с встроенной иконкой из assets:
    // 1. По точному имени файла, 2. По нечёткому совпадению
    fun matchBuiltInIcon(iconBytes: ByteArray, iconFileName: String, iconManager: IconManager): String? {
        val byName = iconManager.findIcon(iconFileName)
        if (byName != null) return byName

        val nameNoExt = iconFileName.substringBeforeLast('.')
        val fuzzy = iconManager.findIconFuzzy(nameNoExt)
        if (fuzzy != null) return fuzzy

        return null
    }

    fun parse(context: Context, uri: Uri): List<WayPoint> {
        val photoDir = File(context.cacheDir, "kmz_photos")
        photoDir.deleteRecursively()
        photoDir.mkdirs()

        val photoMap = mutableMapOf<String, File>()
        // Байты иконок: shortName → bytes (для matchBuiltInIcon)
        val iconBytesMap = mutableMapOf<String, ByteArray>()
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

                                photoMap[entryName] = outFile
                                photoMap[entryName.lowercase()] = outFile
                                photoMap[shortName] = outFile
                                photoMap[shortName.lowercase()] = outFile

                                try {
                                    val decoded = URLDecoder.decode(entryName, "UTF-8")
                                    photoMap[decoded] = outFile
                                    photoMap[decoded.substringAfterLast('/')] = outFile
                                } catch (_: Exception) {}
                            }
                            ext in ICON_EXTENSIONS -> {
                                // SVG и другие иконки — не фото, но нужны для иконок точек
                                val shortName = entryName.substringAfterLast('/')
                                val outFile = File(photoDir, shortName)
                                outFile.writeBytes(bytes)
                                photoMap[entryName] = outFile
                                photoMap[entryName.lowercase()] = outFile
                                photoMap[shortName] = outFile
                                photoMap[shortName.lowercase()] = outFile
                                iconBytesMap[shortName] = bytes
                                try {
                                    val decoded = URLDecoder.decode(entryName, "UTF-8")
                                    photoMap[decoded] = outFile
                                    val decodedShort = decoded.substringAfterLast('/')
                                    photoMap[decodedShort] = outFile
                                    iconBytesMap[decodedShort] = bytes
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
        val iconManager = IconManager(context)

        val result = points.map { point ->
            val rawRef = extractPhotoRef(point.description ?: "")
            val photoFile = if (rawRef != null) {
                val decoded = try { URLDecoder.decode(rawRef, "UTF-8") } catch (_: Exception) { rawRef }
                findPhoto(decoded, photoMap, photoDir)
            } else null

            val iconFile = point.symbol
                ?.takeIf { !it.startsWith("http", ignoreCase = true) }
                ?.let { sym ->
                    val decoded = try { URLDecoder.decode(sym, "UTF-8") } catch (_: Exception) { sym }
                    findPhoto(decoded, photoMap, photoDir)
                }

            // Пытаемся сопоставить иконку с встроенной
            val builtInIconName = iconFile?.let { f ->
                val bytes = iconBytesMap[f.name] ?: f.readBytes()
                matchBuiltInIcon(bytes, f.name, iconManager)
            }

            Log.d(TAG, "Точка: ${point.name} | ref=$rawRef | icon=${iconFile?.name} | builtIn=$builtInIconName")

            point.copy(
                photoPath         = photoFile?.absolutePath ?: point.photoPath,
                photoOriginalName = photoFile?.name ?: point.photoOriginalName,
                iconFilePath      = if (builtInIconName == null) iconFile?.absolutePath else null,
                builtInIconName   = builtInIconName
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
