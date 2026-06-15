package com.coordscanner.utils

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.net.UnknownHostException
import java.net.SocketTimeoutException

object AiBadge {

    fun attach(activity: Activity, tv: TextView) {
        refresh(tv)
        tv.setOnClickListener {
            AiPrefs.setEnabled(!AiPrefs.isEnabled())
            refresh(tv)
            Toast.makeText(activity,
                if (AiPrefs.isEnabled()) "AI: вкл" else "AI: выкл",
                Toast.LENGTH_SHORT).show()
        }
        tv.setOnLongClickListener { showKeyDialog(activity, tv); true }
    }

    fun refresh(tv: TextView) {
        tv.text = when {
            !AiPrefs.isEnabled() -> "🤖 AI: выкл"
            !AiPrefs.hasKey()    -> "🤖 AI: нет ключа"
            else                 -> "🤖 AI"
        }
    }

    /** Короткое человеческое описание причины провала AI — для тоста. */
    fun describe(t: Throwable?): String {
        if (t == null) return "пустой результат"
        val msg = t.message.orEmpty()
        return when {
            t is UnknownHostException     -> "нет сети"
            t is SocketTimeoutException   -> "таймаут"
            msg.contains("HTTP 400")      -> "HTTP 400 (запрос)"
            msg.contains("HTTP 401")      -> "HTTP 401 (ключ?)"
            msg.contains("HTTP 403")      -> "HTTP 403 (ключ/доступ)"
            msg.contains("HTTP 404")      -> "HTTP 404 (модель?)"
            msg.contains("HTTP 429")      -> "HTTP 429 (лимит)"
            msg.contains("HTTP 5")        -> "сервер Gemini"
            msg.contains("JSON", true)    -> "парсинг JSON"
            msg.contains("candidates")    -> "пустой ответ"
            msg.isNotEmpty()              -> msg.take(60)
            else                          -> t.javaClass.simpleName
        }
    }

    private fun showKeyDialog(activity: Activity, tv: TextView) {
        val density = activity.resources.displayMetrics.density
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "AIzaSy…"
            setText("")
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(),
                       (20 * density).toInt(), (8 * density).toInt())
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("Ключ Gemini API")
            .setMessage("Источник сейчас: ${AiPrefs.source()}.\n" +
                "Бесплатный ключ: aistudio.google.com/app/apikey\n" +
                "Пусто + «Очистить» — вернуть встроенный.")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                AiPrefs.setUserKey(input.text.toString())
                refresh(tv)
                Toast.makeText(activity, "Ключ сохранён", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Очистить") { _, _ ->
                AiPrefs.setUserKey("")
                refresh(tv)
                Toast.makeText(activity, "Свой ключ очищен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
