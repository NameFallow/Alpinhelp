package com.coordscanner.utils

import android.util.Log

data class ParsedCoord(
    val name: String,
    val x: Double,
    val y: Double,
    val zone: Int,
    val isWgs84: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val system: String = "СК-42"
)

object OcrParser {
    private const val TAG = "OcrParser"

    // ── Быстрая проверка: есть ли в тексте что-то похожее на координаты ──
    fun hasCoordinatePattern(text: String): Boolean {
        val clean = cleanOcr(text)
        return containsSk42Numbers(clean) ||
               containsDegrees(clean) ||
               containsXYLabel(clean)
    }

    // ── Главный метод парсинга ──
    fun parseText(text: String): List<ParsedCoord> {
        val clean = cleanOcr(text)
        val results = mutableListOf<ParsedCoord>()

        results += parseDegrees(clean)          // WGS-84 градусы
        results += parseLabeledXY(clean)        // X = ... Y = ...
        results += parseTableRows(clean)        // табличные строки
        results += parseRawNumberPairs(clean)   // два числа подряд без меток

        // убираем дубли по координатам
        return results.distinctBy { "${it.x.toLong()}_${it.y.toLong()}_${it.isWgs84}" }
    }

    // ───────────────────────────────────────────────
    //  Очистка OCR-артефактов
    // ───────────────────────────────────────────────
    private fun cleanOcr(text: String): String {
        return text
            .replace("О", "0").replace("о", "0")   // кириллическая О → 0
            .replace("l", "1").replace("I", "1")    // строчная l → 1
            .replace(",", ".")
    }

    // ───────────────────────────────────────────────
    //  Вспомогательные проверки
    // ───────────────────────────────────────────────
    private fun containsSk42Numbers(text: String): Boolean {
        // Ищем 7-8 значные числа (типичные для СК-42)
        return Regex("""\b\d[\d\s]{5,9}\b""").containsMatchIn(text)
    }

    private fun containsDegrees(text: String): Boolean {
        return Regex("""\d{1,3}[°oO]\s*\d{1,2}""").containsMatchIn(text)
    }

    private fun containsXYLabel(text: String): Boolean {
        return Regex("""[XxХх]\s*[=:]\s*\d""").containsMatchIn(text) ||
               Regex("""[YyУу]\s*[=:]\s*\d""").containsMatchIn(text)
    }

    // ───────────────────────────────────────────────
    //  Извлечь число из строки (убираем пробелы внутри числа)
    // ───────────────────────────────────────────────
    private fun extractNumber(s: String): Double? {
        // "5 632 145.23" → "5632145.23"
        return s.replace(Regex("""\s"""), "").replace(",", ".").toDoubleOrNull()
    }

