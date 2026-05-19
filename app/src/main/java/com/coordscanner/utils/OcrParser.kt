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

    // Matches typical point identifiers: ПТ-1, Т12, АГ-54, P.3, etc.
    // Starts with a letter, allows digits/letters, optional separator + more
    private val NAME_RE = Regex(
        """[А-Яа-яЁёA-Za-z][А-Яа-яЁёA-Za-z\d]*(?:[-./][А-Яа-яЁёA-Za-z\d]+)*"""
    )

    // Words to skip — unlikely to be a point name
    private val SKIP_WORDS = setOf(
        "X", "Y", "x", "y", "Х", "У", "х", "у",
        "зона", "zone", "СК", "WGS", "GPS"
    )

    fun hasCoordinatePattern(text: String): Boolean =
        Regex("""\d{6,}""").containsMatchIn(text) ||
        Regex("""[XxХх]\s*[=:\-]""").containsMatchIn(text) ||
        Regex("""\d\s*°""").containsMatchIn(text)

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

    // ── Number extraction ────────────────────────────────────
    // \d{4,} FIRST — prevents "5358337" from being matched as "535"+"833"+"7"
    // {2,} requires ≥2 groups of 3 → number ≥7 digits with space-separators
    private val NUM_RE = Regex(
        """\d{4,}(?:[.,]\d+)?|\d{1,3}(?:[ ,]\d{3}){2,}(?:[.,]\d+)?"""
    )

    private data class NumToken(val value: Double, val pos: Int)

    private fun extractNumbers(line: String): List<NumToken> {
        val result = mutableListOf<NumToken>()
        for (m in NUM_RE.findAll(line)) {
            val v = m.value
                .replace(Regex("""[ ,](?=\d{3})"""), "")
                .replace(",", ".")
                .toDoubleOrNull() ?: continue
            if (v >= 100_000.0) result.add(NumToken(v, m.range.first))
        }
        return result
    }

    // ── X=... Y=... labeled pairs ────────────────────────────
    private fun parseLabeledXY(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        val xRe = Regex("""[XxХх]\s*[=:\-]?\s*(\d[\d ,.]{4,14})""")
        val yRe = Regex("""[YyУуYу]\s*[=:\-]?\s*(\d[\d ,.]{4,14})""")

        val xs = xRe.findAll(text).toList()
        val ys = yRe.findAll(text).toList()

        for (xm in xs) {
            val x = cleanNum(xm.groupValues[1]) ?: continue
            if (!isValidX(x)) continue

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

    // ── Space-separated tables ───────────────────────────────
    private fun parseLines(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        val lines = text.lines()

        for ((idx, line) in lines.withIndex()) {
            val nums = extractNumbers(line)

            // X and Y on same line
            for (i in 0 until nums.size - 1) {
                val x = nums[i].value; val y = nums[i + 1].value
                if (isValidX(x) && isValidY(y)) {
                    val zone = zoneOf(y) ?: continue
                    val name = nameOnLine(line, nums[i].pos)
                        .ifEmpty { nameFromPrevLine(lines, idx) }
                    Log.d(TAG, "Line: '$name' x=$x y=$y zone=$zone")
                    results.add(ParsedCoord(name, x, y, zone))
                    break
                }
            }

            // X on current line, Y on next line
            if (idx < lines.size - 1) {
                val nextNums = extractNumbers(lines[idx + 1])
                val x = nums.firstOrNull { isValidX(it.value) }?.value
                val y = nextNums.firstOrNull { isValidY(it.value) }?.value
                if (x != null && y != null) {
                    val zone = zoneOf(y) ?: continue
                    val name = nameOnLine(line, 0)
                        .ifEmpty { nameFromPrevLine(lines, idx) }
                    Log.d(TAG, "MultiLine: '$name' x=$x y=$y zone=$zone")
                    results.add(ParsedCoord(name, x, y, zone))
                }
            }
        }
        return results
    }

    // ── Degrees WGS-84 ───────────────────────────────────────
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
                results.add(ParsedCoord("Точка", 0.0, 0.0, 0, isWgs84 = true,
                    lat = lat, lon = lon, system = "WGS-84"))
        }
        return results
    }

    // ── Name extraction ──────────────────────────────────────

    // Find identifier on the same line, before xPos.
    // If xPos == 0, searches the whole line.
    private fun nameOnLine(line: String, xPos: Int): String {
        val segment = if (xPos > 0) line.substring(0, xPos) else line
        return NAME_RE.findAll(segment.trim())
            .toList()
            .lastOrNull { it.value !in SKIP_WORDS }
            ?.value ?: ""
    }

    // Search up to 5 lines before lineIdx for an identifier-like line
    private fun nameFromPrevLine(lines: List<String>, lineIdx: Int): String {
        for (i in (lineIdx - 1) downTo maxOf(0, lineIdx - 5)) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            // Skip lines that are themselves coordinate lines
            if (Regex("""\d{6,}""").containsMatchIn(line)) continue
            val name = NAME_RE.findAll(line)
                .toList()
                .lastOrNull { it.value !in SKIP_WORDS }
                ?.value
            if (!name.isNullOrEmpty()) return name
        }
        return "Точка"
    }

    // For labeled X= format: look for identifier in all text before the X label
    private fun nameBeforeMatch(text: String, xPos: Int): String {
        val before = text.substring(0, xPos)
        val lines = before.lines()

        // Name on the same line as X (text before X= on that line)
        val sameLine = lines.lastOrNull()?.trim() ?: ""
        val nameOnSameLine = NAME_RE.findAll(sameLine)
            .toList()
            .lastOrNull { it.value !in SKIP_WORDS }
            ?.value
        if (!nameOnSameLine.isNullOrEmpty()) return nameOnSameLine

        // Look back up to 5 lines for a label line
        val lineCount = lines.size
        for (i in (lineCount - 2) downTo maxOf(0, lineCount - 6)) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            if (Regex("""\d{6,}""").containsMatchIn(line)) continue
            val name = NAME_RE.findAll(line)
                .toList()
                .lastOrNull { it.value !in SKIP_WORDS }
                ?.value
            if (!name.isNullOrEmpty()) return name
        }

        return "Точка"
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun cleanNum(s: String): Double? =
        s.trim()
            .replace(Regex("""[ ,](?=\d{3})"""), "")
            .replace(",", ".")
            .toDoubleOrNull()

    private fun dms(d: String, m: String, s: String, h: String): Double? {
        val v = (d.toDoubleOrNull() ?: return null) +
                (m.toDoubleOrNull() ?: return null) / 60.0 +
                (s.toDoubleOrNull() ?: return null) / 3600.0
        return if (h.uppercase() in listOf("S", "W")) -v else v
    }

    private fun isValidX(v: Double) = v in 1_000_000.0..9_999_999.0
    private fun isValidY(v: Double) = v in 1_000_000.0..32_999_999.0

    private fun zoneOf(y: Double): Int? {
        val z = (y / 1_000_000).toInt()
        return if (z in 1..32) z else null
    }
}
