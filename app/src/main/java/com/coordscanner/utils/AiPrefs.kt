package com.coordscanner.utils

import android.content.Context
import com.coordscanner.BuildConfig

object AiPrefs {

    private const val PREFS = "ai_prefs"
    private const val KEY_USE_AI = "use_ai"
    private const val KEY_USER_API_KEY = "user_api_key"

    fun useAi(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_AI, false)

    fun setUseAi(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_USE_AI, value).apply()
    }

    fun userApiKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_API_KEY, "") ?: ""

    fun setUserApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_USER_API_KEY, key.trim()).apply()
    }

    fun effectiveApiKey(ctx: Context): String {
        val u = userApiKey(ctx)
        return u.ifBlank { BuildConfig.GEMINI_API_KEY }
    }

    fun hasAnyKey(ctx: Context): Boolean = effectiveApiKey(ctx).isNotBlank()
}
