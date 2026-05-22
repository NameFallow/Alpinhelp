package com.coordscanner.utils

import android.util.Xml
import com.coordscanner.model.WayPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object KmlParser {

    private data class StyleInfo(val color: String, val iconHref: String?)

    fun parse(stream: InputStream): List<WayPoint> {
        val bytes = stream.readBytes()
        val styles = collectStyles(bytes)
        return collectPlacemarks(bytes, styles)
    }

    // Первый проход: Style id → StyleInfo(color, iconHref)
    private fun collectStyles(kml: ByteArray): Map<String, StyleInfo> {
        val result = mutableMapOf<String, StyleInfo>()
        val p = newParser(kml)
        var styleId: String? = null
        var inIconStyle = false; var inIcon = false
        var inColor = false; var inHref = false
        var currentColor = ""; var currentHref = ""

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "Style"     -> {
                        styleId = p.getAttributeValue(null, "id")
                        currentColor = ""; currentHref = ""
                    }
                    "IconStyle" -> if (styleId != null) inIconStyle = true
                    "Icon"      -> if (inIconStyle) inIcon = true
                    "color"     -> if (inIconStyle) inColor = true
                    "href"      -> if (inIcon) inHref = true
                }
                XmlPullParser.TEXT -> when {
                    inColor && styleId != null -> { currentColor = p.text.trim(); inColor = false }
                    inHref && styleId != null  -> { currentHref  = p.text.trim(); inHref  = false }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "Style"     -> {
                        if (styleId != null) {
                            result[styleId!!] = StyleInfo(
                                color    = if (currentColor.isNotBlank()) kmlColorToHex(currentColor) else "#FF0000",
                                iconHref = currentHref.ifBlank { null }
                            )
                        }
                        styleId = null; inIconStyle = false; inIcon = false
                    }
                    "IconStyle" -> { inIconStyle = false; inIcon = false }
                    "Icon"      -> inIcon = false
                }
            }
            ev = p.next()
        }
        return result
    }

    // Второй проход: Placemark → WayPoint
    private fun collectPlacemarks(kml: ByteArray, styles: Map<String, StyleInfo>): List<WayPoint> {
        val points = mutableListOf<WayPoint>()
        val p = newParser(kml)

        var inPlacemark = false
        var inPoint = false
        var inName = false; var inCoords = false; var inDesc = false
        var inStyleUrl = false; var inIconStyle = false; var inIcon = false
        var inColor = false; var inHref = false
        var name = ""; var coordsText = ""; var styleUrl = ""
        var inlineColor = ""; var inlineHref = ""; var description = ""

        // ExtendedData
        var inExtendedData = false
        var currentDataName = ""
        var inValue = false
        var extDataPhotoRef: String? = null

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "Placemark" -> {
                        inPlacemark = true
                        name = ""; coordsText = ""; styleUrl = ""
                        inlineColor = ""; inlineHref = ""; description = ""
                        inPoint = false; inName = false; inCoords = false
                        inDesc = false; inStyleUrl = false
                        inIconStyle = false; inIcon = false; inColor = false; inHref = false
                        inExtendedData = false; currentDataName = ""
                        inValue = false; extDataPhotoRef = null
                    }
                    "Point"        -> if (inPlacemark) inPoint       = true
                    "name"         -> if (inPlacemark) inName        = true
                    "coordinates"  -> if (inPlacemark && inPoint) inCoords = true
                    "description"  -> if (inPlacemark) inDesc        = true
                    "styleUrl"     -> if (inPlacemark) inStyleUrl    = true
                    "IconStyle"    -> if (inPlacemark) inIconStyle   = true
                    "Icon"         -> if (inPlacemark && inIconStyle) inIcon = true
                    "color"        -> if (inPlacemark && inIconStyle) inColor = true
                    "href"         -> if (inPlacemark && inIcon) inHref = true
                    "ExtendedData" -> if (inPlacemark) inExtendedData = true
                    "Data"         -> if (inExtendedData) {
                        currentDataName = p.getAttributeValue(null, "name") ?: ""
                    }
                    "value"        -> if (inExtendedData &&
                        currentDataName.lowercase() in PHOTO_DATA_NAMES) inValue = true
                }
                XmlPullParser.TEXT -> when {
                    inName     -> { name        = p.text.trim(); inName     = false }
                    inCoords   -> { coordsText  = p.text.trim(); inCoords   = false }
                    inDesc     -> { description = p.text.trim(); inDesc     = false }
                    inStyleUrl -> { styleUrl    = p.text.trim().trimStart('#'); inStyleUrl = false }
                    inColor    -> { inlineColor = p.text.trim(); inColor    = false }
                    inHref     -> { inlineHref  = p.text.trim(); inHref     = false }
                    inValue    -> { extDataPhotoRef = p.text.trim(); inValue = false }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "Point"        -> inPoint        = false
                    "IconStyle"    -> { inIconStyle  = false; inIcon = false }
                    "Icon"         -> inIcon          = false
                    "ExtendedData" -> inExtendedData  = false
                    "Data"         -> currentDataName = ""
                    "value"        -> inValue         = false
                    "Placemark"    -> if (inPlacemark) {
                        val (lat, lon) = parseCoords(coordsText)
                        if (lat != 0.0 || lon != 0.0) {
                            val styleInfo = if (styleUrl.isNotBlank()) styles[styleUrl] else null
                            val color = when {
                                inlineColor.isNotBlank() -> kmlColorToHex(inlineColor)
                                styleInfo != null        -> styleInfo.color
                                else                     -> "#FF0000"
                            }
                            val symbol = when {
                                inlineHref.isNotBlank()      -> inlineHref
                                styleInfo?.iconHref != null  -> styleInfo.iconHref
                                else                         -> null
                            }
                            // Если в description нет фото, но есть ссылка из ExtendedData —
                            // вставляем синтетический тег, чтобы KmzParser её нашёл
                            val effectiveDesc = mergePhotoRef(description, extDataPhotoRef)
                            points.add(WayPoint(
                                name        = name.ifBlank { "Точка ${points.size + 1}" },
                                lat         = lat,
                                lon         = lon,
                                color       = color,
                                description = effectiveDesc.ifBlank { null },
                                symbol      = symbol
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

    // Если description не содержит img/href на изображение, добавляем ref из ExtendedData
    private fun mergePhotoRef(description: String, extDataPhotoRef: String?): String {
        if (extDataPhotoRef.isNullOrBlank()) return description
        val hasPhoto = description.contains(
            Regex("""(?:src|href)=["'][^"']+\.(?:jpg|jpeg|png|gif|webp|bmp)["']""",
                RegexOption.IGNORE_CASE)
        )
        if (hasPhoto) return description
        val imgTag = """<img src="$extDataPhotoRef">"""
        return if (description.isBlank()) imgTag else "$description $imgTag"
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

    private val PHOTO_DATA_NAMES = setOf("photo", "image", "foto", "picture", "img")
}
