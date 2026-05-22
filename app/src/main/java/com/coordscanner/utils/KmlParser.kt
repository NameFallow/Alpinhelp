package com.coordscanner.utils

import android.util.Xml
import com.coordscanner.model.WayPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object KmlParser {

    fun parse(stream: InputStream): List<WayPoint> {
        val bytes = stream.readBytes()
        val styles = collectStyles(bytes)
        return collectPlacemarks(bytes, styles)
    }

    // Первый проход: Style id → #RRGGBB
    private fun collectStyles(kml: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val p = newParser(kml)
        var styleId: String? = null
        var inIconStyle = false
        var inColor = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "Style"     -> styleId = p.getAttributeValue(null, "id")
                    "IconStyle" -> if (styleId != null) inIconStyle = true
                    "color"     -> if (inIconStyle) inColor = true
                }
                XmlPullParser.TEXT -> if (inColor && styleId != null) {
                    result[styleId!!] = kmlColorToHex(p.text.trim())
                    inColor = false
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "Style"     -> { styleId = null; inIconStyle = false }
                    "IconStyle" -> inIconStyle = false
                }
            }
            ev = p.next()
        }
        return result
    }

    // Второй проход: Placemark → WayPoint (имя, координаты, цвет, описание)
    private fun collectPlacemarks(kml: ByteArray, styles: Map<String, String>): List<WayPoint> {
        val points = mutableListOf<WayPoint>()
        val p = newParser(kml)

        var inPlacemark = false
        var inPoint = false
        var inName = false; var inCoords = false; var inDesc = false
        var inStyleUrl = false; var inIconStyle = false; var inColor = false
        var name = ""; var coordsText = ""; var styleUrl = ""
        var inlineColor = ""; var description = ""

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "Placemark" -> {
                        inPlacemark = true
                        name = ""; coordsText = ""; styleUrl = ""
                        inlineColor = ""; description = ""
                        inPoint = false; inName = false; inCoords = false
                        inDesc = false; inStyleUrl = false
                        inIconStyle = false; inColor = false
                    }
                    "Point"       -> if (inPlacemark) inPoint     = true
                    "name"        -> if (inPlacemark) inName      = true
                    "coordinates" -> if (inPlacemark && inPoint) inCoords = true
                    "description" -> if (inPlacemark) inDesc      = true
                    "styleUrl"    -> if (inPlacemark) inStyleUrl  = true
                    "IconStyle"   -> if (inPlacemark) inIconStyle = true
                    "color"       -> if (inPlacemark && inIconStyle) inColor = true
                }
                XmlPullParser.TEXT -> when {
                    inName      -> { name        = p.text.trim(); inName      = false }
                    inCoords    -> { coordsText  = p.text.trim(); inCoords    = false }
                    inDesc      -> { description = p.text.trim(); inDesc      = false }
                    inStyleUrl  -> { styleUrl    = p.text.trim().trimStart('#'); inStyleUrl = false }
                    inColor     -> { inlineColor = p.text.trim(); inColor     = false }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "Point"     -> inPoint     = false
                    "IconStyle" -> inIconStyle = false
                    "Placemark" -> if (inPlacemark) {
                        val (lat, lon) = parseCoords(coordsText)
                        if (lat != 0.0 || lon != 0.0) {
                            val color = when {
                                inlineColor.isNotBlank() -> kmlColorToHex(inlineColor)
                                styleUrl.isNotBlank()    -> styles[styleUrl] ?: "#FF0000"
                                else                     -> "#FF0000"
                            }
                            points.add(WayPoint(
                                name        = name.ifBlank { "Точка ${points.size + 1}" },
                                lat         = lat,
                                lon         = lon,
                                color       = color,
                                description = description.ifBlank { null }
                            ))
                        }
                        inPlacemark = false
                    }
                }
            }
            ev = p.next()
        }
        return points
    }

    // KML координаты: "lon,lat,alt" — сначала LON, потом LAT
    private fun parseCoords(text: String): Pair<Double, Double> {
        val parts = text.trim().split(Regex("[,\\s]+"))
        if (parts.size < 2) return 0.0 to 0.0
        val lon = parts[0].replace(",", ".").toDoubleOrNull() ?: return 0.0 to 0.0
        val lat = parts[1].replace(",", ".").toDoubleOrNull() ?: return 0.0 to 0.0
        return lat to lon
    }

    // KML цвет AABBGGRR → #RRGGBB (порядок байт обратный!)
    fun kmlColorToHex(kmlColor: String): String {
        if (kmlColor.length < 8) return "#FF0000"
        val r = kmlColor.substring(6, 8).uppercase()
        val g = kmlColor.substring(4, 6).uppercase()
        val b = kmlColor.substring(2, 4).uppercase()
        return "#$r$g$b"
    }

    private fun newParser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(bytes.inputStream(), "UTF-8")
        }
}
