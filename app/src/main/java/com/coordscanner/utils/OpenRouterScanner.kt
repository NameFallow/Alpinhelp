package com.coordscanner.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.util.Log
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

/**
 * Резервный AI-провайдер — OpenRouter (https://openrouter.ai).
 * Один ключ → доступ к десяткам vision-моделей разных вендоров.
 * Подключается, когда Anthropic Claude недоступен в регионе или временно лежит.
 */
object OpenRouterScanner {

    private const val TAG = "OpenRouterScanner"
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val HTTP_REFERER = "https://github.com/NameFallow/Alpinhelp"
    private const val X_TITLE = "CoordScanner"

    // Каскад моделей OpenRouter: разные вендоры → высокая устойчивость к региональным блокам.
    private val MODEL_CHAIN = listOf(
        "openai/gpt-4o-mini",
        "anthropic/claude-3.5-sonnet",
        "qwen/qwen-2-vl-72b-instruct",
        "google/gemini-flash-1.5",
    )

    private const val MAX_SIDE_PX = 2560
    private const val JPEG_QUALITY = 90
    private const val MAX_TOKENS = 4096

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    class OpenRouterException(msg: String) : Exception(msg)

    suspend fun scanSk42Columns(
        bitmap: Bitmap,
        nameRect: RectF,
        xRect: RectF,
        yRect: RectF,
        imageViewRect: RectF,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val nameFr = ScanPrompts.normalizeRect(nameRect, imageViewRect)
        val xFr    = ScanPrompts.normalizeRect(xRect,    imageViewRect)
        val yFr    = ScanPrompts.normalizeRect(yRect,    imageViewRect)
        val json = callOpenRouter(apiKey, ScanPrompts.sk42Columns(nameFr, xFr, yFr), base64)
        ScanParsers.parseSk42Response(json)
    }

    suspend fun scanWgs(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val prompt = when (mode) {
            GeminiScanner.WgsMode.TEXT  -> ScanPrompts.wgsText()
            GeminiScanner.WgsMode.TABLE -> ScanPrompts.wgsTable()
        }
        val json = callOpenRouter(apiKey, prompt, base64)
        ScanParsers.parseWgsResponse(json)
    }

    suspend fun scanWgsColumns(
        bitmap: Bitmap,
        nameRect: RectF,
        latRect: RectF,
        lonRect: RectF,
        imageViewRect: RectF,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val nameFr = ScanPrompts.normalizeRect(nameRect, imageViewRect)
        val latFr  = ScanPrompts.normalizeRect(latRect,  imageViewRect)
        val lonFr  = ScanPrompts.normalizeRect(lonRect,  imageViewRect)
        val json = callOpenRouter(apiKey, ScanPrompts.wgsColumns(nameFr, latFr, lonFr), base64)
        ScanParsers.parseWgsResponse(json)
    }

    suspend fun scanSk42Free(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val prompt = when (mode) {
            GeminiScanner.WgsMode.TEXT  -> ScanPrompts.sk42FreeText()
            GeminiScanner.WgsMode.TABLE -> ScanPrompts.sk42FreeTable()
        }
        val json = callOpenRouter(apiKey, prompt, base64)
        ScanParsers.parseSk42Response(json)
    }

    suspend fun scanBatch(
        bitmap: Bitmap,
        apiKey: String,
    ): List<ParsedCoord> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val json = callOpenRouter(apiKey, ScanPrompts.batch(), base64)
        ScanParsers.parseBatchResponse(json)
    }

    // ── HTTP ────────────────────────────────────────────────────

    private fun callOpenRouter(
        apiKey: String,
        prompt: String,
        imageBase64: String,
    ): String {
        // OpenAI-совместимый chat completions с image_url (data URI).
        val content = JSONArray()
            .put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$imageBase64")
                })
            })
            .put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })

        val baseBody = JSONObject().apply {
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.1)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }

        var lastError: Exception? = null
        for (model in MODEL_CHAIN) {
            val body = JSONObject(baseBody.toString()).put("model", model).toString()
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", HTTP_REFERER)
                .header("X-Title", X_TITLE)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val short = text.take(400)
                        Log.w(TAG, "model $model: HTTP ${resp.code}: $short")
                        // 401/403 — ключ невалиден.
                        if (resp.code in setOf(401, 403)) {
                            throw OpenRouterException("HTTP ${resp.code}: $short")
                        }
                        throw OpenRouterException("HTTP ${resp.code}: $short")
                    }
                    if (text.isBlank()) throw OpenRouterException("Пустое тело ответа модели")
                    val root = JSONObject(text)
                    val choices = root.optJSONArray("choices")
                        ?: throw OpenRouterException("Нет choices в ответе")
                    if (choices.length() == 0) throw OpenRouterException("Пустой ответ модели")
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    val out = message.optString("content", "")
                    Log.d(TAG, "model $model: ответ получен (${out.length} символов)")
                    return out
                }
            } catch (e: OpenRouterException) {
                val msg = e.message.orEmpty()
                if (msg.startsWith("HTTP 401") || msg.startsWith("HTTP 403")) {
                    throw e
                }
                lastError = e
                Log.w(TAG, "model $model failed, trying next: $msg")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "model $model network/IO failed, trying next", e)
            }
        }

        throw lastError ?: OpenRouterException("Все модели OpenRouter недоступны")
    }

    // ── Кодирование ─────────────────────────────────────────────

    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= MAX_SIDE_PX) return bitmap
        val scale = MAX_SIDE_PX.toFloat() / maxSide
        val nw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val nh = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    private fun encodeJpegBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
