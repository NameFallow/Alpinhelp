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

    // ── Быстрая проверка для overlay-подсветки ──────────────────
    fun hasCoordinatePattern(text: String): Boolean {
        return Regex("""\d{7,}""").containsMatchIn(text) ||              // 7+ цифр подряд
               Regex("""[XxХх]\s*[=:]""").containsMatchIn(text) ||       // X= или X:
               Regex("""\d\s*[°*]\s*\d""").containsMatchIn(text) ||      // градусы
               Regex("""\d[\d ]{4,}\d\s+\d[\d ]{5,}\d""").containsMatchIn(text) // два больших числа
    }

    // ── Главный метод ────────────────────────────────────────────
    fun parseText(rawText: String): List<ParsedCoord> {
        val text = fixOcrNoise(rawText)
        val results = mutableListOf<ParsedCoord>()

        results += parseDegrees(text)
        results += parseLabeledXY(text)
        results += parseLines(text)

        return results.distinctBy { "${it.x.toLong()}_${it.y.toLong()}_${it.isWgs84}" }
    }

    // ── Исправление OCR-артефактов (осторожно, только цифровой контекст) ──
    private fun fixOcrNoise(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            // Кириллическая О рядом с цифрой → 0
            if ((ch == 'О' || ch == 'о') &&
                ((i > 0 && text[i-1].isDigit()) || (i < text.length-1 && text[i+1].isDigit()))) {
                sb.append('0')
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    // ── Извлечь все числа >= 100000 из строки ───────────────────
    private data class NumToken(val value: Double, val pos: Int)

    private fun extractNumbers(line: String): List<NumToken> {
        val result = mutableListOf<NumToken>()
        // Число: цифры с возможными разделителями тысяч (пробел или запятая) и точкой
        // Примеры: 5632145  5 632 145  5,632,145  5632145.23
        val re = Regex("""(\d{1,3}(?:[ ,]\d{3})*(?:[.,]\d+)?|\d{4,}(?:[.,]\d+)?)""")
        for (m in re.findAll(line)) {
            val raw = m.value
            val clean = raw.replace(Regex("""[ ,](?=\d{3}\b)"""), "")  // убрать только разделители тысяч
                          .replace(",", ".")
            val v = clean.toDoubleOrNull() ?: continue
            if (v >= 100_000.0) result.add(NumToken(v, m.range.first))
        }
        return result
    }

    // ── Основной парсер строк ────────────────────────────────────
    // Ищет пары (X, Y) в каждой строке, X = 7 цифр, Y = 8 цифр с префиксом зоны
    private fun parseLines(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.length < 10) continue

            val nums = extractNumbers(trimmed)
            if (nums.size < 2) continue

            // Перебираем соседние пары
            for (i in 0 until nums.size - 1) {
                val x = nums[i].value
                val y = nums[i + 1].value

                if (isValidX(x) && isValidY(y)) {
                    val zone = zoneOf(y) ?: continue
                    val name = extractName(trimmed, nums[i].pos)
                    Log.d(TAG, "Line: '$name' x=$x y=$y zone=$zone")
                    results.add(ParsedCoord(name, x, y, zone))
                    break
                }
            }
        }

        // Дополнительно: X на одной строке, Y на следующей
        results += parseMultiLine(text)
        return results
    }

    // Обрабатывает случай когда X и Y на разных строках (без меток)
    private fun parseMultiLine(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        val lines = text.lines()
        for (i in 0 until lines.size - 1) {
            val numsA = extractNumbers(lines[i])
            val numsB = extractNumbers(lines[i + 1])
            if (numsA.isEmpty() || numsB.isEmpty()) continue

            val x = numsA.lastOrNull { isValidX(it.value) }?.value ?: continue
            val y = numsB.firstOrNull { isValidY(it.value) }?.value ?: continue
            val zone = zoneOf(y) ?: continue

            val name = extractName(lines[i].trim(), 0)
            Log.d(TAG, "MultiLine: '$name' x=$x y=$y zone=$zone")
            results.add(ParsedCoord(name, x, y, zone))
        }
        return results
    }

    // ── X=... Y=... (метки) ──────────────────────────────────────
    private fun parseLabeledXY(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()

        // Ищем X= и Y= (латинские и кириллические)
        val xRe = Regex("""[XxХх]\s*[=:]\s*([\d ,.]{5,16})""")
        val yRe = Regex("""[YyУу]\s*[=:]\s*([\d ,.]{6,17})""")

        val xs = xRe.findAll(text).toList()
        val ys = yRe.findAll(text).toList()

        for (xm in xs) {
            val x = parseNumStr(xm.groupValues[1]) ?: continue
            if (!isValidX(x)) continue

            // Ближайший Y после X (или до X не дальше 100 символов)
            val ym = ys.minByOrNull { Math.abs(it.range.first - xm.range.last) } ?: continue
            val y = parseNumStr(ym.groupValues[1]) ?: continue
            if (!isValidY(y)) continue
            val zone = zoneOf(y) ?: continue

            val name = text.substring(maxOf(0, xm.range.first - 40), xm.range.first)
                .trim().split(Regex("""\s+"""))
                .lastOrNull { it.any { c -> c.isLetter() } } ?: "Точка"

            Log.d(TAG, "Labeled: '$name' x=$x y=$y zone=$zone")
            results.add(ParsedCoord(name, x, y, zone))
        }
        return results
    }

    // ── Градусы WGS-84: 55°30'45"N 37°15'23"E ───────────────────
    private fun parseDegrees(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        // Гибкий паттерн: допускает разные символы для °, ', "
        val degRe = Regex(
            """(\d{1,3})\s*[°oO]\s*(\d{1,2})\s*['`]\s*([\d.]+)[^NSEW]{0,3}([NSns])""" +
            """\D{0,10}""" +
            """(\d{1,3})\s*[°oO]\s*(\d{1,2})\s*['`]\s*([\d.]+)[^NSEW]{0,3}([EWew])"""
        )
        for (m in degRe.findAll(text)) {
            val lat = dms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) ?: continue
            val lon = dms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8]) ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            results.add(ParsedCoord("Точка", 0.0, 0.0, 0, isWgs84 = true, lat = lat, lon = lon, system = "WGS-84"))
        }
        return results
    }

    // ── Вспомогательные ─────────────────────────────────────────

    private fun parseNumStr(s: String): Double? {
        return s.trim()
            .replace(Regex("""[ ,](?=\d{3})"""), "")
            .replace(",", ".")
            .toDoubleOrNull()
    }

    private fun extractName(line: String, xPos: Int): String {
        val before = if (xPos > 0) line.substring(0, xPos).trim() else line
        return before.split(Regex("""\s+"""))
            .filter { it.isNotEmpty() && it.any { c -> c.isLetter() } }
            .lastOrNull()
            ?: before.split(Regex("""\s+""")).firstOrNull { it.isNotEmpty() }
            ?: "Точка"
    }

    private fun dms(d: String, m: String, s: String, hem: String): Double? {
        val dv = d.toDoubleOrNull() ?: return null
        val mv = m.toDoubleOrNull() ?: return null
        val sv = s.toDoubleOrNull() ?: return null
        var v = dv + mv / 60.0 + sv / 3600.0
        if (hem.uppercase() in listOf("S", "W")) v = -v
        return v
    }

    // СК-42 X: 7 цифр, 1 000 000 — 9 999 999
    private fun isValidX(v: Double) = v in 1_000_000.0..9_999_999.0

    // СК-42 Y с префиксом зоны: 8 цифр, 1 000 000 — 32 999 999
    private fun isValidY(v: Double) = v in 1_000_000.0..32_999_999.0

    private fun zoneOf(y: Double): Int? {
        val z = (y / 1_000_000).toInt()
        return if (z in 1..32) z else null
    }
}
