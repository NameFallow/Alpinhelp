package com.coordscanner.utils

import android.util.Log
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

data class MatchedRow(val name: String, val x: Double, val y: Double, val zone: Int)

private data class RowLine(val text: String, val centerY: Int)

object RowMatcher {

    private const val TAG = "RowMatcher"

    // Строки с разницей centerY меньше этого значения считаются одной строкой таблицы
    private const val MERGE_PX = 30

    // Максимальное расстояние по Y между колонками одной строки
    private const val MATCH_PX = 80

    fun match(
        visionText: Text,
        bitmapWidth: Int,
        nameFraction: Float,
        xFraction: Float,
        yFraction: Float,
        stripWidthFraction: Float = 0.15f
    ): List<MatchedRow> {
        val half = (bitmapWidth * stripWidthFraction / 2f).toInt()
        val nameCx = (nameFraction * bitmapWidth).toInt()
        val xCx    = (xFraction    * bitmapWidth).toInt()
        val yCx    = (yFraction    * bitmapWidth).toInt()

        val nameLines = extractLines(visionText, nameCx, half)
        val xLines    = extractLines(visionText, xCx, half)
        val yLines    = extractLines(visionText, yCx, half)

        Log.d(TAG, "Строк по колонкам: название=${nameLines.size} x=${xLines.size} y=${yLines.size}")

        return matchRows(nameLines, xLines, yLines)
    }

    private fun extractLines(text: Text, centerX: Int, half: Int): List<RowLine> {
        val xMin = centerX - half
        val xMax = centerX + half
        val lines = mutableListOf<RowLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (box.exactCenterX().toInt() in xMin..xMax) {
                    lines += RowLine(line.text.trim(), box.exactCenterY().toInt())
                }
            }
        }
        return mergeRows(lines.sortedBy { it.centerY })
    }

    // Объединяет строки, которые OCR разбил на несколько, но физически это одна строка таблицы
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

            // Ищем ближайшую строку Y в пределах порога
            val yRow = yLines
                .minByOrNull { abs(it.centerY - xRow.centerY) }
                ?.takeIf { abs(it.centerY - xRow.centerY) <= MATCH_PX } ?: continue
            val yVal = parseNumber(yRow.text) ?: continue
            if (!isValidY(yVal)) continue
            val zone = zoneOf(yVal) ?: continue

            // Имя — ближайшая строка из колонки название (порог увеличен, т.к. текст иногда сдвинут)
            val nameRow = nameLines.minByOrNull { abs(it.centerY - xRow.centerY) }
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
