package com.coordscanner.utils

import android.util.Log
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

object WgsParser {

    private const val TAG = "WgsParser"

    // Широта: 44.0–50.9 (Донбас / восток Украины)
    private const val LAT_MIN = 44.0; private const val LAT_MAX = 50.9
    // Долгота: 34.0–39.9
    private const val LON_MIN = 34.0; private const val LON_MAX = 39.9

    // Число с 2–3 цифрами до точки и 1–8 после. Не может быть частью длинного числа.
    private val COORD_RE = Regex("""(?<!\d)\d{2,3}\.\d{1,8}(?!\d)""")
    // Слова для извлечения имени точки (кириллица или латиница, ≥2 символов)
    private val WORD_RE  = Regex("""[А-Яа-яЁёA-Za-z][А-Яа-яЁёA-Za-z\d\-]{1,}""")

    /**
     * Режим «Текст»: координаты разбросаны по тексту.
     * Алгоритм: находим первое число в диапазоне [44, 50.9] → это широта.
     * Следующее число в диапазоне [34, 39.9] → это долгота. Образуют пару.
     * Текст перед широтой → имя точки.
     */
    fun parseTextMode(rawText: String): List<MatchedRow> {
        val tokens = COORD_RE.findAll(rawText).toList()
        val results = mutableListOf<MatchedRow>()
        var i = 0
        var prevEnd = 0

        while (i < tokens.size) {
            val lat = tokens[i].value.toDoubleOrNull() ?: run { i++; continue }
            if (lat !in LAT_MIN..LAT_MAX) { i++; continue }

            // Ищем долготу в следующих до 5 числах
            var lonIdx = -1
            for (j in (i + 1) until minOf(i + 6, tokens.size)) {
                val v = tokens[j].value.toDoubleOrNull() ?: continue
                if (v in LON_MIN..LON_MAX) { lonIdx = j; break }
            }
            if (lonIdx == -1) { i++; continue }

            val lon    = tokens[lonIdx].value.toDouble()
            val before = rawText.substring(prevEnd, tokens[i].range.first)
            val name   = extractName(before, results.size + 1)

            Log.d(TAG, "Text: '$name'  lat=$lat lon=$lon")
            results += MatchedRow(name, 0.0, 0.0, 0, isWgs84 = true, lat = lat, lon = lon)
            prevEnd = tokens[lonIdx].range.last + 1
            i = lonIdx + 1
        }
        return results
    }

    /**
     * Режим «Таблица»: два столбика — левый = широта, правый = долгота.
     * Использует bbox-позиции ML Kit для разделения столбцов.
     * Строки сопоставляются по вертикальной позиции (centerY).
     */
    fun parseTableMode(visionText: Text, bitmapHeight: Int): List<MatchedRow> {
        data class Num(val value: Double, val cx: Float, val cy: Float)

        val all = mutableListOf<Num>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cy  = box.exactCenterY()
                val cx  = box.exactCenterX()
                for (m in COORD_RE.findAll(line.text)) {
                    val v = m.value.toDoubleOrNull() ?: continue
                    if (v in LAT_MIN..LAT_MAX || v in LON_MIN..LON_MAX) {
                        all += Num(v, cx, cy)
                    }
                }
            }
        }

        val lats = all.filter { it.value in LAT_MIN..LAT_MAX }.sortedBy { it.cy }
        val lons = all.filter { it.value in LON_MIN..LON_MAX }.sortedBy { it.cy }
        Log.d(TAG, "Table: ${lats.size} широт, ${lons.size} долгот")

        // Допустимое расстояние по Y между широтой и долготой одной строки таблицы
        val maxRowGap = bitmapHeight * 0.06f

        val results   = mutableListOf<MatchedRow>()
        val usedLons  = mutableSetOf<Int>()
        var idx = 1

        for (lat in lats) {
            val lonIdx = lons.indices
                .filter  { it !in usedLons }
                .minByOrNull { abs(lons[it].cy - lat.cy) } ?: continue
            if (abs(lons[lonIdx].cy - lat.cy) > maxRowGap) continue
            usedLons += lonIdx
            Log.d(TAG, "Table row: lat=${lat.value} lon=${lons[lonIdx].value}")
            results += MatchedRow("Point_${idx++}", 0.0, 0.0, 0,
                isWgs84 = true, lat = lat.value, lon = lons[lonIdx].value)
        }
        return results
    }

    // Извлекает имя из текстового фрагмента перед координатой.
    // Возвращает последние ≤3 слова; если слов нет — "Point_N".
    private fun extractName(text: String, idx: Int): String {
        val words = WORD_RE.findAll(text)
            .map { it.value }
            .filter { it.length >= 2 }
            .toList()
        return if (words.isEmpty()) "Point_$idx" else words.takeLast(3).joinToString(" ")
    }
}
