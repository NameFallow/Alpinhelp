package com.coordscanner.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.coordscanner.model.WayPoint

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
                KmzParser.parse(context, uri)
            fileName.endsWith(".dxf", ignoreCase = true) ->
                context.contentResolver.openInputStream(uri)?.use { DxfParser.parse(it) }
            else -> throw UnsupportedOperationException("Неизвестный формат: $fileName")
        } ?: emptyList()
        return ImportResult(points, fileName)
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
