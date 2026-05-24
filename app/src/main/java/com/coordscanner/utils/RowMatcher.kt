package com.coordscanner.utils

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

data class MatchedRow(
    val name: String,
    val x: Double,
    val y: Double,
    val zone: Int,
    val isWgs84: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

private data class RowLine(val text: String, val centerY: Int)

object RowMatcher {

    private const val TAG = "RowMatcher"

    // Строки с разницей centerY меньше этого значения — одна строка таблицы
    private const val MERGE_PX = 30

    // Максимальное расстояние по Y между колонками для матчинга одной строки
    private const val MATCH_PX = 80

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

        val nameLines = extractLines(visionText, nameImgRect, fixCyrillic = true)
        val xLines    = extractLines(visionText, xImgRect,    fixCyrillic = false)
        val yLines    = extractLines(visionText, yImgRect,    fixCyrillic = false)

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

        val xLines = mergeRows(
            xImgRects.flatMap { extractLines(visionText, it, fixCyrillic = false) }
                     .sortedBy { it.centerY }
        )
        val yLines = mergeRows(
            yImgRects.flatMap { extractLines(visionText, it, fixCyrillic = false) }
                     .sortedBy { it.centerY }
        )

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

    // Извлекает строки, центр которых попадает в заданный прямоугольник изображения
    private fun extractLines(text: Text, boundRect: Rect, fixCyrillic: Boolean): List<RowLine> {
        val lines = mutableListOf<RowLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cx = box.exactCenterX().toInt()
                val cy = box.exactCenterY().toInt()
                if (boundRect.contains(cx, cy)) {
                    val raw = line.text.trim()
                    val processed = if (fixCyrillic) OcrParser.fixCyrillicName(raw) else raw
                    lines += RowLine(processed, cy)
                }
            }
        }
        return mergeRows(lines.sortedBy { it.centerY })
    }

    // Объединяет строки, разбитые OCR, но физически расположенные в одной строке таблицы
    private fun mergeRows(lines: List<RowLine>): List<RowLine> {
        if (lines.isEmpty()) return emptyList()
        val merged = mutableListOf<RowLine>()
        var cur = lines[0]
        for (i in 1 until lines.size) {
            val next = lines[i]
            if (next.centerY - cur.centerY <= MERGE_PX) {
                cur = RowLine("${cur.text} ${next.text}", (cur.centerY + next.centerY) / 2)
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
        for (xRow in xLines) {
            val xVal = parseNumber(xRow.text) ?: continue
            if (!isValidX(xVal)) continue

            val yRow = yLines
                .minByOrNull { abs(it.centerY - xRow.centerY) }
                ?.takeIf { abs(it.centerY - xRow.centerY) <= MATCH_PX } ?: continue
            val yVal = parseNumber(yRow.text) ?: continue
            if (!isValidY(yVal)) continue
            val zone = zoneOf(yVal) ?: continue

            val nameRow = nameLines
                .minByOrNull { abs(it.centerY - xRow.centerY) }
                ?.takeIf { abs(it.centerY - xRow.centerY) <= MATCH_PX * 2 }
            val name = nameRow?.text?.trim()?.ifEmpty { null } ?: "Точка"

            Log.d(TAG, "Строка: '$name'  x=${xVal.toLong()}  y=${yVal.toLong()}  зона=$zone")
            results += MatchedRow(name, xVal, yVal, zone)
        }
        return results
    }

    private val NUM_RE = Regex("""\d{4,}(?:[.,]\d+)?|\d{1,3}(?:[ ,]\d{3}){2,}(?:[.,]\d+)?""")

    private fun parseNumber(text: String): Double? =
        NUM_RE.findAll(text)
            .mapNotNull {
                it.value
                    .replace(Regex("""[ ,](?=\d{3})"""), "")
                    .replace(",", ".")
                    .toDoubleOrNull()
            }
            .firstOrNull { it >= 100_000.0 }

    private fun isValidX(v: Double) = v in 1_000_000.0..9_999_999.0
    private fun isValidY(v: Double) = v in 1_000_000.0..32_999_999.0

    private fun zoneOf(y: Double): Int? {
        val z = (y / 1_000_000).toInt()
        return if (z in 1..32) z else null
    }
}
