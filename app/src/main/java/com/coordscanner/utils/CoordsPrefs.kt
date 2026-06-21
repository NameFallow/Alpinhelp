package com.coordscanner.utils

import android.content.Context

object CoordsPrefs {
    private const val PREFS         = "app_prefs"
    private const val KEY           = "coord_mode"
    private const val KEY_SCAN_FMT  = "scan_format"

    const val SK42  = "sk42"
    const val WGS84 = "wgs84"

    fun getMode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SK42) ?: SK42

    fun setMode(ctx: Context, mode: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()

    fun getScanFormat(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCAN_FMT, SK42) ?: SK42

    fun setScanFormat(ctx: Context, mode: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCAN_FMT, mode).apply()
}
