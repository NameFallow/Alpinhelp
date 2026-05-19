package com.coordscanner.utils

import android.util.Log
import kotlin.math.abs

data class ParsedCoord(
    val name: String,
    val x: Double,
    val y: Double,
    val zone: Int,
    val isWgs84: Boolean = false,        // true when parsed from degree format
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

object OcrParser {
    private const val TAG = "OcrParser"

    // Table row: NAME  X_VALUE  Y_VALUE  (digits may contain spaces from OCR noise)
    private val TABLE_ROW = Regex(
        """([А-Яа-яёA-Za-z0-9_.№\-]+)\s{1,10}([\d][\d\s]{4,12}(?:[.,][\d]+)?)\s{1,10}([\d][\d\s]{5,14}(?:[.,][\d]+)?)"""
    )

    // Labeled: X = VALUE  Y = VALUE  (with optional name before)
    private val LABELED_XY = Regex(
        """(?:([А-Яа-яёA-Za-z0-9_.№\-]+)\s+)?[Xx]\s*[=:]\s*([\d\s]+(?:[.,][\d]+)?)\s+[Yy]\s*[=:]\s*([\d\s]+(?:[.,][\d]+)?)""",
        RegexOption.MULTILINE
    )

    // Degrees: 55°30'45.67"N 037°15'23.45"E  (various quote/degree chars from OCR)
    private val DEGREES = Regex(
        """(\d{1,3})\s*[°°oO]\s*(\d{1,2})\s*['’ʼ]\s*([\d.]+)\s*["”ʺ]?\s*([NnСс])\s+(\d{1,3})\s*[°°oO]\s*(\d{1,2})\s*['’ʼ]\s*([\d.]+)\s*["”ʺ]?\s*([EeВвEeЕе])"""
    )

    // Detects whether a text block contains any coordinate-like pattern
    fun hasCoordinatePattern(text: String): Boolean {
        val stripped = text.replace(" ", "")
        return TABLE_ROW.containsMatchIn(text) ||
                LABELED_XY.containsMatchIn(text) ||
                DEGREES.containsMatchIn(text) ||
                // raw 7-digit + 8-digit pair (X and Y) in stripped text
                Regex("""\d{7,8}[.,]?\d*\s+\d{7,9}[.,]?\d*""").containsMatchIn(text)
    }

    fun parseText(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()

        results += parseDegrees(text)
        results += parseLabeledXY(text)
        results += parseTableRows(text)

        return results.distinctBy { "${it.x.toLong()}_${it.y.toLong()}" }
    }

    private fun parseDegrees(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        for (match in DEGREES.findAll(text)) {
            val latDeg = match.groupValues[1].toDoubleOrNull() ?: continue
            val latMin = match.groupValues[2].toDoubleOrNull() ?: continue
            val latSec = match.groupValues[3].replace(",", ".").toDoubleOrNull() ?: continue
            val latHem = match.groupValues[4]
            val lonDeg = match.groupValues[5].toDoubleOrNull() ?: continue
            val lonMin = match.groupValues[6].toDoubleOrNull() ?: continue
            val lonSec = match.groupValues[7].replace(",", ".").toDoubleOrNull() ?: continue
            val lonHem = match.groupValues[8]

            var lat = latDeg + latMin / 60.0 + latSec / 3600.0
            var lon = lonDeg + lonMin / 60.0 + lonSec / 3600.0
            if (latHem.uppercase() in listOf("S", "Ю")) lat = -lat
            if (lonHem.uppercase() in listOf("W", "З")) lon = -lon

            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            Log.d(TAG, "Degrees parsed: lat=$lat lon=$lon")
            results.add(ParsedCoord("Точка", 0.0, 0.0, 0, isWgs84 = true, lat = lat, lon = lon))
        }
        return results
    }

    private fun parseLabeledXY(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        for (match in LABELED_XY.findAll(text)) {
            val name = match.groupValues[1].trim().ifEmpty { "Точка" }
            val xStr = match.groupValues[2].replace(" ", "").replace(",", ".")
            val yStr = match.groupValues[3].replace(" ", "").replace(",", ".")
            val x = xStr.toDoubleOrNull() ?: continue
            val y = yStr.toDoubleOrNull() ?: continue
            val zone = extractZone(y) ?: continue
            Log.d(TAG, "Labeled parsed: $name x=$x y=$y zone=$zone")
            results.add(ParsedCoord(name, x, y, zone))
        }
        return results
    }

    private fun parseTableRows(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        for (line in text.lines()) {
            val match = TABLE_ROW.find(line.trim()) ?: continue
            val name = match.groupValues[1].trim()
            val xStr = match.groupValues[2].replace(" ", "").replace(",", ".")
            val yStr = match.groupValues[3].replace(" ", "").replace(",", ".")
            val x = xStr.toDoubleOrNull() ?: continue
            val y = yStr.toDoubleOrNull() ?: continue
            if (x < 1_000_000 || x > 10_000_000) continue  // Sanity check for SK-42 X
            val zone = extractZone(y) ?: continue
            Log.d(TAG, "Table row parsed: $name x=$x y=$y zone=$zone")
            results.add(ParsedCoord(name, x, y, zone))
        }
        return results
    }

    private fun extractZone(y: Double): Int? {
        val yLong = y.toLong()
        if (yLong < 1_000_000) return null
        val prefix = (yLong / 1_000_000).toInt()
        return if (prefix in 1..32) prefix else null
    }
}
