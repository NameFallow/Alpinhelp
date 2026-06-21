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
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            if (!isValidSk42Pair(x, y)) continue
            val name = cleanName(o.optString("name", ""), x, y)
            val zone = resolveZone(o.optInt("zone", 0), y)
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
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            if (lat !in -90.0..90.0) continue
            if (lon !in -180.0..180.0) continue
            val name = cleanName(o.optString("name", ""), lat, lon)
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
            val isWgs = o.optBoolean("isWgs84", false)
            if (isWgs) {
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                val name = cleanName(o.optString("name", ""), lat, lon)
                out += ParsedCoord(
                    name = name, x = 0.0, y = 0.0, zone = 0,
                    isWgs84 = true, lat = lat, lon = lon, system = "WGS-84"
                )
            } else {
                val x = o.optDouble("x", Double.NaN)
                val y = o.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                if (!isValidSk42Pair(x, y)) continue
                val name = cleanName(o.optString("name", ""), x, y)
                val zone = resolveZone(o.optInt("zone", 0), y)
                out += ParsedCoord(name = name, x = x, y = y, zone = zone)
            }
        }
        Log.d(TAG, "Batch распознано точек: ${out.size}")
        return out
    }

    // X (северная) в России лежит в 100k..9.9M; Y (восточная) с зоной — 10k..99.9M.
    // Защита от «AI прочитал одну и ту же цифру дважды»: разница X и Y < 100 — почти
    // гарантированно слипшийся дубль, не валидная пара.
    private fun isValidSk42Pair(x: Double, y: Double): Boolean {
        if (x !in 100_000.0..9_999_999.0) return false
        if (y !in 10_000.0..99_999_999.0) return false
        if (kotlin.math.abs(x - y) < 100.0) return false
        return true
    }

    // Если у AI зона и зона из Y расходятся — приоритет у Y (первая(-ые) цифры Y и есть
    // источник зоны в СК-42 с префиксом). Иначе доверяем AI; если ни то ни другое — 0.
    private fun resolveZone(zoneFromAi: Int, y: Double): Int {
        if (y >= 1_000_000.0) {
            val zoneFromY = (y / 1_000_000).toInt().let { if (it in 1..60) it else 0 }
            if (zoneFromY != 0) {
                if (zoneFromAi in 1..60 && zoneFromAi != zoneFromY) {
                    Log.w(TAG, "Зона: AI=$zoneFromAi, из Y=$zoneFromY — беру $zoneFromY")
                }
                return zoneFromY
            }
        }
        return if (zoneFromAi in 1..60) zoneFromAi else 0
    }

    // Чистим имя от типового мусора: если AI вернул вместо имени саму координату
    // (полностью числовое значение, совпадающее с x/y), либо явный плейсхолдер —
    // подставляем "Точка". Длина имени до 256: в реальных таблицах встречаются
    // полные описания позиций («ЗРПК Панцирь С1 — 2,5 км юго зап. н.п. Никаноровка»),
    // их нельзя резать.
    private fun cleanName(raw: String, a: Double, b: Double): String {
        val s = raw.trim()
        if (s.isEmpty()) return "Точка"
        val onlyDigits = s.replace(Regex("[\\s.,\\-]"), "")
        if (onlyDigits.isNotEmpty() && onlyDigits.all { it.isDigit() }) {
            val asNum = onlyDigits.toDoubleOrNull()
            if (asNum != null && (kotlin.math.abs(asNum - a) < 1.0 || kotlin.math.abs(asNum - b) < 1.0)) {
                return "Точка"
            }
        }
        return if (s.length > 256) s.substring(0, 256) else s
    }
}
