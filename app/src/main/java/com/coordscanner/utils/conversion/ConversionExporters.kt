package com.coordscanner.utils.conversion

import com.coordscanner.model.WayPoint
import com.coordscanner.utils.GpxParser
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GpxConversionExporter : FormatExporter {
    override fun export(points: List<WayPoint>, out: OutputStream) {
        // use{} гарантирует flush+close даже при исключении
        OutputStreamWriter(out, Charsets.UTF_8).use { w ->
            GpxParser.write(points, w)
            w.flush()
        }
    }
}

class KmlConversionExporter : FormatExporter {
    override fun export(points: List<WayPoint>, out: OutputStream) {
        OutputStreamWriter(out, Charsets.UTF_8).use { w ->
            val colors = points.map { it.color }.distinct()

            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")

            // Style-блоки для каждого уникального цвета
            for (hex in colors) {
                w.write("  <Style id=\"${styleId(hex)}\">\n")
                w.write("    <IconStyle><color>${hexToKmlColor(hex)}</color>\n")
                w.write("      <Icon><href>http://maps.google.com/mapfiles/kml/paddle/wht-circle.png</href></Icon>\n")
                w.write("    </IconStyle>\n  </Style>\n")
            }

            // Placemark-и: ВНИМАНИЕ — в KML координаты lon,lat,alt (не lat,lon!)
            for (p in points) {
                val lat = String.format(Locale.US, "%.8f", p.lat)
                val lon = String.format(Locale.US, "%.8f", p.lon)
                w.write("  <Placemark>\n")
                w.write("    <name>${p.name.xmlEscape()}</name>\n")
                if (!p.description.isNullOrBlank()) {
                    w.write("    <description><![CDATA[${p.description}]]></description>\n")
                }
                w.write("    <styleUrl>#${styleId(p.color)}</styleUrl>\n")
                // КРИТИЧНО: сначала lon, потом lat!
                w.write("    <Point><coordinates>$lon,$lat,0</coordinates></Point>\n")
                w.write("  </Placemark>\n")
            }

            w.write("</Document>\n</kml>\n")
            w.flush()
        }
    }

    // #RRGGBB → ffBBGGRR (KML использует AABBGGRR, alpha=ff)
    private fun hexToKmlColor(hex: String): String {
        val c = hex.trimStart('#').padStart(6, '0').uppercase(Locale.US)
        return "ff${c.substring(4, 6)}${c.substring(2, 4)}${c.substring(0, 2)}"
    }

    private fun styleId(hex: String) = "s_${hex.trimStart('#').uppercase(Locale.US)}"

    private fun String.xmlEscape() = replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

// Пакует KML в ZIP-архив с именем doc.kml
class KmzConversionExporter : FormatExporter {
    override fun export(points: List<WayPoint>, out: OutputStream) {
        // Сначала строим KML в памяти, потом пакуем в ZIP
        val kmlBytes = ByteArrayOutputStream().also { buf ->
            KmlConversionExporter().export(points, buf)
        }.toByteArray()

        // BufferedOutputStream + use{} — гарантирует flush ZIP-архива
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml"))
            zip.write(kmlBytes)
            zip.closeEntry()
            zip.finish()
        }
    }
}

// X = lon (код 10), Y = lat (код 20) — совпадает с DxfParser
class DxfConversionExporter : FormatExporter {
    override fun export(points: List<WayPoint>, out: OutputStream) {
        OutputStreamWriter(out, Charsets.UTF_8).use { w ->
            w.write("  0\nSECTION\n  2\nHEADER\n")
            w.write("  9\n\$MEASUREMENT\n 70\n1\n")
            w.write("  0\nENDSEC\n")
            w.write("  0\nSECTION\n  2\nENTITIES\n")
            for (p in points) {
                val lon = String.format(Locale.US, "%.8f", p.lon)  // X = longitude
                val lat = String.format(Locale.US, "%.8f", p.lat)  // Y = latitude
                w.write("  0\nPOINT\n")
                w.write("  8\n0\n")
                w.write(" 10\n$lon\n")
                w.write(" 20\n$lat\n")
                w.write(" 30\n0.0\n")
                w.write("  1\n${p.name}\n")
            }
            w.write("  0\nENDSEC\n  0\nEOF\n")
            w.flush()
        }
    }
}
