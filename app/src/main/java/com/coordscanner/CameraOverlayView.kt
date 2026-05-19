package com.coordscanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")  // Material Blue
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#402196F3")  // 25% blue
        style = Paint.Style.FILL
    }

    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6000C853")  // green flash on detection
        style = Paint.Style.FILL
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private var rects = listOf<RectF>()
    private var flashAlpha = 0f
    private var flashAnimator: ValueAnimator? = null

    fun setRects(newRects: List<RectF>) {
        rects = newRects
        invalidate()
    }

    fun triggerFlash() {
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                flashAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (rect in rects) {
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, strokePaint)
            drawCorners(canvas, rect)
        }

        // Green flash overlay on detection
        if (flashAlpha > 0f) {
            flashPaint.alpha = (flashAlpha * 100).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
    }

    // Draw L-shaped corners instead of full rectangle
    private fun drawCorners(canvas: Canvas, rect: RectF) {
        val len = minOf(rect.width(), rect.height()) * 0.25f
        cornerPaint.alpha = 220

        // Top-left
        canvas.drawLine(rect.left, rect.top + len, rect.left, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, cornerPaint)

        // Top-right
        canvas.drawLine(rect.right - len, rect.top, rect.right, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, cornerPaint)

        // Bottom-left
        canvas.drawLine(rect.left, rect.bottom - len, rect.left, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, cornerPaint)

        // Bottom-right
        canvas.drawLine(rect.right - len, rect.bottom, rect.right, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom - len, rect.right, rect.bottom, cornerPaint)
    }
}
