package com.coordscanner.utils

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

/**
 * Каскад AI-провайдеров: Anthropic Claude → OpenRouter.
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
                bitmap = bitmap,
                nameRect = nameRect,
                xRect = xRect,
                yRect = yRect,
                imageViewRect = imageViewRect,
                apiKey = key,
            )
        },
        openrouter = { key ->
            OpenRouterScanner.scanSk42Columns(
                bitmap = bitmap,
                nameRect = nameRect,
                xRect = xRect,
                yRect = yRect,
                imageViewRect = imageViewRect,
                apiKey = key,
            )
        },
    )

    suspend fun scanWgs(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
    ): Result<List<MatchedRow>> = runChain(
        anthropic = { key -> AnthropicScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = key) },
        openrouter = { key -> OpenRouterScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = key) },
    )

    suspend fun scanSk42Free(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
    ): Result<List<MatchedRow>> = runChain(
        anthropic = { key -> AnthropicScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = key) },
        openrouter = { key -> OpenRouterScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = key) },
    )

    suspend fun scanBatch(
        bitmap: Bitmap,
    ): Result<List<ParsedCoord>> = runChain(
        anthropic = { key -> AnthropicScanner.scanBatch(bitmap = bitmap, apiKey = key) },
        openrouter = { key -> OpenRouterScanner.scanBatch(bitmap = bitmap, apiKey = key) },
    )

    // ── Внутреннее ──────────────────────────────────────────────

    private suspend fun <T> runChain(
        anthropic: suspend (apiKey: String) -> T,
        openrouter: suspend (apiKey: String) -> T,
    ): Result<T> {
        // 1) Anthropic.
        val anthropicKey = AiPrefs.anthropicKey()
        var lastError: Throwable? = null
        if (anthropicKey.isNotBlank()) {
            val r = runCatching { anthropic(anthropicKey) }
            r.onSuccess {
                AiPrefs.markSuccess()
                return r
            }
            lastError = r.exceptionOrNull()
            Log.w(TAG, "Anthropic chain failed, пробуем OpenRouter", lastError)
        }

        // 2) OpenRouter.
        val openrouterKey = AiPrefs.openrouterKey()
        if (openrouterKey.isNotBlank()) {
            val r = runCatching { openrouter(openrouterKey) }
            r.onSuccess {
                AiPrefs.markSuccess()
                return r
            }
            lastError = r.exceptionOrNull() ?: lastError
            Log.w(TAG, "OpenRouter chain failed", r.exceptionOrNull())
        }

        // 3) Оба упали или ключей нет.
        val finalErr = lastError
            ?: IllegalStateException("Нет ключей AI (Anthropic / OpenRouter)")
        AiPrefs.markError(finalErr)
        return Result.failure(finalErr)
    }
}
