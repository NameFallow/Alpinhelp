package com.coordscanner.utils

import android.graphics.Bitmap
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
 * Бесплатный AI-провайдер — Groq (https://groq.com).
 * Vision-модели llama 3.2 без оплаты, высокие лимиты. API совместим с OpenAI.
 * Используется как последний резерв, когда Anthropic и OpenRouter недоступны.
 */
object GroqScanner {

    private const val TAG = "GroqScanner"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    // Vision-модели Groq. llama-3.2-*-vision-preview сняты как decommissioned;
    // на 2026 единственная актуальная — Llama 4 Scout (17b, 131k контекст).
    private val MODEL_CHAIN = listOf(
        "meta-llama/llama-4-scout-17b-16e-instruct",
    )

    private const val MAX_SIDE_PX = 2048
    private const val JPEG_QUALITY = 90
    private const val MAX_TOKENS = 4096

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    class GroqException(msg: String) : Exception(msg)

    suspend fun scanSk42Columns(
        bitmap: Bitmap,
        nameRect: RectF,
        xRect: RectF,
        yRect: RectF,
        imageViewRect: RectF,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Groq API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val nameFr = ScanPrompts.normalizeRect(nameRect, imageViewRect)
        val xFr    = ScanPrompts.normalizeRect(xRect,    imageViewRect)
        val yFr    = ScanPrompts.normalizeRect(yRect,    imageViewRect)
        val json = callGroq(apiKey, ScanPrompts.sk42Columns(nameFr, xFr, yFr), base64)
        ScanParsers.parseSk42Response(json)
    }

    suspend fun scanWgs(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Groq API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val prompt = when (mode) {
            GeminiScanner.WgsMode.TEXT  -> ScanPrompts.wgsText()
            GeminiScanner.WgsMode.TABLE -> ScanPrompts.wgsTable()
        }
        val json = callGroq(apiKey, prompt, base64)
        ScanParsers.parseWgsResponse(json)
    }

    suspend fun scanSk42Free(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
        apiKey: String,
    ): List<MatchedRow> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Groq API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val prompt = when (mode) {
            GeminiScanner.WgsMode.TEXT  -> ScanPrompts.sk42FreeText()
            GeminiScanner.WgsMode.TABLE -> ScanPrompts.sk42FreeTable()
        }
        val json = callGroq(apiKey, prompt, base64)
        ScanParsers.parseSk42Response(json)
    }

    suspend fun scanBatch(
        bitmap: Bitmap,
        apiKey: String,
    ): List<ParsedCoord> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Groq API ключ не задан" }
        val base64 = encodeJpegBase64(downscale(bitmap))
        val json = callGroq(apiKey, ScanPrompts.batch(), base64)
        ScanParsers.parseBatchResponse(json)
    }

    // ── HTTP ────────────────────────────────────────────────────

    private fun callGroq(
        apiKey: String,
        prompt: String,
        imageBase64: String,
    ): String {
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
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val short = text.take(400)
                        Log.w(TAG, "model $model: HTTP ${resp.code}: $short")
                        if (resp.code in setOf(401, 403)) {
                            throw GroqException("HTTP ${resp.code}: $short")
                        }
                        throw GroqException("HTTP ${resp.code}: $short")
                    }
                    val root = JSONObject(text)
                    val choices = root.optJSONArray("choices")
                        ?: throw GroqException("Нет choices в ответе")
                    if (choices.length() == 0) throw GroqException("Пустой ответ модели")
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    val out = message.optString("content", "")
                    Log.d(TAG, "model $model: ответ получен (${out.length} символов)")
                    return out
                }
            } catch (e: GroqException) {
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

        throw lastError ?: GroqException("Все модели Groq недоступны")
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
