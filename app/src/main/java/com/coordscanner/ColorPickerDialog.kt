package com.coordscanner

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

object ColorPickerDialog {

    data class ColorOption(val label: String, val hex: String)

    val COLORS = listOf(
        ColorOption("Синий",      "#0055FF"),
        ColorOption("Красный",    "#FF2020"),
        ColorOption("Зелёный",    "#00BB00"),
        ColorOption("Оранжевый",  "#FF8800"),
        ColorOption("Жёлтый",     "#FFD700"),
        ColorOption("Фиолетовый", "#9900CC"),
        ColorOption("Белый",      "#FFFFFF"),
    )

    /** Расширенная палитра (сетка 6×4) для массовой перекраски точек в GPX-менеджере. */
    val PALETTE = listOf(
        "#FF0000", "#FF5252", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5",
        "#0055FF", "#2196F3", "#03A9F4", "#00BCD4", "#009688", "#00BB00",
        "#4CAF50", "#8BC34A", "#CDDC39", "#FFEB3B", "#FFD700", "#FFC107",
        "#FF8800", "#FF5722", "#795548", "#9E9E9E", "#000000", "#FFFFFF",
    )

    /**
     * Палитра-сетка. [currentHex] — цвет, отмеченный при открытии (null — ничего не отмечено,
     * «Применить» недоступна пока цвет не выбран).
     */
    fun showPalette(
        context: Context,
        currentHex: String?,
        title: String = "Выберите цвет",
        onSelected: (String) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var selectedHex: String? = PALETTE.firstOrNull { it.equals(currentHex, ignoreCase = true) }
        val swatches = mutableListOf<TextView>()

        fun styleSwatch(view: TextView, hex: String, selected: Boolean) {
            val color = Color.parseColor(hex)
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(1), Color.parseColor("#80808080"))
            }
            // Галочка контрастного цвета: чёрная на светлом образце, белая на тёмном
            val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
            view.setTextColor(if (luminance > 150) Color.BLACK else Color.WHITE)
            view.text = if (selected) "✓" else ""
            val scale = if (selected) 1.15f else 1.0f
            view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }

        val grid = GridLayout(context).apply { columnCount = 6 }
        var applyButton: Button? = null

        for (hex in PALETTE) {
            val swatch = TextView(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(36); height = dp(36)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                gravity = Gravity.CENTER
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                contentDescription = hex
            }
            styleSwatch(swatch, hex, hex == selectedHex)
            swatch.setOnClickListener {
                selectedHex = hex
                swatches.forEachIndexed { i, v -> styleSwatch(v, PALETTE[i], PALETTE[i] == hex) }
                applyButton?.isEnabled = true
            }
            swatches += swatch
            grid.addView(swatch)
        }

        val container = LinearLayout(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(4))
            addView(grid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Применить") { _, _ -> selectedHex?.let(onSelected) }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        applyButton?.isEnabled = selectedHex != null
    }

    fun show(context: Context, currentHex: String, onSelected: (String) -> Unit) {
        var selectedHex = currentHex
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                (20 * density).toInt(), (20 * density).toInt(),
                (20 * density).toInt(), (8  * density).toInt()
            )
        }

        val dots = mutableListOf<View>()

        for (opt in COLORS) {
            val dot = View(context).apply {
                val size = (36 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.marginEnd = (10 * density).toInt()
                }
                background = context.getDrawable(R.drawable.color_circle)
                backgroundTintList = ColorStateList.valueOf(
                    try { Color.parseColor(opt.hex) } catch (_: Exception) { Color.BLUE }
                )
                val initScale = if (opt.hex.equals(currentHex, ignoreCase = true)) 1.4f else 1.0f
                scaleX = initScale
                scaleY = initScale
            }

            dot.setOnClickListener {
                selectedHex = opt.hex
                dots.forEachIndexed { i, v ->
                    val target = if (COLORS[i].hex == opt.hex) 1.4f else 1.0f
                    v.animate().scaleX(target).scaleY(target).setDuration(130).start()
                }
            }

            dots += dot
            container.addView(dot)
        }

        AlertDialog.Builder(context)
            .setTitle("Выберите цвет")
            .setView(container)
            .setPositiveButton("Применить") { _, _ -> onSelected(selectedHex) }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
