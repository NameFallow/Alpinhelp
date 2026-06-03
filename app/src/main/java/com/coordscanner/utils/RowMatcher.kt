package com.coordscanner.utils

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MatchedRow(
    val name: String,
    val x: Double,
    val y: Double,
    val zone: Int,
    val isWgs84: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

private data class RowLine(val text: String, val centerY: Int, val height: Int)

object RowMatcher {

    private const val TAG = "RowMatcher"

    // Доля площади bbox внутри зоны, при которой строка считается принадлежащей зоне
    private const val MIN_OVERLAP_RATIO = 0.5f

    // Запасные пороги (px) для случая, когда оценить высоту строки нельзя.
    private const val FALLBACK_MERGE_PX = 30
    private const val FALLBACK_MATCH_PX = 80

    /**
     * Сопоставляет строки из трёх выделенных областей (название, X, Y).
     *
     * @param nameViewRect  прямоугольник колонки "Название" в координатах View
     * @param xViewRect     прямоугольник колонки X в координатах View
     * @param yViewRect     прямоугольник колонки Y в координатах View
     * @param imageViewRect прямоугольник отображаемого изображения в View (для масштабирования)
     */
    fun match(
        visionText: Text,
        bitmapWidth: Int,
        bitmapHeight: Int,
        nameViewRect: RectF,
        xViewRect: RectF,
        yViewRect: RectF,
        imageViewRect: RectF
    ): List<MatchedRow> {
        val nameImgRect = viewRectToImage(nameViewRect, imageViewRect, bitmapWidth, bitmapHeight)
        val xImgRect    = viewRectToImage(xViewRect,    imageViewRect, bitmapWidth, bitmapHeight)
        val yImgRect    = viewRectToImage(yViewRect,    imageViewRect, bitmapWidth, bitmapHeight)

        val nameLines = extractLines(visionText, nameImgRect, normalizeAsDigits = false, fixCyrillic = true)
        val xLines    = extractLines(visionText, xImgRect,    normalizeAsDigits = true,  fixCyrillic = false)
        val yLines    = extractLines(visionText, yImgRect,    normalizeAsDigits = true,  fixCyrillic = false)

        Log.d(TAG, "Строк в области: название=${nameLines.size} x=${xLines.size} y=${yLines.size}")

        return matchRows(nameLines, xLines, yLines)
    }

    /**
     * Матчит строки из НЕСКОЛЬКИХ зон X и НЕСКОЛЬКИХ зон Y.
     * Строки из всех X-зон объединяются; аналогично для Y.
     * Имена не извлекаются (все точки будут называться "Точка").
     */
    fun matchMultiZone(
        visionText: Text,
        bitmapWidth: Int,
        bitmapHeight: Int,
        xViewRects: List<android.graphics.RectF>,
        yViewRects: List<android.graphics.RectF>,
        imageViewRect: android.graphics.RectF
    ): List<MatchedRow> {
        val xImgRects = xViewRects.map { viewRectToImage(it, imageViewRect, bitmapWidth, bitmapHeight) }
        val yImgRects = yViewRects.map { viewRectToImage(it, imageViewRect, bitmapWidth, bitmapHeight) }

        val xRawLines = xImgRects.flatMap {
            extractLines(visionText, it, normalizeAsDigits = true, fixCyrillic = false)
        }.sortedBy { it.centerY }
        val yRawLines = yImgRects.flatMap {
            extractLines(visionText, it, normalizeAsDigits = true, fixCyrillic = false)
        }.sortedBy { it.centerY }

        val xLines = mergeRows(xRawLines, mergeThreshold(xRawLines))
        val yLines = mergeRows(yRawLines, mergeThreshold(yRawLines))

        Log.d(TAG, "matchMultiZone: x=${xLines.size} y=${yLines.size}")
        return matchRows(emptyList(), xLines, yLines)
    }

    // ── Вспомогательные функции ───────────────────────────────

    // Переводит прямоугольник из пространства View в пространство пикселей изображения
    private fun viewRectToImage(
        viewRect: RectF,
        imageViewRect: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Rect {
        if (imageViewRect.isEmpty) return Rect(0, 0, bitmapWidth, bitmapHeight)
        val scaleX = bitmapWidth.toFloat()  / imageViewRect.width()
        val scaleY = bitmapHeight.toFloat() / imageViewRect.height()
        return Rect(
            ((viewRect.left  - imageViewRect.left) * scaleX).toInt().coerceAtLeast(0),
            ((viewRect.top   - imageViewRect.top)  * scaleY).toInt().coerceAtLeast(0),
            ((viewRect.right - imageViewRect.left) * scaleX).toInt().coerceAtMost(bitmapWidth),
            ((viewRect.bottom- imageViewRect.top)  * scaleY).toInt().coerceAtMost(bitmapHeight)
        )
    }

    // Извлекает строки, чей bbox пересекается с зоной не меньше чем на MIN_OVERLAP_RATIO площади.
    // Для числовых зон (normalizeAsDigits=true) применяется коррекция OCR-цифр.
    private fun extractLines(
        text: Text,
        boundRect: Rect,
        normalizeAsDigits: Boolean,
        fixCyrillic: Boolean
    ): List<RowLine> {
        val lines = mutableListOf<RowLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (!overlapsEnough(box, boundRect)) continue
                val raw = line.text.trim()
                val processed = when {
                    normalizeAsDigits -> OcrParser.normalizeDigits(raw)
                    fixCyrillic       -> OcrParser.fixCyrillicName(raw)
                    else              -> raw
                }
                lines += RowLine(processed, box.exactCenterY().toInt(), box.height())
            }
        }
        val sorted = lines.sortedBy { it.centerY }
        return mergeRows(sorted, mergeThreshold(sorted))
    }

    // true, если ≥MIN_OVERLAP_RATIO площади bbox строки лежит внутри bound
    private fun overlapsEnough(box: Rect, bound: Rect): Boolean {
        val ix = max(0, min(box.right, bound.right) - max(box.left, bound.left))
        val iy = max(0, min(box.bottom, bound.bottom) - max(box.top, bound.top))
        val inter = ix.toLong() * iy.toLong()
        val area  = box.width().toLong() * box.height().toLong()
        if (area <= 0L) return false
        return inter * 2L >= area  // inter / area >= 0.5
    }

    // Порог объединения строк = ~60% медианной высоты bbox.
    // Это адаптируется и к крупным сканам, и к мелким фото.
    private fun mergeThreshold(lines: List<RowLine>): Int {
        if (lines.isEmpty()) return FALLBACK_MERGE_PX
        val heights = lines.map { it.height }.sorted()
        val median = heights[heights.size / 2]
        return if (median > 0) (median * 0.6f).toInt().coerceAtLeast(8) else FALLBACK_MERGE_PX
    }

    // Порог матчинга X↔Y по centerY = ~150% медианной высоты bbox.
    private fun matchThreshold(xLines: List<RowLine>, yLines: List<RowLine>): Int {
        val combined = xLines + yLines
        if (combined.isEmpty()) return FALLBACK_MATCH_PX
        val heights = combined.map { it.height }.sorted()
        val median = heights[heights.size / 2]
        return if (median > 0) (median * 1.5f).toInt().coerceAtLeast(20) else FALLBACK_MATCH_PX
    }

    // Объединяет строки, разбитые OCR, но физически расположенные в одной строке таблицы
    private fun mergeRows(lines: List<RowLine>, threshold: Int): List<RowLine> {
        if (lines.isEmpty()) return emptyList()
        val merged = mutableListOf<RowLine>()
        var cur = lines[0]
        for (i in 1 until lines.size) {
            val next = lines[i]
            if (next.centerY - cur.centerY <= threshold) {
                cur = RowLine(
                    text     = "${cur.text} ${next.text}",
                    centerY  = (cur.centerY + next.centerY) / 2,
                    height   = max(cur.height, next.height)
                )
            } else {
                merged += cur
                cur = next
            }
        }
        merged += cur
        return merged
    }

    private fun matchRows(
        nameLines: List<RowLine>,
        xLines: List<RowLine>,
        yLines: List<RowLine>
    ): List<MatchedRow> {
        val results = mutableListOf<MatchedRow>()
        val matchPx = matchThreshold(xLines, yLines)
        val namePx  = matchPx * 2

        // Биекция X↔Y: каждый Y и каждое имя используется максимум один раз.
        // Идём по X в порядке возрастания centerY; для каждого X — ближайший
        // не занятый Y/имя.
        val usedY = BooleanArray(yLines.size)
        val usedName = BooleanArray(nameLines.size)

        for (xRow in xLines.sortedBy { it.centerY }) {
            val xVal = parseNumber(xRow.text) ?: continue
            if (!isValidX(xVal)) continue

            var bestYIdx = -1
            var bestYDist = Int.MAX_VALUE
            for (i in yLines.indices) {
                if (usedY[i]) continue
                val d = abs(yLines[i].centerY - xRow.centerY)
                if (d < bestYDist) { bestYDist = d; bestYIdx = i }
            }
            if (bestYIdx < 0 || bestYDist > matchPx) continue
            val yRow = yLines[bestYIdx]
            val yVal = parseNumber(yRow.text) ?: continue
            if (!isValidY(yVal)) continue
            val zone = zoneOf(yVal) ?: continue
            usedY[bestYIdx] = true

            var bestNameIdx = -1
            var bestNameDist = Int.MAX_VALUE
            for (i in nameLines.indices) {
                if (usedName[i]) continue
                val d = abs(nameLines[i].centerY - xRow.centerY)
                if (d < bestNameDist) { bestNameDist = d; bestNameIdx = i }
            }
            val name = if (bestNameIdx >= 0 && bestNameDist <= namePx) {
                usedName[bestNameIdx] = true
                nameLines[bestNameIdx].text.trim().ifEmpty { "Точка" }
            } else "Точка"

            Log.d(TAG, "Строка: '$name'  x=${xVal.toLong()}  y=${yVal.toLong()}  зона=$zone")
            results += MatchedRow(name, xVal, yVal, zone)
        }
        return results
    }

    // Парсер чисел: сначала убираем все пробелы из строки, затем берём первую
    // последовательность из 6+ цифр (потенциальная X- или Y-координата СК-42).
    // Это устойчиво и к одиночному пробелу-разделителю, и к слипшимся числам.
    private val DIGIT_RUN = Regex("""\d{6,}(?:[.,]\d+)?""")

    private fun parseNumber(text: String): Double? {
        val compact = text.replace(" ", "").replace(" ", "")
        val m = DIGIT_RUN.find(compact) ?: return null
        return m.value.replace(",", ".").toDoubleOrNull()
    }

    private fun isValidX(v: Double) = v in 1_000_000.0..9_999_999.0
    private fun isValidY(v: Double) = v in 1_000_000.0..32_999_999.0

    private fun zoneOf(y: Double): Int? {
        val z = (y / 1_000_000).toInt()
        return if (z in 1..32) z else null
    }
}
