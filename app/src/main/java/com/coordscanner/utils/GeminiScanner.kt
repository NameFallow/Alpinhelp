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

    // Каскад моделей: при сетевом сбое / 429 / 5xx / пустом ответе на первой —
    // мгновенно дёргаем следующую. Тот же API-ключ для всех.
    private val MODEL_CHAIN = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash",
    )

    private fun endpointFor(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    private const val MAX_SIDE_PX = 2048
    private const val JPEG_QUALITY = 90

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    enum class WgsMode { TEXT, TABLE }

    class GeminiException(msg: String) : Exception(msg)

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

        val nameFr = ScanPrompts.normalizeRect(nameRect, imageViewRect)
        val xFr    = ScanPrompts.normalizeRect(xRect,    imageViewRect)
        val yFr    = ScanPrompts.normalizeRect(yRect,    imageViewRect)

        val prompt = ScanPrompts.sk42Columns(nameFr, xFr, yFr)

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
        ScanParsers.parseSk42Response(json)
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
            WgsMode.TEXT  -> ScanPrompts.wgsText()
            WgsMode.TABLE -> ScanPrompts.wgsTable()
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
        ScanParsers.parseWgsResponse(json)
    }

    /**
     * Свободное сканирование СК-42: текст вразброс или таблица без выделения колонок.
     */
    suspend fun scanSk42Free(
        bitmap: Bitmap,
        mode: WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API ключ не задан" }

        val full = cropAndDownscale(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        val base64 = encodeJpegBase64(full)

        val prompt = when (mode) {
            WgsMode.TEXT  -> ScanPrompts.sk42FreeText()
            WgsMode.TABLE -> ScanPrompts.sk42FreeTable()
        }

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
        ScanParsers.parseSk42Response(json)
    }

    /**
     * Batch-сканирование фото таблицы без выделения колонок.
     * Поддерживает обе системы (СК-42 и WGS-84) — AI сам определяет.
     */
    suspend fun scanBatch(
        bitmap: Bitmap,
        apiKey: String,
    ): List<ParsedCoord> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API ключ не задан" }

        val full = cropAndDownscale(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        val base64 = encodeJpegBase64(full)

        val prompt = ScanPrompts.batch()

        val schema = JSONObject().apply {
            put("type", "ARRAY")
            put("items", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().put("type", "STRING"))
                    put("isWgs84", JSONObject().put("type", "BOOLEAN"))
                    put("x", JSONObject().put("type", "NUMBER"))
                    put("y", JSONObject().put("type", "NUMBER"))
                    put("zone", JSONObject().put("type", "INTEGER"))
                    put("lat", JSONObject().put("type", "NUMBER"))
                    put("lon", JSONObject().put("type", "NUMBER"))
                })
                put("required", JSONArray(listOf("name", "isWgs84", "x", "y", "zone", "lat", "lon")))
            })
        }

        val json = callGemini(apiKey, prompt, base64, schema)
        ScanParsers.parseBatchResponse(json)
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
        val bodyStr = body.toString()

        var lastError: Exception? = null
        for (model in MODEL_CHAIN) {
            val request = Request.Builder()
                .url(endpointFor(model))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val short = text.take(400)
                        Log.w(TAG, "model $model: HTTP ${resp.code}: $short")
                        // 400/401/403 — ошибка ключа/запроса, ретрай не поможет.
                        if (resp.code in setOf(400, 401, 403)) {
                            throw GeminiException("HTTP ${resp.code}: $short")
                        }
                        // 404/429/5xx и прочее — пробуем следующую модель.
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
                    Log.d(TAG, "model $model: ответ получен (${out.length} символов)")
                    return out.toString()
                }
            } catch (e: GeminiException) {
                val msg = e.message.orEmpty()
                // На ошибках ключа/запроса дальше нет смысла перебирать модели.
                if (msg.startsWith("HTTP 400") || msg.startsWith("HTTP 401") || msg.startsWith("HTTP 403")) {
                    throw e
                }
                lastError = e
                Log.w(TAG, "model $model failed, trying next: $msg")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "model $model network/IO failed, trying next", e)
            }
        }

        throw lastError ?: GeminiException("Все модели Gemini недоступны")
    }

    // ── Кроп и кодирование ──────────────────────────────────────

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
