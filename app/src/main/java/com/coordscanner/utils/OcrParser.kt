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
    val system: String = "СК-42",
    // All text tokens found before the coordinates on the same line.
    // Used to let the user pick which column to use as the point name.
    val textCandidates: List<String> = emptyList()
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

    // Returns the first meaningful text line that appears before any coordinate numbers.
    // Used to pre-fill the batch point name from the table title (e.g. "Станция помех").
    fun extractTableTitle(text: String): String {
        val lines = text.lines()
        val firstCoordIdx = lines.indexOfFirst { extractNumbers(it).isNotEmpty() }
        if (firstCoordIdx <= 0) return ""
        for (i in (firstCoordIdx - 1) downTo 0) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            if (Regex("""\d{5,}""").containsMatchIn(line)) continue
            if (!line.any { it.isLetter() }) continue
            if (line.length < 3) continue
            return line
        }
        return ""
    }

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
        // Column-format fallback: all X values first, then all Y values.
        // Also trigger when parseLines found suspiciously few SK-42 pairs relative
        // to the total number of large numbers in the text (e.g. column-by-column OCR).
        val colBlocks = parseColumnBlocks(rawText)
        val sk42FromLines = results.count { !it.isWgs84 }
        if (colBlocks.size > sk42FromLines) {
            results.removeAll { !it.isWgs84 }
            results.addAll(colBlocks)
        }
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
        // OCR часто путает O/0, l/1, S/5 и пр. внутри длинных чисел.
        // Сначала пробуем строку как есть, затем — с лёгкой нормализацией цифр,
        // сохраняя позиции исходных вхождений.
        val sources = listOf(line, line.let { src ->
            buildString(src.length) {
                for (ch in src) append(
                    when (ch) {
                        'O', 'o', 'О', 'о' -> '0'
                        'l', 'I', '|'      -> '1'
                        'S'                -> '5'
                        'B'                -> '8'
                        else               -> ch
                    }
                )
            }
        })
        val seen = HashSet<Int>()
        for (src in sources) {
            for (m in NUM_RE.findAll(src)) {
                if (!seen.add(m.range.first)) continue
                val v = m.value
                    .replace(Regex("""[ ,](?=\d{3})"""), "")
                    .replace(",", ".")
                    .toDoubleOrNull() ?: continue
                if (v >= 100_000.0) result.add(NumToken(v, m.range.first))
            }
        }
        return result.sortedBy { it.pos }
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
    // Стратегия: X — первое валидное число в строке, Y — следующее.
    // Это работает и для зон 4-5 (где Y < X), и для зон 6-13 (где Y > X).
    // Если на строке доминирует одна зона Y (все остальные строки в файле
    // дают такую же зону), это используем как мягкое подтверждение.
    private fun parseLines(text: String): List<ParsedCoord> {
        val results = mutableListOf<ParsedCoord>()
        val lines = text.lines()

        for ((idx, line) in lines.withIndex()) {
            val nums = extractNumbers(line)

            // Берём первую пару (i, j=i+1) где i — кандидат X, j — кандидат Y.
            // Это устойчиво к мусорному большому числу слева (старый код,
            // выбравший по «max gap», там мог промахнуться).
            var bestCoord: ParsedCoord? = null
            for (i in nums.indices) {
                val x = nums[i].value
                if (!isValidX(x)) continue
                val yTok = nums.drop(i + 1).firstOrNull { isValidY(it.value) } ?: continue
                val y = yTok.value
                val zone = zoneOf(y) ?: continue
                // Защита от слитного дубля: если X и Y отличаются меньше чем
                // на 100 м — это явно одно и то же число, прочитанное дважды.
                if (Math.abs(x - y) < 100.0) continue

                val firstNumPos = nums[i].pos
                val textsBefore = NAME_RE.findAll(line.substring(0, firstNumPos))
                    .map { it.value }
                    .filter { it !in SKIP_WORDS && it.length > 1 }
                    .toList()
                val name = textsBefore.lastOrNull() ?: nameFromPrevLine(lines, idx)
                bestCoord = ParsedCoord(name, x, y, zone, textCandidates = textsBefore)
                break
            }
            if (bestCoord != null) {
                Log.d(TAG, "Line: '${bestCoord.name}' x=${bestCoord.x} y=${bestCoord.y} zone=${bestCoord.zone}")
                results.add(bestCoord)
                continue
            }

            // X на текущей строке, Y — на следующей (двухстрочная запись)
            if (idx < lines.size - 1) {
                val nextNums = extractNumbers(lines[idx + 1])
                val x = nums.firstOrNull { isValidX(it.value) }?.value
                val y = nextNums.firstOrNull { isValidY(it.value) }?.value
                if (x != null && y != null) {
                    val zone = zoneOf(y) ?: continue
                    if (Math.abs(x - y) < 100.0) continue
                    val textsBefore = NAME_RE.findAll(line)
                        .map { it.value }
                        .filter { it !in SKIP_WORDS && it.length > 1 }
                        .toList()
                    val name = textsBefore.lastOrNull()
                        ?: nameFromPrevLine(lines, idx)
                    Log.d(TAG, "MultiLine: '$name' x=$x y=$y zone=$zone")
                    results.add(ParsedCoord(name, x, y, zone, textCandidates = textsBefore))
                }
            }
        }
        return results
    }

    // ── Column-format: OCR reads table column-by-column ──────
    // Handles tables with extra leading columns (name codes, old coordinates, etc.)
    // by trying skip=0,1,2,... prefix numbers until a valid [X block][Y block] is found.
    private fun parseColumnBlocks(text: String): List<ParsedCoord> {
        val allNums = mutableListOf<Double>()
        for (line in text.lines()) extractNumbers(line).forEach { allNums.add(it.value) }
        val n = allNums.size
        if (n < 4) return emptyList()

        var best = emptyList<ParsedCoord>()

        for (skip in 0..(n / 3)) {
            val rem = n - skip
            if (rem < 4) continue
            // Try both floor and ceil split so an odd remainder (one garbled number
            // dropped by OCR) doesn't silently lose a whole row.
            val halves = if (rem % 2 == 0) listOf(rem / 2) else listOf(rem / 2, rem / 2 + 1)
            for (half in halves) {
                val end = skip + half * 2
                if (end > n) continue
                val xCands = allNums.subList(skip, skip + half)
                val yCands = allNums.subList(skip + half, end)

                if (!xCands.all { isValidX(it) }) continue
                if (!yCands.all { isValidY(it) }) continue

                val zoneList = yCands.mapNotNull { zoneOf(it) }
                if (zoneList.size != yCands.size) continue
                val zone = zoneList[0]
                if (zoneList.any { it != zone }) continue
                if (Math.abs(xCands.average() - yCands.average()) < 300_000.0) continue

                if (half > best.size) {
                    Log.d(TAG, "ColumnBlocks: skip=$skip ${half}p zone=$zone")
                    best = xCands.zip(yCands).mapIndexed { i, (x, y) ->
                        ParsedCoord("Точка ${i + 1}", x, y, zone)
                    }
                }
            }
        }
        return best
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

    // ── Постобработка чисел ──────────────────────────────────

    // ML Kit часто путает в числовых полях буквы и цифры.
    // Применять ТОЛЬКО к строкам X/Y, никогда к названиям.
    // Помимо подмены оставляем только цифры, пробелы, запятые и точки —
    // отбрасываем единицы измерения и прочий мусор после числа.
    fun normalizeDigits(text: String): String {
        val map = mapOf(
            'O' to '0', 'o' to '0', 'О' to '0', 'о' to '0', 'Q' to '0', 'D' to '0',
            'I' to '1', 'l' to '1', '|' to '1', 'i' to '1', 'L' to '1',
            'Z' to '2', 'z' to '2',
            'S' to '5', 's' to '5',
            'G' to '6', 'b' to '6',
            'T' to '7', 't' to '7',
            'B' to '8',
            'g' to '9', 'q' to '9'
        )
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val r = map[ch] ?: ch
            if (r.isDigit() || r == ' ' || r == ',' || r == '.' || r == ' ') sb.append(r)
        }
        return sb.toString().trim()
    }

    // ── Постобработка кириллицы ──────────────────────────────

    // ML Kit в смешанном режиме путает латинские и кириллические буквы.
    // Применять ТОЛЬКО к текстовым названиям, не к числовым полям.
    fun fixCyrillicName(text: String): String {
        val map = mapOf(
            'C' to 'С', 'c' to 'с',
            'T' to 'Т', 't' to 'т',
            'A' to 'А', 'a' to 'а',
            'I' to 'И', 'i' to 'и',
            'O' to 'О', 'o' to 'о',
            'P' to 'Р', 'p' to 'р',
            'H' to 'Н',
            'E' to 'Е', 'e' to 'е',
            'B' to 'В',
            'M' to 'М', 'm' to 'м',
            'K' to 'К', 'k' to 'к',
            'X' to 'Х', 'x' to 'х',
            // n / п — оба имеют форму дуги/арки
            'n' to 'п',
            // y / у — визуально идентичны во многих шрифтах
            'Y' to 'У', 'y' to 'у',
        )
        val fixed = text.map { map[it] ?: it }.joinToString("").trim()
        // Sentence-case только если весь текст — заглавные буквы (≥4 буквы).
        // Коды типа «ПТ-12» или «АГ-54» остаются без изменений.
        val letters = fixed.filter { it.isLetter() }
        return if (letters.length >= 4 && letters.all { it.isUpperCase() }) {
            fixed.lowercase().replaceFirstChar { it.uppercaseChar() }
        } else {
            fixed.replaceFirstChar { it.uppercaseChar() }
        }
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
