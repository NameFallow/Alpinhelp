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

    // ── Быстрая проверка для overlay-подсветки ──────────────
    fun hasCoordinatePattern(text: String): Boolean =
        Regex("""\d{6,}""").containsMatchIn(text) ||
        Regex("""[XxХх]\s*[=:\-]""").containsMatchIn(text) ||
        Regex("""\d\s*°""").containsMatchIn(text)

    // ── Главный метод ────────────────────────────────────────
    fun parseText(rawText: String): List<ParsedCoord> {
        Log.d(TAG, "=== OCR text ===\n$rawText\n================")
        val results = mutableListOf<ParsedCoord>()
        results += parseDegrees(rawText)
        results += parseLabeledXY(rawText)
        results += parseLines(rawText)
        val unique = results.distinctBy { "${it.x.toLong()}_${it.y.toLong()}_${it.isWgs84}" }
        Log.d(TAG, "Parsed ${unique.size} points")
        return unique
    }

    // ── Извлечь все числа ≥ 100000 из строки ────────────────
    // ВАЖНО: \d{4,} стоит ПЕРВЫМ — иначе "5358337" дробится на куски
    private val NUM_RE = Regex("""\d{4,}(?:[.,]\d+)?|\d{1,3}(?:[ ,]\d{3})+(?:[.,]\d+)?""")

    private data class NumToken(val value: Double, val pos: Int)

    private fun extractNumbers(line: String): List<NumToken> {
        val result = mutableListOf<NumToken>()
        for (m in NUM_RE.findAll(line)) {
            val v = m.value
                .replace(Regex("""[ ,](?=\d{3})"""), "")  // убрать разделители тысяч
                .replace(",", ".")
                .toDoubleOrNull() ?: continue
            if (v >= 100_000.0) result.add(NumToken(v, m.range.first))
        }
        return result
    }

    // ── X=... Y=... (с метками) ──────────────────────────────
    // Поддерживает: X= X: X- X  (пробел), кириллические Х У
    private fun parseLabeledXY(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        // Разрешаем =, :, -, пробел как разделитель после X/Y
        val xRe = Regex("""[XxХх]\s*[=:\-]?\s*(\d[\d ,.]{4,14})""")
        val yRe = Regex("""[YyУуYу]\s*[=:\-]?\s*(\d[\d ,.]{4,14})""")

        val xs = xRe.findAll(text).toList()
        val ys = yRe.findAll(text).toList()

        for (xm in xs) {
            val x = cleanNum(xm.groupValues[1]) ?: continue
            if (!isValidX(x)) continue

            // Ближайший Y (до 200 символов от X)
            val ym = ys.minByOrNull { Math.abs(it.range.first - xm.range.last) }
                       ?.takeIf { Math.abs(it.range.first - xm.range.last) < 200 } ?: continue
            val y = cleanNum(ym.groupValues[1]) ?: continue
            if (!isValidY(y)) continue
            val zone = zoneOf(y) ?: continue

            val name = nameBeforeMatch(text, xm.range.first)
            Log.d(TAG, "Labeled: '$name' x=$x y=$y zone=$zone")
            results.add(ParsedCoord(name, x, y, zone))
        }
        return results
    }

    // ── Парсинг строк: числа через пробел ───────────────────
    private fun parseLines(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        val lines = text.lines()

        for ((idx, line) in lines.withIndex()) {
            val nums = extractNumbers(line)
            // Ищем X, Y пару в одной строке
            for (i in 0 until nums.size - 1) {
                val x = nums[i].value; val y = nums[i + 1].value
                if (isValidX(x) && isValidY(y)) {
                    val zone = zoneOf(y) ?: continue
                    val name = extractName(line, nums[i].pos)
                    Log.d(TAG, "Line: '$name' x=$x y=$y zone=$zone")
                    results.add(ParsedCoord(name, x, y, zone))
                    break
                }
            }
            // X на текущей строке, Y на следующей (или наоборот)
            if (idx < lines.size - 1) {
                val nextNums = extractNumbers(lines[idx + 1])
                val x = nums.firstOrNull { isValidX(it.value) }?.value
                val y = nextNums.firstOrNull { isValidY(it.value) }?.value
                if (x != null && y != null) {
                    val zone = zoneOf(y) ?: continue
                    val name = extractName(line, 0)
                    Log.d(TAG, "MultiLine: '$name' x=$x y=$y zone=$zone")
                    results.add(ParsedCoord(name, x, y, zone))
                }
            }
        }
        return results
    }

    // ── Градусы WGS-84 ───────────────────────────────────────
    private fun parseDegrees(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        val re = Regex(
            """(\d{1,3})\s*[°o]\s*(\d{1,2})\s*['`]\s*([\d.]+)[^NSEWnsew]{0,4}([NSns])""" +
            """\D{0,15}""" +
            """(\d{1,3})\s*[°o]\s*(\d{1,2})\s*['`]\s*([\d.]+)[^NSEWnsew]{0,4}([EWew])"""
        )
        for (m in re.findAll(text)) {
            val lat = dms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) ?: continue
            val lon = dms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8]) ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0)
                results.add(ParsedCoord("Точка", 0.0, 0.0, 0, isWgs84 = true, lat = lat, lon = lon, system = "WGS-84"))
        }
        return results
    }

    // ── Вспомогательные ─────────────────────────────────────

    private fun cleanNum(s: String): Double? =
        s.trim()
         .replace(Regex("""[ ,](?=\d{3})"""), "")
         .replace(",", ".")
         .toDoubleOrNull()

    private fun extractName(line: String, xPos: Int): String {
        val before = if (xPos > 0) line.substring(0, xPos).trim() else ""
        return before.split(Regex("""\s+"""))
            .lastOrNull { it.isNotEmpty() && it.any { c -> c.isLetter() } }
            ?: "Точка"
    }

    private fun nameBeforeMatch(text: String, pos: Int): String {
        val before = text.substring(maxOf(0, pos - 40), pos).trim()
        return before.split(Regex("""\s+"""))
            .lastOrNull { it.isNotEmpty() && it.any { c -> c.isLetter() } }
            ?: "Точка"
    }

    private fun dms(d: String, m: String, s: String, h: String): Double? {
        val v = (d.toDoubleOrNull() ?: return null) +
                (m.toDoubleOrNull() ?: return null) / 60.0 +
                (s.toDoubleOrNull() ?: return null) / 3600.0
        return if (h.uppercase() in listOf("S", "W")) -v else v
    }

    // X: 7 цифр, СК-42 northing (1 000 000 — 9 999 999)
    private fun isValidX(v: Double) = v in 1_000_000.0..9_999_999.0

    // Y: СК-42 easting с зоной (1 000 000 — 32 999 999)
    private fun isValidY(v: Double) = v in 1_000_000.0..32_999_999.0

    private fun zoneOf(y: Double): Int? {
        val z = (y / 1_000_000).toInt()
        return if (z in 1..32) z else null
    }
}
