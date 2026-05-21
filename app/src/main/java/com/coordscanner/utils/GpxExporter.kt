package com.coordscanner.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.util.Log
import com.coordscanner.model.Point
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale

object GpxExporter {

    private const val TAG = "GpxExporter"

    fun exportAndShare(context: Context, points: List<Point>) {
        Log.d(TAG, "Exporting ${points.size} points:")
        for (p in points) {
            Log.d(TAG, "  ${p.name}: lat=${p.latWgs84}  lon=${p.lonWgs84}  xSk42=${p.xSk42}  ySk42=${p.ySk42}")
        }
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
                // Use the pre-computed WGS-84 coordinates that CoordScanner already
                // displayed to the user — guaranteed to match the on-screen values.
                val lat = String.format(Locale.US, "%.8f", p.latWgs84)
                val lon = String.format(Locale.US, "%.8f", p.lonWgs84)
                val name = escapeXml(p.name)
                appendLine("""  <wpt lat="$lat" lon="$lon">""")
                appendLine("    <name>$name</name>")
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
        // Delete old exports so AlpineQuest can't serve a cached version of a stale URI.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "coordscanner_${System.currentTimeMillis()}.gpx")
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

        // Open directly if only one app handles GPX (e.g. AlpesQuest), else show chooser
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            val chooser = Intent.createChooser(intent, "Открыть в...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }
}
