package com.coordscanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColumnOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class ColumnMark(
        val fraction: Float,   // 0..1 от ширины изображения
        val color: Int,
        val label: String
    )

    // Прямоугольник, который занимает изображение внутри ImageView (fitCenter)
    var imageRect = RectF()
        set(value) {
            field = value
            invalidate()
        }

    // Ширина полосы в долях от ширины изображения (итого ±7.5%)
    var stripWidthFraction = 0.15f

    var onTap: ((Float, Float) -> Unit)? = null

    private val marks = mutableListOf<ColumnMark>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    init {
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                onTap?.invoke(event.x, event.y)
            }
            performClick()
            true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setMarks(newMarks: List<ColumnMark>) {
        marks.clear()
        marks.addAll(newMarks)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.isEmpty) return

        val imgW = imageRect.width()
        val half = imgW * stripWidthFraction / 2f

        for (mark in marks) {
            val cx = imageRect.left + mark.fraction * imgW

            // Полупрозрачная заливка полосы
            fillPaint.color = (mark.color and 0x00FFFFFF) or (0x44 shl 24)
            canvas.drawRect(cx - half, imageRect.top, cx + half, imageRect.bottom, fillPaint)

            // Центральная вертикальная линия
            fillPaint.color = (mark.color and 0x00FFFFFF) or (0xCC shl 24)
            canvas.drawRect(cx - 2.5f, imageRect.top, cx + 2.5f, imageRect.bottom, fillPaint)

            // Подпись в верхней части полосы
            labelPaint.color = mark.color
            val textW = labelPaint.measureText(mark.label)
            canvas.drawText(mark.label, cx - textW / 2f, imageRect.top + 56f, labelPaint)
        }
    }
}
