package com.coordscanner

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
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
