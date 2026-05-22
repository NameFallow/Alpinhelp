package com.coordscanner.utils

import android.util.Xml
import com.coordscanner.model.WayPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.Writer

object GpxParser {

    fun parse(stream: InputStream): List<WayPoint> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, "UTF-8")
        }
        val points = mutableListOf<WayPoint>()
        var inWpt = false; var inName = false; var inColor = false
        var lat = 0.0; var lon = 0.0; var name = ""; var color = "#FF0000"

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "wpt" -> {
                        lat   = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon   = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        name  = ""; color = "#FF0000"; inWpt = true
                    }
                    "name"  -> if (inWpt) inName  = true
                    "color" -> if (inWpt) inColor = true
                }
                XmlPullParser.TEXT -> when {
                    inName  -> { name  = parser.text.trim(); inName  = false }
                    inColor -> {
                        val raw = parser.text.trim()
                        color = if (raw.startsWith("#")) raw else "#$raw"
                        inColor = false
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "wpt" && inWpt) {
                    points.add(WayPoint(lat, lon, name, color))
                    inWpt = false
                }
            }
            event = parser.next()
        }
        return points
    }

    fun write(points: List<WayPoint>, writer: Writer) {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.write("<gpx version=\"1.1\" creator=\"CoordScanner\"\n")
        writer.write("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        writer.write("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        writer.write("  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        for (p in points) {
            val lat = "%.8f".format(p.lat)
            val lon = "%.8f".format(p.lon)
            writer.write("  <wpt lat=\"$lat\" lon=\"$lon\">\n")
            writer.write("    <name>${p.name.xmlEscape()}</name>\n")
            writer.write("    <sym>Circle</sym>\n")
            writer.write("    <type>Waypoint</type>\n")
            writer.write("    <extensions>\n")
            writer.write("      <color>${p.color.xmlEscape()}</color>\n")
            writer.write("    </extensions>\n")
            writer.write("  </wpt>\n")
        }
        writer.write("</gpx>\n")
    }

    private fun String.xmlEscape() = replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
