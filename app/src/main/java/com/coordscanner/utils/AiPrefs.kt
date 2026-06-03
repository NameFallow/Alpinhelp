package com.coordscanner.utils

import com.coordscanner.BuildConfig

object AiPrefs {
    fun apiKey(): String = BuildConfig.GEMINI_API_KEY
    fun hasKey(): Boolean = apiKey().isNotBlank()
}
