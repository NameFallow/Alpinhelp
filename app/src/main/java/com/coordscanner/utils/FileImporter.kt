package com.coordscanner.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.coordscanner.model.WayPoint
import java.util.zip.ZipInputStream

class FileImporter(private val context: Context) {

    data class ImportResult(val points: List<WayPoint>, val fileName: String)

    fun importFile(uri: Uri): ImportResult {
        val fileName = resolveFileName(uri) ?: ""
        val points = when {
            fileName.endsWith(".gpx", ignoreCase = true) ->
                context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
            fileName.endsWith(".kml", ignoreCase = true) ->
                context.contentResolver.openInputStream(uri)?.use { KmlParser.parse(it) }
            fileName.endsWith(".kmz", ignoreCase = true) ->
                parseKmz(uri)
            fileName.endsWith(".dxf", ignoreCase = true) ->
                context.contentResolver.openInputStream(uri)?.use { DxfParser.parse(it) }
            else -> throw UnsupportedOperationException("Неизвестный формат: $fileName")
        } ?: emptyList()
        return ImportResult(points, fileName)
    }

    // KMZ: извлекаем doc.kml из ZIP и парсим через KmlParser
    private fun parseKmz(uri: Uri): List<WayPoint> {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.equals("doc.kml", ignoreCase = true)) {
                        return KmlParser.parse(zip.readBytes().inputStream())
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return emptyList()
    }

    fun resolveFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}
