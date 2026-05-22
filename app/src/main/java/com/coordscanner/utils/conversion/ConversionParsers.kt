package com.coordscanner.utils.conversion

import com.coordscanner.model.WayPoint
import com.coordscanner.utils.DxfParser
import com.coordscanner.utils.GpxParser
import com.coordscanner.utils.KmlParser
import java.io.InputStream
import java.util.zip.ZipInputStream

class GpxConversionParser : FormatParser {
    override fun parse(stream: InputStream): List<WayPoint> = GpxParser.parse(stream)
}

class KmlConversionParser : FormatParser {
    override fun parse(stream: InputStream): List<WayPoint> = KmlParser.parse(stream)
}

// Распаковывает ZIP, извлекает doc.kml и парсит через KmlParser
class KmzConversionParser : FormatParser {
    override fun parse(stream: InputStream): List<WayPoint> {
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals("doc.kml", ignoreCase = true)) {
                    return KmlParser.parse(zip.readBytes().inputStream())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return emptyList()
    }
}

class DxfConversionParser : FormatParser {
    override fun parse(stream: InputStream): List<WayPoint> = DxfParser.parse(stream)
}
