package com.coordscanner.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.coordscanner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object GeminiScanner {

    private const val TAG = "GeminiScanner"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    private const val MAX_SIDE_PX = 2048
    private const val JPEG_QUALITY = 90

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    enum class WgsMode { TEXT, TABLE }

    class GeminiException(msg: String) : Exception(msg)

    fun apiKey(userKey: String?): String {
        val u = userKey?.trim().orEmpty()
        if (u.isNotEmpty()) return u
        return BuildConfig.GEMINI_API_KEY
    }

    suspend fun scanSk42Columns(
        bitmap: Bitmap,
        nameRect: RectF,
        xRect: RectF,
        yRect: RectF,
        imageViewRect: RectF,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API ключ не задан" }

        val full = cropAndDownscale(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        val base64 = encodeJpegBase64(full)

        val nameFr = normalizeRect(nameRect, imageViewRect)
        val xFr    = normalizeRect(xRect,    imageViewRect)
        val yFr    = normalizeRect(yRect,    imageViewRect)

        val prompt = """
            На изображении — фотография таблицы координат в системе СК-42 (Гаусса-Крюгера).
            Пользователь выделил ТРИ колонки, которые его интересуют. Их области заданы в долях от размера изображения (0..1):

            • колонка с названием/номером точки:  ${rectStr(nameFr)}
            • колонка X (северная):               ${rectStr(xFr)}
            • колонка Y (восточная):              ${rectStr(yFr)}

            Извлеки строки таблицы СТРОГО из этих трёх колонок. Все остальные колонки игнорируй (высота H, площадь, дирекционные углы, расстояния, отдельная колонка «Зона» — её используй только для поля zone, см. ниже).

            Формат координат:
            • Цифр в X и Y может быть от 5 до 8 (например, 632214 или 6 183 420.54). Десятичная часть после точки/запятой опциональна.
            • Y может включать в начале номер зоны (1–2 цифры), тогда Y > 1 000 000 (например 7 413 872 или 17 309 447).
            • Если в таблице есть отдельная колонка «Зона» — возьми зону оттуда (целое 1..60).
            • Если зоны нет нигде явно и Y >= 1 000 000 — извлеки её из первой(-ых) цифры(-р) Y.
            • Если зону определить нельзя — верни zone=0.

            Правила:
            • Возьми ВСЕ строки таблицы, ничего не пропусти и ничего не выдумай.
            • Игнорируй строку заголовка и итоговые/служебные строки.
            • Пробелы между цифрами как разделители тысяч убирай.
            • Если имя пустое или нечитаемо — "Точка".
            • Цифры распознавай аккуратно (0/O, 1/I/l, 5/S, 8/B).
            • Верни числа как числа (не строки): "x": 6183420.54, "y": 7413872.19, "zone": 7.

            Верни ТОЛЬКО JSON-массив без пояснений.
        """.trimIndent()

        val schema = JSONObject().apply {
            put("type", "ARRAY")
            put("items", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().put("type", "STRING"))
                    put("x", JSONObject().put("type", "NUMBER"))
                    put("y", JSONObject().put("type", "NUMBER"))
                    put("zone", JSONObject().put("type", "INTEGER"))
                })
                put("required", JSONArray(listOf("name", "x", "y", "zone")))
            })
        }

        val json = callGemini(apiKey, prompt, base64, schema)
        parseSk42Response(json)
    }

    suspend fun scanWgs(
        bitmap: Bitmap,
        mode: WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API ключ не задан" }

        val cropped = cropAndDownscale(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        val base64 = encodeJpegBase64(cropped)

        val prompt = when (mode) {
            WgsMode.TEXT -> """
                На изображении — фотография с географическими координатами WGS-84 (широта и долгота).
                Координаты могут быть в любом формате: десятичные градусы (55.7558), градусы-минуты-секунды (55°45'20.9"), или двоеточием (55:45:20.9).
                Они могут быть рассыпаны по фото (не таблица).

                Задача: извлеки ВСЕ пары координат, приведи их к десятичным градусам.
                - lat: широта, обычно 40–80 для России, диапазон -90..90.
                - lon: долгота, обычно 20–180 для России, диапазон -180..180.
                - Если рядом есть подпись/название точки — используй его. Если нет — "Точка".

                Верни ТОЛЬКО JSON-массив.
            """.trimIndent()

            WgsMode.TABLE -> """
                На изображении — таблица координат WGS-84 с двумя колонками: левая = широта, правая = долгота.
                Возможен дополнительный левый столбец с названием/номером точки — если он есть, используй.
                Координаты могут быть в десятичных градусах (55.7558) или DMS (55°45'20.9").

                Задача: извлеки ВСЕ строки, приведи координаты к десятичным градусам.
                - Не пропускай строки. Не выдумывай.
                - Игнорируй заголовки и итоги.
                - Если нет имени — "Точка".

                Верни ТОЛЬКО JSON-массив.
            """.trimIndent()
        }

        val schema = JSONObject().apply {
            put("type", "ARRAY")
            put("items", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().put("type", "STRING"))
                    put("lat", JSONObject().put("type", "NUMBER"))
                    put("lon", JSONObject().put("type", "NUMBER"))
                })
                put("required", JSONArray(listOf("name", "lat", "lon")))
            })
        }

        val json = callGemini(apiKey, prompt, base64, schema)
        parseWgsResponse(json)
    }

    // ── HTTP ────────────────────────────────────────────────────

    private fun callGemini(
        apiKey: String,
        prompt: String,
        imageBase64: String,
        responseSchema: JSONObject,
    ): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", imageBase64)
                    }))
                )
            }))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("responseSchema", responseSchema)
                put("temperature", 0.1)
            })
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val short = text.take(400)
                Log.e(TAG, "HTTP ${resp.code}: $short")
                throw GeminiException("HTTP ${resp.code}: $short")
            }
            val root = JSONObject(text)
            val candidates = root.optJSONArray("candidates")
                ?: throw GeminiException("Нет candidates в ответе")
            if (candidates.length() == 0) throw GeminiException("Пустой ответ модели")
            val parts = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                out.append(parts.getJSONObject(i).optString("text", ""))
            }
            return out.toString()
        }
    }

    // ── Парсинг ─────────────────────────────────────────────────

    private fun parseSk42Response(json: String): List<MatchedRow> {
        val arr = parseArray(json) ?: return emptyList()
        val out = mutableListOf<MatchedRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim().ifEmpty { "Точка" }
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            if (x !in 10_000.0..99_999_999.0) continue
            if (y !in 10_000.0..99_999_999.0) continue
            val zoneFromAi = o.optInt("zone", 0)
            val zone = when {
                zoneFromAi in 1..60 -> zoneFromAi
                y >= 1_000_000.0 -> (y / 1_000_000).toInt().let { if (it in 1..60) it else 0 }
                else -> 0
            }
            out += MatchedRow(name = name, x = x, y = y, zone = zone)
        }
        Log.d(TAG, "SK-42 распознано строк: ${out.size}")
        return out
    }

    private fun parseWgsResponse(json: String): List<MatchedRow> {
        val arr = parseArray(json) ?: return emptyList()
        val out = mutableListOf<MatchedRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim().ifEmpty { "Точка" }
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            if (lat !in -90.0..90.0) continue
            if (lon !in -180.0..180.0) continue
            out += MatchedRow(name = name, x = 0.0, y = 0.0, zone = 0, isWgs84 = true, lat = lat, lon = lon)
        }
        Log.d(TAG, "WGS распознано точек: ${out.size}")
        return out
    }

    private fun parseArray(raw: String): JSONArray? {
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

    // ── Кроп и кодирование ──────────────────────────────────────

    private data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun normalizeRect(viewRect: RectF, imageViewRect: RectF): NormRect {
        if (imageViewRect.isEmpty) return NormRect(0f, 0f, 1f, 1f)
        val w = imageViewRect.width()
        val h = imageViewRect.height()
        val l = ((viewRect.left   - imageViewRect.left) / w).coerceIn(0f, 1f)
        val t = ((viewRect.top    - imageViewRect.top)  / h).coerceIn(0f, 1f)
        val r = ((viewRect.right  - imageViewRect.left) / w).coerceIn(0f, 1f)
        val b = ((viewRect.bottom - imageViewRect.top)  / h).coerceIn(0f, 1f)
        return NormRect(l, t, r, b)
    }

    private fun rectStr(n: NormRect): String =
        "left=%.3f top=%.3f right=%.3f bottom=%.3f".format(n.left, n.top, n.right, n.bottom)

    @Suppress("unused")
    private fun unionBitmapRect(
        bitmap: Bitmap,
        viewRects: List<RectF>,
        imageViewRect: RectF,
        paddingFraction: Float,
    ): Rect {
        if (imageViewRect.isEmpty || viewRects.isEmpty()) {
            return Rect(0, 0, bitmap.width, bitmap.height)
        }
        val scaleX = bitmap.width / imageViewRect.width()
        val scaleY = bitmap.height / imageViewRect.height()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (vr in viewRects) {
            l = min(l, (vr.left - imageViewRect.left) * scaleX)
            t = min(t, (vr.top - imageViewRect.top) * scaleY)
            r = max(r, (vr.right - imageViewRect.left) * scaleX)
            b = max(b, (vr.bottom - imageViewRect.top) * scaleY)
        }
        val padX = (r - l) * paddingFraction
        val padY = (b - t) * paddingFraction
        return Rect(
            (l - padX).toInt().coerceAtLeast(0),
            (t - padY).toInt().coerceAtLeast(0),
            (r + padX).toInt().coerceAtMost(bitmap.width),
            (b + padY).toInt().coerceAtMost(bitmap.height),
        )
    }

    private fun cropAndDownscale(bitmap: Bitmap, rect: Rect): Bitmap {
        val w = (rect.right - rect.left).coerceAtLeast(1)
        val h = (rect.bottom - rect.top).coerceAtLeast(1)
        val cropped = if (rect.left == 0 && rect.top == 0 && w == bitmap.width && h == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, rect.left, rect.top, w, h)
        }
        val maxSide = max(cropped.width, cropped.height)
        if (maxSide <= MAX_SIDE_PX) return cropped
        val scale = MAX_SIDE_PX.toFloat() / maxSide
        val nw = (cropped.width * scale).toInt().coerceAtLeast(1)
        val nh = (cropped.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(cropped, nw, nh, true)
    }

    private fun encodeJpegBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
