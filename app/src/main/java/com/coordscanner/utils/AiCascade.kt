package com.coordscanner.utils

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

/**
 * Каскад AI-провайдеров: Gemini → Anthropic.
 * Возвращает Result, чтобы вызывающий активити мог упасть на ML Kit.
 * Сам ведёт учёт markSuccess/markError в AiPrefs, поэтому активити их вызывать не должны.
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
        gemini = {
            GeminiScanner.scanSk42Columns(
                bitmap = bitmap,
                nameRect = nameRect,
                xRect = xRect,
                yRect = yRect,
                imageViewRect = imageViewRect,
                apiKey = AiPrefs.apiKey(),
            )
        },
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
    )

    suspend fun scanWgs(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
    ): Result<List<MatchedRow>> = runChain(
        gemini = { GeminiScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = AiPrefs.apiKey()) },
        anthropic = { key -> AnthropicScanner.scanWgs(bitmap = bitmap, mode = mode, apiKey = key) },
    )

    suspend fun scanSk42Free(
        bitmap: Bitmap,
        mode: GeminiScanner.WgsMode,
    ): Result<List<MatchedRow>> = runChain(
        gemini = { GeminiScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = AiPrefs.apiKey()) },
        anthropic = { key -> AnthropicScanner.scanSk42Free(bitmap = bitmap, mode = mode, apiKey = key) },
    )

    suspend fun scanBatch(
        bitmap: Bitmap,
    ): Result<List<ParsedCoord>> = runChain(
        gemini = { GeminiScanner.scanBatch(bitmap = bitmap, apiKey = AiPrefs.apiKey()) },
        anthropic = { key -> AnthropicScanner.scanBatch(bitmap = bitmap, apiKey = key) },
    )

    // ── Внутреннее ──────────────────────────────────────────────

    // Gemini заблокирован в регионе пользователя — используем только Anthropic.
    // Аргумент gemini оставлен в API, чтобы не ломать сигнатуры вызовов; не вызывается.
    private suspend fun <T> runChain(
        gemini: suspend () -> T,
        anthropic: suspend (apiKey: String) -> T,
    ): Result<T> {
        val anthropicKey = AiPrefs.anthropicKey()
        if (anthropicKey.isBlank()) {
            val err = IllegalStateException("Нет ключа Anthropic Claude")
            AiPrefs.markError(err)
            return Result.failure(err)
        }

        val result = runCatching { anthropic(anthropicKey) }
        result.onSuccess { AiPrefs.markSuccess() }
              .onFailure {
                  Log.w(TAG, "Anthropic chain failed", it)
                  AiPrefs.markError(it)
              }
        return result
    }
}
