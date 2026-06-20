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

    private suspend fun <T> runChain(
        gemini: suspend () -> T,
        anthropic: suspend (apiKey: String) -> T,
    ): Result<T> {
        // 1) Gemini (с каскадом моделей внутри).
        val geminiResult = runCatching { gemini() }
        geminiResult.onSuccess {
            AiPrefs.markSuccess()
            return geminiResult
        }
        val geminiError = geminiResult.exceptionOrNull()
        Log.w(TAG, "Gemini chain failed, пробуем Anthropic", geminiError)

        // 2) Anthropic (если ключ есть).
        val anthropicKey = AiPrefs.anthropicKey()
        if (anthropicKey.isBlank()) {
            AiPrefs.markError(geminiError)
            return geminiResult
        }

        val anthropicResult = runCatching { anthropic(anthropicKey) }
        anthropicResult.onSuccess {
            AiPrefs.markSuccess()
            return anthropicResult
        }
        val anthropicError = anthropicResult.exceptionOrNull()
        Log.w(TAG, "Anthropic chain failed", anthropicError)

        // Записываем самую свежую ошибку (Anthropic), но возвращаем Gemini-ошибку как первичную
        // только если Anthropic тоже упал — пусть UI видит самое полезное.
        AiPrefs.markError(anthropicError ?: geminiError)
        return anthropicResult
    }
}