    // ───────────────────────────────────────────────
    //  Парсинг: X = VALUE  Y = VALUE  (русские и латинские буквы)
    // ───────────────────────────────────────────────
    private fun parseLabeledXY(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()

        // Находим все упоминания X= и Y= в тексте (могут быть на разных строках)
        val xPattern = Regex("""[XxХх]\s*[=:]\s*([\d\s.]{6,15})""")
        val yPattern = Regex("""[YyУу]\s*[=:]\s*([\d\s.]{6,16})""")

        val xMatches = xPattern.findAll(text).toList()
        val yMatches = yPattern.findAll(text).toList()

        // Пробуем скомбинировать X и Y пары
        for (xm in xMatches) {
            // Ищем ближайший Y после этого X
            val xEnd = xm.range.last
            val ym = yMatches.firstOrNull { it.range.first > xEnd - 50 } ?: continue

            val x = extractNumber(xm.groupValues[1]) ?: continue
            val y = extractNumber(ym.groupValues[1]) ?: continue

            if (!isValidSk42X(x) || !isValidSk42Y(y)) continue
            val zone = extractZone(y) ?: continue

            // Пытаемся найти имя точки перед X=
            val nameBefore = text.substring(maxOf(0, xm.range.first - 30), xm.range.first)
                .trim().split(Regex("""\s+""")).lastOrNull { it.length > 1 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
                ?: "Точка"

            Log.d(TAG, "Labeled: name=$nameBefore x=$x y=$y zone=$zone")
            results.add(ParsedCoord(nameBefore, x, y, zone))
        }

        return results
    }

    // ───────────────────────────────────────────────
    //  Парсинг: табличные строки  ИМЯ  X  Y
    // ───────────────────────────────────────────────
    private fun parseTableRows(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()

        for (line in text.lines()) {
            val tokens = line.trim().split(Regex("""\s{2,}|\t"""))
            if (tokens.size < 3) continue

            // Перебираем варианты: какие два токена — X и Y
            for (i in 1 until tokens.size - 1) {
                val x = extractNumber(tokens[i]) ?: continue
                val y = extractNumber(tokens[i + 1]) ?: continue

                if (!isValidSk42X(x) || !isValidSk42Y(y)) continue
                val zone = extractZone(y) ?: continue

                val name = tokens.subList(0, i).joinToString(" ").trim().ifEmpty { "Точка" }
                Log.d(TAG, "Table: name=$name x=$x y=$y zone=$zone")
                results.add(ParsedCoord(name, x, y, zone))
                break
            }
        }

        return results
    }

    // ───────────────────────────────────────────────
    //  Парсинг: два числа подряд без меток (последний resort)
    // ───────────────────────────────────────────────
    private fun parseRawNumberPairs(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()

        // Ищем пары чисел: 7 цифр + 8 цифр (с возможными пробелами внутри)
        val numPattern = Regex("""(\d[\d\s]{5,8}\d)""")
        val allNums = numPattern.findAll(text).toList()

        var i = 0
        while (i < allNums.size - 1) {
            val x = extractNumber(allNums[i].value)
            val y = extractNumber(allNums[i + 1].value)

            if (x != null && y != null && isValidSk42X(x) && isValidSk42Y(y)) {
                val zone = extractZone(y)
                if (zone != null) {
                    val before = text.substring(
                        maxOf(0, allNums[i].range.first - 20),
                        allNums[i].range.first
                    ).trim()
                    val name = before.split(Regex("""\s+"""))
                        .lastOrNull { it.isNotEmpty() && it[0].isLetter() } ?: "Точка"
                    Log.d(TAG, "Raw pair: name=$name x=$x y=$y zone=$zone")
                    results.add(ParsedCoord(name, x, y, zone))
                    i += 2
                    continue
                }
            }
            i++
        }

        return results
    }

    // ───────────────────────────────────────────────
    //  Парсинг: градусный формат WGS-84
    //  55°30'45.67"N  37°15'23.45"E
    // ───────────────────────────────────────────────
    private fun parseDegrees(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()

        val deg = Regex(
            """(\d{1,3})\s*[°oO*]\s*(\d{1,2})\s*['`']\s*([\d.]+)\s*[""]?\s*([NnСсSs])\D{0,5}""" +
            """(\d{1,3})\s*[°oO*]\s*(\d{1,2})\s*['`']\s*([\d.]+)\s*[""]?\s*([EeВвEеЕе])"""
        )

        for (m in deg.findAll(text)) {
            val lat = dms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) ?: continue
            val lon = dms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8]) ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            Log.d(TAG, "Degrees: lat=$lat lon=$lon")
            results.add(ParsedCoord("Точка", 0.0, 0.0, 0, isWgs84 = true, lat = lat, lon = lon, system = "WGS-84"))
        }

        return results
    }

    private fun dms(degStr: String, minStr: String, secStr: String, hem: String): Double? {
        val d = degStr.toDoubleOrNull() ?: return null
        val m = minStr.toDoubleOrNull() ?: return null
        val s = secStr.toDoubleOrNull() ?: return null
        var v = d + m / 60.0 + s / 3600.0
        if (hem.uppercase() in listOf("S", "Ю", "W", "З")) v = -v
        return v
    }

    // ───────────────────────────────────────────────
    //  Валидация и зона
    // ───────────────────────────────────────────────

    // СК-42 X (northing): 1 000 000 — 9 999 999 метров
    private fun isValidSk42X(x: Double) = x in 1_000_000.0..9_999_999.0

    // СК-42 Y с префиксом зоны: 1_000_000 — 32_999_999
    private fun isValidSk42Y(y: Double) = y in 1_000_000.0..32_999_999.0

    private fun extractZone(y: Double): Int? {
        val prefix = (y / 1_000_000).toInt()
        return if (prefix in 1..32) prefix else null
    }
}
