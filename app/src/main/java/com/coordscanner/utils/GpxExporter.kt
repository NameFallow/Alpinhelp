package com.coordscanner.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.coordscanner.model.Point
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object GpxExporter {

    fun exportAndShare(context: Context, points: List<Point>) {
        val gpxContent = buildGpx(points)
        val file = writeGpxFile(context, gpxContent)
        shareGpxFile(context, file)
    }

    private fun buildGpx(points: List<Point>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = sdf.format(Date())

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<gpx version="1.1" creator="CoordScanner"""")
            appendLine("""  xmlns="http://www.topografix.com/GPX/1/1"""")
            appendLine("""  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
            appendLine("""  xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3"""")
            appendLine("""  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")
            appendLine("  <metadata><time>$now</time></metadata>")

            for (p in points) {
                val lat = "%.8f".format(p.latWgs84)
                val lon = "%.8f".format(p.lonWgs84)
                val name = escapeXml(p.name)
                val desc = if (p.xSk42 != 0.0)
                    "X: ${p.xSk42.toLong()}  Y: ${p.ySk42.toLong()}  зона ${p.zone}"
                else
                    "WGS-84: %.5f N  %.5f E".format(p.latWgs84, p.lonWgs84)
                appendLine("""  <wpt lat="$lat" lon="$lon">""")
                appendLine("    <name>$name</name>")
                appendLine("    <desc>${escapeXml(desc)}</desc>")
                appendLine("    <sym>Circle</sym>")
                appendLine("    <type>Waypoint</type>")
                appendLine("    <extensions>")
                appendLine("      <color>${p.color}</color>")
                appendLine("    </extensions>")
                appendLine("  </wpt>")
            }

            appendLine("</gpx>")
        }
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun writeGpxFile(context: Context, content: String): File {
        val dir = File(context.cacheDir, "gpx").apply { mkdirs() }
        val file = File(dir, "coordscanner_export.gpx")
        FileWriter(file).use { it.write(content) }
        return file
    }

    private fun shareGpxFile(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Try ACTION_VIEW first (AlpesQuest handles it), fallback to chooser
        val chooser = Intent.createChooser(intent, "Открыть в...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
