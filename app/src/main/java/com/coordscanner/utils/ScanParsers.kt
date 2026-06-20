package com.coordscanner.utils

import android.util.Log
import org.json.JSONArray

/**
 * Парсеры JSON-ответов AI-моделей (Gemini, Anthropic) — общие для всех провайдеров.
 * Логика валидации и приведения зон/координат живёт здесь, чтобы провайдеры
 * отвечали только за HTTP-вызов и доставку «сырого» JSON-массива.
 */
internal object ScanParsers {

    private const val TAG = "ScanParsers"

    fun parseArray(raw: String): JSONArray? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            JSONArray(s)
        } catch (_: Exception) {
            val start = s.indexOf('[')
            val end = s.lastIndexOf(']')
            if (start in 0 until end) {
                try { JSONArray(s.substring(start, end + 1)) } catch (_: Exception) { null }
            } else null
        }
    }

    fun parseSk42Response(json: String): List<MatchedRow> {
        val arr = parseArray(json) ?: return emptyList()
        val out = mutableListOf<MatchedRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim().ifEmpty { "Точка" }
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            if (x !in 10_000.0..99_999_999.0) continue
            if (y !in 10_000.0..99_999_999.0) continue
            val zoneFromAi = o.optInt("zone", 0)
            val zone = when {
                zoneFromAi in 1..60 -> zoneFromAi
                y >= 1_000_000.0 -> (y / 1_000_000).toInt().let { if (it in 1..60) it else 0 }
                else -> 0
            }
            out += MatchedRow(name = name, x = x, y = y, zone = zone)
        }
        Log.d(TAG, "SK-42 распознано строк: ${out.size}")
        return out
    }

    fun parseWgsResponse(json: String): List<MatchedRow> {
        val arr = parseArray(json) ?: return emptyList()
        val out = mutableListOf<MatchedRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim().ifEmpty { "Точка" }
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            if (lat !in -90.0..90.0) continue
            if (lon !in -180.0..180.0) continue
            out += MatchedRow(name = name, x = 0.0, y = 0.0, zone = 0, isWgs84 = true, lat = lat, lon = lon)
        }
        Log.d(TAG, "WGS распознано точек: ${out.size}")
        return out
    }

    fun parseBatchResponse(json: String): List<ParsedCoord> {
        val arr = parseArray(json) ?: return emptyList()
        val out = mutableListOf<ParsedCoord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim().ifEmpty { "Точка" }
            val isWgs = o.optBoolean("isWgs84", false)
            if (isWgs) {
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                out += ParsedCoord(
                    name = name, x = 0.0, y = 0.0, zone = 0,
                    isWgs84 = true, lat = lat, lon = lon, system = "WGS-84"
                )
            } else {
                val x = o.optDouble("x", Double.NaN)
                val y = o.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                if (x !in 10_000.0..99_999_999.0) continue
                if (y !in 10_000.0..99_999_999.0) continue
                val zoneFromAi = o.optInt("zone", 0)
                val zone = when {
                    zoneFromAi in 1..60 -> zoneFromAi
                    y >= 1_000_000.0 -> (y / 1_000_000).toInt().let { if (it in 1..60) it else 0 }
                    else -> 0
                }
                out += ParsedCoord(name = name, x = x, y = y, zone = zone)
            }
        }
        Log.d(TAG, "Batch распознано точек: ${out.size}")
        return out
    }
}
