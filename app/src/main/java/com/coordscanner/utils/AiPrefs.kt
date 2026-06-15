package com.coordscanner.utils

import android.content.Context
import android.content.SharedPreferences
import com.coordscanner.BuildConfig

object AiPrefs {
    private const val PREFS = "ai_prefs"
    private const val KEY_USER_KEY = "user_key"
    private const val KEY_ENABLED  = "enabled"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun apiKey(): String {
        val u = userKeyRaw()
        return if (u.isNotEmpty()) u else BuildConfig.GEMINI_API_KEY
    }

    fun hasKey(): Boolean = apiKey().isNotBlank()
    fun isEnabled(): Boolean = if (::sp.isInitialized) sp.getBoolean(KEY_ENABLED, true) else true
    fun isReadyToTry(): Boolean = isEnabled() && hasKey()

    fun setUserKey(k: String?) {
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_USER_KEY, k?.trim().orEmpty()).apply()
    }

    fun setEnabled(b: Boolean) {
        if (!::sp.isInitialized) return
        sp.edit().putBoolean(KEY_ENABLED, b).apply()
    }

    fun source(): String = when {
        userKeyRaw().isNotEmpty()        -> "user"
        BuildConfig.GEMINI_API_KEY.isNotBlank() -> "build"
        else                              -> "none"
    }

    private fun userKeyRaw(): String =
        if (::sp.isInitialized) sp.getString(KEY_USER_KEY, "").orEmpty().trim() else ""
}
