package com.coordscanner.utils

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

object AiUi {

    fun bindAiToggle(
        ctx: Context,
        button: MaterialButton,
        onChanged: (Boolean) -> Unit = {},
    ) {
        refreshAiToggleText(ctx, button)
        button.setOnClickListener {
            val next = !AiPrefs.useAi(ctx)
            if (next && !AiPrefs.hasAnyKey(ctx)) {
                showApiKeyDialog(ctx, requireKey = true) { saved ->
                    if (saved && AiPrefs.hasAnyKey(ctx)) {
                        AiPrefs.setUseAi(ctx, true)
                        refreshAiToggleText(ctx, button)
                        onChanged(true)
                    }
                }
                return@setOnClickListener
            }
            AiPrefs.setUseAi(ctx, next)
            refreshAiToggleText(ctx, button)
            onChanged(next)
        }
        button.setOnLongClickListener {
            showApiKeyDialog(ctx, requireKey = false) {
                refreshAiToggleText(ctx, button)
            }
            true
        }
    }

    fun refreshAiToggleText(ctx: Context, button: MaterialButton) {
        val on = AiPrefs.useAi(ctx) && AiPrefs.hasAnyKey(ctx)
        button.text = if (on) "🤖 AI скан: ВКЛ  (долгий тап — ключ)"
                      else    "🤖 AI скан: ВЫКЛ  (долгий тап — настройки)"
        button.setTextColor(if (on) 0xFF4CAF50.toInt() else 0xFF7AA0C8.toInt())
    }

    fun showApiKeyDialog(
        ctx: Context,
        requireKey: Boolean,
        onResult: (saved: Boolean) -> Unit,
    ) {
        val padding = (ctx.resources.displayMetrics.density * 16).toInt()

        val info = TextView(ctx).apply {
            text = "AI-скан использует Gemini 2.5 Flash для распознавания таблиц координат.\n\n" +
                    "Получить ключ: aistudio.google.com/app/apikey → Create API key.\n" +
                    "Бесплатный лимит: 1500 запросов/день."
            textSize = 13f
            setPadding(0, 0, 0, padding / 2)
        }

        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "API ключ Gemini"
            setText(AiPrefs.userApiKey(ctx))
            setSingleLine(true)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            gravity = Gravity.TOP
            addView(info)
            addView(input)
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (requireKey) "Нужен API ключ Gemini" else "Настройки AI-скана")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val key = input.text.toString().trim()
                AiPrefs.setUserApiKey(ctx, key)
                if (key.isNotEmpty() || AiPrefs.hasAnyKey(ctx)) {
                    Toast.makeText(ctx, "Ключ сохранён", Toast.LENGTH_SHORT).show()
                    onResult(true)
                } else {
                    Toast.makeText(ctx, "Ключ пустой — AI отключён", Toast.LENGTH_SHORT).show()
                    AiPrefs.setUseAi(ctx, false)
                    onResult(false)
                }
            }
            .setNegativeButton("Отмена") { _, _ -> onResult(false) }

        if (!requireKey && AiPrefs.userApiKey(ctx).isNotEmpty()) {
            builder.setNeutralButton("Удалить") { _, _ ->
                AiPrefs.setUserApiKey(ctx, "")
                if (!AiPrefs.hasAnyKey(ctx)) AiPrefs.setUseAi(ctx, false)
                Toast.makeText(ctx, "Ключ удалён", Toast.LENGTH_SHORT).show()
                onResult(true)
            }
        }
        builder.show()
    }
}
