package com.coordscanner.utils.conversion

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.coordscanner.model.WayPoint
import java.io.ByteArrayOutputStream

class ConversionManager(private val context: Context) {

    // Читает inputDoc, парсит, экспортирует в новый формат — возвращает байты
    fun convert(inputDoc: DocumentFile, outputFormat: FileFormat): ByteArray {
        val name = inputDoc.name
            ?: throw IllegalArgumentException("Нет имени файла")
        val ext = name.substringAfterLast('.', "")
        val inputFormat = FileFormat.fromExtension(ext)
            ?: throw UnsupportedOperationException("Неизвестный формат: $name")

        val points: List<WayPoint> = context.contentResolver
            .openInputStream(inputDoc.uri)
            ?.use { parserFor(inputFormat).parse(it) }
            ?: emptyList()

        return ByteArrayOutputStream().also { buf ->
            exporterFor(outputFormat).export(points, buf)
        }.toByteArray()
    }

    // "точки.gpx" → "точки.kml"
    fun outputName(inputName: String, fmt: FileFormat): String =
        inputName.substringBeforeLast('.') + "." + fmt.extension

    private fun parserFor(fmt: FileFormat): FormatParser = when (fmt) {
        FileFormat.GPX -> GpxConversionParser()
        FileFormat.KML -> KmlConversionParser()
        FileFormat.KMZ -> KmzConversionParser()
        FileFormat.DXF -> DxfConversionParser()
    }

    private fun exporterFor(fmt: FileFormat): FormatExporter = when (fmt) {
        FileFormat.GPX -> GpxConversionExporter()
        FileFormat.KML -> KmlConversionExporter(context)
        FileFormat.KMZ -> KmzConversionExporter(context)
        FileFormat.DXF -> DxfConversionExporter()
    }
}
