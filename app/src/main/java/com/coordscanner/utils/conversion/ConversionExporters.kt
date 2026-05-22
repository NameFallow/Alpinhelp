package com.coordscanner.utils.conversion

import com.coordscanner.model.WayPoint
import com.coordscanner.utils.GpxParser
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
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

    private data class StyleKey(val color: String, val symbol: String?)

    override fun export(points: List<WayPoint>, out: OutputStream) {
        OutputStreamWriter(out, Charsets.UTF_8).use { w ->
            val styleKeys = points.map { StyleKey(it.color, it.symbol) }.distinct()

            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")

            // Style-блоки: уникальная пара (цвет, ярлык)
            for (sk in styleKeys) {
                w.write("  <Style id=\"${styleId(sk.color, sk.symbol)}\">\n")
                w.write("    <IconStyle><color>${hexToKmlColor(sk.color)}</color>\n")
                val href = sk.symbol ?: "http://maps.google.com/mapfiles/kml/paddle/wht-circle.png"
                w.write("      <Icon><href>$href</href></Icon>\n")
                w.write("    </IconStyle>\n  </Style>\n")
            }

            // Placemark-и: ВНИМАНИЕ — в KML координаты lon,lat,alt (не lat,lon!)
            for (p in points) {
                val lat = String.format(Locale.US, "%.8f", p.lat)
                val lon = String.format(Locale.US, "%.8f", p.lon)
                w.write("  <Placemark>\n")
                w.write("    <name>${p.name.xmlEscape()}</name>\n")
                val desc = buildDescription(p)
                if (desc != null) {
                    w.write("    <description><![CDATA[$desc]]></description>\n")
                }
                w.write("    <styleUrl>#${styleId(p.color, p.symbol)}</styleUrl>\n")
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

    private fun styleId(hex: String, symbol: String? = null): String {
        val base = "s_${hex.trimStart('#').uppercase(Locale.US)}"
        if (symbol == null) return base
        val suffix = symbol.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_]"), "_").take(32)
        return "${base}_$suffix"
    }

    private fun buildDescription(p: WayPoint): String? {
        val photoName = p.photoOriginalName ?: p.photoPath?.let { File(it).name }
        val imgHtml = photoName?.let { "<img src=\"files/$it\">" }
        val cleanDesc = p.description
            ?.replace(Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
        return when {
            imgHtml != null && cleanDesc != null -> "$imgHtml\n$cleanDesc"
            imgHtml != null -> imgHtml
            cleanDesc != null -> cleanDesc
            else -> null
        }
    }

    private fun String.xmlEscape() = replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

// Пакует KML в ZIP-архив с именем doc.kml + фотографии и иконки в files/
class KmzConversionExporter : FormatExporter {
    override fun export(points: List<WayPoint>, out: OutputStream) {
        val kmlBytes = ByteArrayOutputStream().also { buf ->
            KmlConversionExporter().export(points, buf)
        }.toByteArray()

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml"))
            zip.write(kmlBytes)
            zip.closeEntry()

            val seenEntries = mutableSetOf<String>()

            // Пакуем уникальные фото как files/<имя>
            for (p in points) {
                val photoPath = p.photoPath ?: continue
                val photoName = p.photoOriginalName ?: File(photoPath).name
                val entry = "files/$photoName"
                if (seenEntries.add(entry)) {
                    val photoFile = File(photoPath)
                    if (photoFile.exists()) {
                        zip.putNextEntry(ZipEntry(entry))
                        photoFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }

            // Пакуем иконки, сохраняя оригинальный путь из symbol (напр. "files/aq_wpt_circle.png")
            for (p in points) {
                val iconPath = p.iconFilePath ?: continue
                val symbol   = p.symbol ?: continue
                val entry = if (symbol.contains('/')) symbol else "files/${symbol.substringAfterLast('/')}"
                if (seenEntries.add(entry)) {
                    val iconFile = File(iconPath)
                    if (iconFile.exists()) {
                        zip.putNextEntry(ZipEntry(entry))
                        iconFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }

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
