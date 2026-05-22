package com.coordscanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class ColumnType { NAME, X, Y }

    companion object {
        val COLOR_NAME = 0xFF2196F3.toInt()  // синий
        val COLOR_X    = 0xFF4CAF50.toInt()  // зелёный
        val COLOR_Y    = 0xFFE53935.toInt()  // красный

        fun colorFor(type: ColumnType) = when (type) {
            ColumnType.NAME -> COLOR_NAME
            ColumnType.X    -> COLOR_X
            ColumnType.Y    -> COLOR_Y
        }

        fun labelFor(type: ColumnType) = when (type) {
            ColumnType.NAME -> "НАЗВАНИЕ"
            ColumnType.X    -> "X"
            ColumnType.Y    -> "Y"
        }
    }

    // Прямоугольник изображения в пространстве View (fitCenter)
    var imageRect = RectF()

    // Какой тип колонки выделяем прямо сейчас
    var activeType: ColumnType? = null
        set(value) {
            field = value
            drawingRect = null
            invalidate()
            onSelectionStateChanged?.invoke()
        }

    var onRectConfirmed: ((ColumnType, RectF) -> Unit)? = null
    var onSelectionStateChanged: (() -> Unit)? = null

    private val confirmedRects = mutableMapOf<ColumnType, RectF>()
    private var dragStart = PointF()
    private var drawingRect: RectF? = null  // прямоугольник в процессе рисования

    // Кисти для рисования
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val liveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        // Пунктирная рамка во время рисования
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 38f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    fun getConfirmedRect(type: ColumnType): RectF? = confirmedRects[type]

    fun hasAllRects(): Boolean = ColumnType.values().all { it in confirmedRects }

    fun clearAll() {
        confirmedRects.clear()
        drawingRect = null
        activeType = null
        invalidate()
    }

    // ── Обработка касаний ─────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Если нет активного режима — не потребляем события
        if (activeType == null && event.action == MotionEvent.ACTION_DOWN) return false

        // Зажимаем координаты в пределах изображения
        val x = if (imageRect.isEmpty) event.x else event.x.coerceIn(imageRect.left, imageRect.right)
        val y = if (imageRect.isEmpty) event.y else event.y.coerceIn(imageRect.top, imageRect.bottom)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStart.set(x, y)
                drawingRect = RectF(x, y, x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                drawingRect = RectF(
                    minOf(dragStart.x, x), minOf(dragStart.y, y),
                    maxOf(dragStart.x, x), maxOf(dragStart.y, y)
                )
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val rect = drawingRect
                val active = activeType
                drawingRect = null
                // Принимаем только прямоугольник достаточного размера
                if (rect != null && active != null && rect.width() > 20f && rect.height() > 20f) {
                    confirmedRects[active] = RectF(rect)
                    onRectConfirmed?.invoke(active, RectF(rect))
                }
                // Выходим из режима выделения
                activeType = null  // вызывает onSelectionStateChanged через setter
                invalidate()
            }
        }
        return true
    }

    // ── Рисование ────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Зафиксированные прямоугольники
        for ((type, rect) in confirmedRects) {
            val color = colorFor(type)

            fillPaint.color = (color and 0x00FFFFFF) or (0x33 shl 24)
            canvas.drawRect(rect, fillPaint)

            borderPaint.color = color
            canvas.drawRect(rect, borderPaint)

            labelPaint.color = color
            val label = labelFor(type)
            val tw = labelPaint.measureText(label)
            canvas.drawText(label, rect.left + (rect.width() - tw) / 2f, rect.top + 48f, labelPaint)
        }

        // Прямоугольник в процессе рисования (пунктир)
        val live = drawingRect ?: return
        val activeColor = activeType?.let { colorFor(it) } ?: Color.WHITE

        fillPaint.color = (activeColor and 0x00FFFFFF) or (0x22 shl 24)
        canvas.drawRect(live, fillPaint)

        liveBorderPaint.color = activeColor
        canvas.drawRect(live, liveBorderPaint)
    }
}
