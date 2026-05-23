package com.coordscanner.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.coordscanner.model.WayPoint
import com.coordscanner.utils.conversion.DxfConversionExporter
import com.coordscanner.utils.conversion.FormatExporter
import com.coordscanner.utils.conversion.GpxConversionExporter
import com.coordscanner.utils.conversion.KmlConversionExporter
import com.coordscanner.utils.conversion.KmzConversionExporter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// KMZ первый — он приоритетный и выбирается по умолчанию
enum class SaveFormat(val ext: String, val label: String, val mimeType: String) {
    KMZ(".kmz", "KMZ ⭐", "application/vnd.google-earth.kmz"),
    GPX(".gpx", "GPX",    "application/gpx+xml"),
    KML(".kml", "KML",    "application/vnd.google-earth.kml+xml"),
    DXF(".dxf", "DXF",    "application/octet-stream")
}

data class SaveResult(
    val format: SaveFormat,
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long
)

object FileSaveHelper {

    fun defaultFileName(): String =
        "Метки_${SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date())}"

    fun sanitizeFileName(input: String): String =
        input.trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "_")
            .take(50)
            .ifBlank { defaultFileName() }

    // Сохранить во всех форматах — возвращает список результатов (без исключений)
    fun saveAll(context: Context, points: List<WayPoint>, baseName: String): List<SaveResult> =
        SaveFormat.values().mapNotNull { format ->
            runCatching {
                saveToDownloads(context, points, "$baseName${format.ext}", format)
            }.getOrNull()
        }

    // Сохранить один файл в Downloads/CoordScanner/
    fun saveToDownloads(
        context: Context,
        points: List<WayPoint>,
        fileName: String,
        format: SaveFormat
    ): SaveResult {
        val exporter = getExporter(format)
        val uri: Uri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — MediaStore Downloads, не нужно разрешение WRITE_EXTERNAL_STORAGE
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/CoordScanner")
            }
            uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore: не удалось создать $fileName")

            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw Exception("MediaStore: нет доступа к потоку для $fileName")
            stream.use {
                BufferedOutputStream(stream).use { bos ->
                    exporter.export(points, bos)
                    bos.flush()
                }
            }
        } else {
            // Android 8-9 — прямая запись через File (нужно WRITE_EXTERNAL_STORAGE ≤ API 28)
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "CoordScanner"
            ).also { it.mkdirs() }
            val file = File(dir, fileName)

            FileOutputStream(file).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    exporter.export(points, bos)
                    bos.flush()
                }
            }
            if (file.length() == 0L) throw Exception("Файл пустой: $fileName")

            uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
        }

        val size = getUriSize(context, uri)
        return SaveResult(format, uri, fileName, size)
    }

    // Поделиться одним KMZ-файлом
    fun shareKmz(uri: Uri, context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = SaveFormat.KMZ.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Поделиться KMZ")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // Поделиться всеми форматами сразу
    fun shareAll(uris: List<Uri>, context: Context) {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Поделиться файлами")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun getExporter(format: SaveFormat): FormatExporter = when (format) {
        SaveFormat.KMZ -> KmzConversionExporter()
        SaveFormat.GPX -> GpxConversionExporter()
        SaveFormat.KML -> KmlConversionExporter()
        SaveFormat.DXF -> DxfConversionExporter()
    }

    fun getUriSize(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L
}
