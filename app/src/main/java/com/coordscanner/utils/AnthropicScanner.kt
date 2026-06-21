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
 * Резервный AI-провайдер — Anthropic Claude.
 * Подключается, когда каскад Gemini-моделей не отвечает.
 * Использует те же промпты (ScanPrompts) и парсеры (ScanParsers), что и Gemini.
 */
object AnthropicScanner {

    private const val TAG = "AnthropicScanner"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    // Каскад моделей Claude с подтверждённым vision-входом.
    private val MODEL_CHAIN = listOf(
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022",
        "claude-3-haiku-20240307",
    )

    private const val MAX_SIDE_PX = 2560
    private const val JPEG_QUALITY = 90
    private const val MAX_TOKENS = 4096

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    class AnthropicException(msg: String) : Exception(msg)

    suspend fun scanSk42Columns(
        bitmap: Bitmap,
        nameRect: RectF,
        xRect: RectF,
        yRect: RectF,
        imageViewRect: RectF,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Anthropic API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val nameFr = ScanPrompts.normalizeRect(nameRect, imageViewRect)
        val xFr    = ScanPrompts.normalizeRect(xRect,    imageViewRect)
        val yFr    = ScanPrompts.normalizeRect(yRect,    imageViewRect)
        val json = callClaude(apiKey, ScanPrompts.sk42Columns(nameFr, xFr, yFr), base64)
        ScanParsers.parseSk42Response(json)
    }

    suspend fun scanWgs(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Anthropic API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val prompt = when (mode) {
            GeminiScanner.WgsMode.TEXT  -> ScanPrompts.wgsText()
            GeminiScanner.WgsMode.TABLE -> ScanPrompts.wgsTable()
        }
        val json = callClaude(apiKey, prompt, base64)
        ScanParsers.parseWgsResponse(json)
    }

    suspend fun scanSk42Free(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Anthropic API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val prompt = when (mode) {
            GeminiScanner.WgsMode.TEXT  -> ScanPrompts.sk42FreeText()
            GeminiScanner.WgsMode.TABLE -> ScanPrompts.sk42FreeTable()
        }
        val json = callClaude(apiKey, prompt, base64)
        ScanParsers.parseSk42Response(json)
    }

    suspend fun scanBatch(
        bitmap: Bitmap,
        apiKey: String,
    ): List<ParsedCoord> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Anthropic API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val json = callClaude(apiKey, ScanPrompts.batch(), base64)
        ScanParsers.parseBatchResponse(json)
    }

    // ── HTTP ────────────────────────────────────────────────────

    private fun callClaude(
        apiKey: String,
        prompt: String,
        imageBase64: String,
    ): String {
        // Claude Messages API: контент — список блоков (image + text).
        // У Claude нет responseSchema, но JSON-ответ ловим через "Верни ТОЛЬКО JSON-массив"
        // в промпте + ScanParsers.parseArray, который умеет вычленять массив из обёртки.
        val content = JSONArray()
            .put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", imageBase64)
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
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val short = text.take(400)
                        Log.w(TAG, "model $model: HTTP ${resp.code}: $short")
                        // 401/403 — ошибка ключа, ретрай не поможет.
                        // 400 может означать невалидный alias модели — продолжаем по каскаду.
                        if (resp.code in setOf(401, 403)) {
                            throw AnthropicException("HTTP ${resp.code}: $short")
                        }
                        throw AnthropicException("HTTP ${resp.code}: $short")
                    }
                    if (text.isBlank()) throw AnthropicException("Пустое тело ответа модели")
                    val root = JSONObject(text)
                    val contentArr = root.optJSONArray("content")
                        ?: throw AnthropicException("Нет content в ответе")
                    if (contentArr.length() == 0) throw AnthropicException("Пустой ответ модели")
                    val out = StringBuilder()
                    for (i in 0 until contentArr.length()) {
                        val block = contentArr.optJSONObject(i) ?: continue
                        if (block.optString("type") == "text") {
                            out.append(block.optString("text", ""))
                        }
                    }
                    Log.d(TAG, "model $model: ответ получен (${out.length} символов)")
                    return out.toString()
                }
            } catch (e: AnthropicException) {
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

        throw lastError ?: AnthropicException("Все модели Anthropic недоступны")
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
