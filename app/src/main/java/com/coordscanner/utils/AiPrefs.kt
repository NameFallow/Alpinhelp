package com.coordscanner.utils

import android.content.Context
import android.content.SharedPreferences
import com.coordscanner.BuildConfig

object AiPrefs {
    private const val PREFS = "ai_prefs"
    private const val KEY_USER_KEY = "user_key"
    private const val KEY_ANTHROPIC_USER_KEY = "anthropic_user_key"
    private const val KEY_OPENROUTER_USER_KEY = "openrouter_user_key"
    private const val KEY_ENABLED  = "enabled"
    private const val KEY_LAST_OK_AT  = "last_ok_at"
    private const val KEY_LAST_ERR_AT = "last_err_at"
    private const val KEY_LAST_ERR_MSG = "last_err_msg"

    private const val ERR_MSG_MAX = 200

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

    fun anthropicKey(): String {
        val u = anthropicUserKeyRaw()
        return if (u.isNotEmpty()) u else BuildConfig.ANTHROPIC_API_KEY
    }

    fun openrouterKey(): String {
        val u = openrouterUserKeyRaw()
        return if (u.isNotEmpty()) u else BuildConfig.OPENROUTER_API_KEY
    }

    fun hasKey(): Boolean = apiKey().isNotBlank()
    fun hasAnthropicKey(): Boolean = anthropicKey().isNotBlank()
    fun hasOpenRouterKey(): Boolean = openrouterKey().isNotBlank()
    fun isEnabled(): Boolean = if (::sp.isInitialized) sp.getBoolean(KEY_ENABLED, true) else true
    // Gemini в регионе пользователя недоступен — пробуем при наличии любого из резервных ключей.
    fun isReadyToTry(): Boolean = isEnabled() && (hasAnthropicKey() || hasOpenRouterKey())

    fun setUserKey(k: String?) {
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_USER_KEY, k?.trim().orEmpty()).apply()
    }

    fun setAnthropicUserKey(k: String?) {
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_ANTHROPIC_USER_KEY, k?.trim().orEmpty()).apply()
    }

    fun setOpenRouterUserKey(k: String?) {
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_OPENROUTER_USER_KEY, k?.trim().orEmpty()).apply()
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

    fun markSuccess() {
        if (!::sp.isInitialized) return
        sp.edit().putLong(KEY_LAST_OK_AT, System.currentTimeMillis()).apply()
    }

    fun markError(t: Throwable?) {
        if (!::sp.isInitialized) return
        val raw = (t?.message ?: t?.javaClass?.simpleName ?: "unknown").trim()
        val msg = if (raw.length > ERR_MSG_MAX) raw.substring(0, ERR_MSG_MAX) + "…" else raw
        sp.edit()
            .putLong(KEY_LAST_ERR_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERR_MSG, msg)
            .apply()
    }

    fun lastOkAt(): Long  = if (::sp.isInitialized) sp.getLong(KEY_LAST_OK_AT, 0L) else 0L
    fun lastErrAt(): Long = if (::sp.isInitialized) sp.getLong(KEY_LAST_ERR_AT, 0L) else 0L
    fun lastErrMsg(): String? = if (::sp.isInitialized) sp.getString(KEY_LAST_ERR_MSG, null) else null

    // true если последний вызов был успешным или вызовов ещё не было.
    fun runtimeOk(): Boolean = lastErrAt() <= lastOkAt()

    // true только когда конфиг готов И последний реальный вызов не упал.
    fun isActive(): Boolean = isReadyToTry() && runtimeOk()

    // Человеческое объяснение, что не так (или OK).
    fun statusReason(): String {
        val backup = if (hasOpenRouterKey()) " · резерв: OpenRouter" else ""
        return when {
            !isEnabled()                                -> "Выключено пользователем"
            !hasAnthropicKey() && !hasOpenRouterKey()   -> "Нет API-ключей (Anthropic или OpenRouter)"
            !runtimeOk()                                -> "Последняя ошибка: ${lastErrMsg() ?: "неизвестно"}$backup"
            lastOkAt() == 0L                            -> "Готов к работе (вызовов ещё не было)$backup"
            else                                        -> "Всё в порядке$backup"
        }
    }

    private fun userKeyRaw(): String =
        if (::sp.isInitialized) sp.getString(KEY_USER_KEY, "").orEmpty().trim() else ""

    private fun anthropicUserKeyRaw(): String =
        if (::sp.isInitialized) sp.getString(KEY_ANTHROPIC_USER_KEY, "").orEmpty().trim() else ""

    private fun openrouterUserKeyRaw(): String =
        if (::sp.isInitialized) sp.getString(KEY_OPENROUTER_USER_KEY, "").orEmpty().trim() else ""
}
