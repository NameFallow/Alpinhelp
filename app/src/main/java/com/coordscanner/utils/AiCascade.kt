package com.coordscanner.utils

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

/**
 * Каскад AI-провайдеров: Anthropic Claude → OpenRouter → Groq.
 * Gemini в регионе пользователя недоступен, поэтому не дёргается.
 * Возвращает Result, чтобы вызывающая активити могла упасть на ML Kit.
 * Сам ведёт учёт markSuccess/markError в AiPrefs.
 */
object AiCascade {

    private const val TAG = "AiCascade"

    suspend fun scanSk42Columns(
        bitmap: Bitmap,
        nameRect: RectF,
        xRect: RectF,
        yRect: RectF,
        imageViewRect: RectF,
    ): Result<List<MatchedRow>> = runChain(
        anthropic = { key ->
            AnthropicScanner.scanSk42Columns(
                bitmap = bitmap, nameRect = nameRect, xRect = xRect, yRect = yRect,
                imageViewRect = imageViewRect, apiKey = key,
            )
        },
        openrouter = { key ->
            OpenRouterScanner.scanSk42Columns(
                bitmap = bitmap, nameRect = nameRect, xRect = xRect, yRect = yRect,
                imageViewRect = imageViewRect, apiKey = key,
            )
        },
        groq = { key ->
            GroqScanner.scanSk42Columns(
                bitmap = bitmap, nameRect = nameRect, xRect = xRect, yRect = yRect,
                imageViewRect = imageViewRect, apiKey = key,
            )
        },
    )

    suspend fun scanWgs(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
    ): Result<List<MatchedRow>> = runChain(
        anthropic = { key -> AnthropicScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = key) },
        openrouter = { key -> OpenRouterScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = key) },
        groq = { key -> GroqScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = key) },
    )

    suspend fun scanWgsColumns(
        bitmap: Bitmap,
        nameRect: RectF,
        latRect: RectF,
        lonRect: RectF,
        imageViewRect: RectF,
    ): Result<List<MatchedRow>> = runChain(
        anthropic = { key ->
            AnthropicScanner.scanWgsColumns(
                bitmap = bitmap, nameRect = nameRect, latRect = latRect, lonRect = lonRect,
                imageViewRect = imageViewRect, apiKey = key,
            )
        },
        openrouter = { key ->
            OpenRouterScanner.scanWgsColumns(
                bitmap = bitmap, nameRect = nameRect, latRect = latRect, lonRect = lonRect,
                imageViewRect = imageViewRect, apiKey = key,
            )
        },
        groq = { key ->
            GroqScanner.scanWgsColumns(
                bitmap = bitmap, nameRect = nameRect, latRect = latRect, lonRect = lonRect,
                imageViewRect = imageViewRect, apiKey = key,
            )
        },
    )

    suspend fun scanSk42Free(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
    ): Result<List<MatchedRow>> = runChain(
        anthropic = { key -> AnthropicScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = key) },
        openrouter = { key -> OpenRouterScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = key) },
        groq = { key -> GroqScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = key) },
    )

    suspend fun scanBatch(
        bitmap: Bitmap,
    ): Result<List<ParsedCoord>> = runChain(
        anthropic = { key -> AnthropicScanner.scanBatch(bitmap = bitmap, apiKey = key) },
        openrouter = { key -> OpenRouterScanner.scanBatch(bitmap = bitmap, apiKey = key) },
        groq = { key -> GroqScanner.scanBatch(bitmap = bitmap, apiKey = key) },
    )

    // ── Внутреннее ──────────────────────────────────────────────

    private suspend fun <T> runChain(
        anthropic: suspend (apiKey: String) -> T,
        openrouter: suspend (apiKey: String) -> T,
        groq: suspend (apiKey: String) -> T,
    ): Result<T> {
        var lastError: Throwable? = null

        // 1) Anthropic.
        val anthropicKey = AiPrefs.anthropicKey()
        if (anthropicKey.isNotBlank()) {
            val r = runCatching { anthropic(anthropicKey) }
            r.onSuccess { AiPrefs.markSuccess(); return r }
            lastError = r.exceptionOrNull()
            Log.w(TAG, "Anthropic failed, пробую OpenRouter", lastError)
        }

        // 2) OpenRouter.
        val openrouterKey = AiPrefs.openrouterKey()
        if (openrouterKey.isNotBlank()) {
            val r = runCatching { openrouter(openrouterKey) }
            r.onSuccess { AiPrefs.markSuccess(); return r }
            lastError = r.exceptionOrNull() ?: lastError
            Log.w(TAG, "OpenRouter failed, пробую Groq", r.exceptionOrNull())
        }

        // 3) Groq (бесплатный).
        val groqKey = AiPrefs.groqKey()
        if (groqKey.isNotBlank()) {
            val r = runCatching { groq(groqKey) }
            r.onSuccess { AiPrefs.markSuccess(); return r }
            lastError = r.exceptionOrNull() ?: lastError
            Log.w(TAG, "Groq failed", r.exceptionOrNull())
        }

        val finalErr = lastError
            ?: IllegalStateException("Нет ключей AI (Anthropic / OpenRouter / Groq)")
        AiPrefs.markError(finalErr)
        return Result.failure(finalErr)
    }
}
