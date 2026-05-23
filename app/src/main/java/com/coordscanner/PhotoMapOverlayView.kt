package com.coordscanner

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor

class PhotoMapOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { ADD_X, ADD_Y, QUERY }

    data class RefLine(val id: Int, val position: Float, val value: Double)

    companion object {
        val COLOR_X     = 0xFF4CAF50.toInt() // зелёный — X (northing/lat)
        val COLOR_Y     = 0xFFFF7043.toInt() // оранжевый — Y (easting/lon)
        val COLOR_QUERY = 0xFFFFEB3B.toInt() // жёлтый — курсор
        const val MAX_LINES = 20
    }

    var mode: Mode = Mode.ADD_X
    var imageRect = RectF()

    val xLines = mutableListOf<RefLine>() // горизонтальные (northing / lat)
    val yLines = mutableListOf<RefLine>() // вертикальные (easting / lon)
    private var nextId = 1

    var onXLineTapped: ((yFraction: Float) -> Unit)? = null
    var onYLineTapped: ((xFraction: Float) -> Unit)? = null
    var onQueryTapped: ((xFrac: Float, yFrac: Float) -> Unit)? = null
    var onLineLongPressed: ((isX: Boolean, id: Int) -> Unit)? = null

    private var queryPoint: PointF? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 0f, 1.5f, Color.BLACK)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        color = COLOR_QUERY
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Long-press detection
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPress: Runnable? = null
    private var downX = 0f; private var downY = 0f; private var moved = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun addXLine(yFraction: Float, value: Double): RefLine {
        val line = RefLine(nextId++, yFraction, value)
        xLines.add(line); invalidate(); return line
    }

    fun addYLine(xFraction: Float, value: Double): RefLine {
        val line = RefLine(nextId++, xFraction, value)
        yLines.add(line); invalidate(); return line
    }

    fun updateLineValue(isX: Boolean, id: Int, value: Double) {
        val list = if (isX) xLines else yLines
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) { list[idx] = list[idx].copy(value = value); invalidate() }
    }

    fun removeLineById(isX: Boolean, id: Int) {
        if (isX) xLines.removeAll { it.id == id }
        else yLines.removeAll { it.id == id }
        invalidate()
    }

    fun setQueryPoint(xFrac: Float, yFrac: Float) {
        queryPoint = PointF(xFrac, yFrac); invalidate()
    }

    fun clearQueryPoint() { queryPoint = null; invalidate() }

    fun clearAll() {
        xLines.clear(); yLines.clear(); queryPoint = null; invalidate()
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private fun nearLine(x: Float, y: Float): Pair<Boolean, Int>? {
        if (imageRect.isEmpty) return null
        val r = 55f
        for (line in xLines) {
            val ly = imageRect.top + line.position * imageRect.height()
            if (abs(y - ly) < r && x >= imageRect.left - r && x <= imageRect.right + r)
                return true to line.id
        }
        for (line in yLines) {
            val lx = imageRect.left + line.position * imageRect.width()
            if (abs(x - lx) < r && y >= imageRect.top - r && y <= imageRect.bottom + r)
                return false to line.id
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false
                pendingPress?.let { handler.removeCallbacks(it) }
                pendingPress = Runnable {
                    val found = nearLine(downX, downY)
                    if (found != null) onLineLongPressed?.invoke(found.first, found.second)
                }.also { handler.postDelayed(it, 500) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) + abs(event.y - downY) > 18f) {
                    moved = true
                    pendingPress?.let { handler.removeCallbacks(it) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pendingPress?.let { handler.removeCallbacks(it) }
                if (!moved) {
                    val x = event.x.coerceIn(imageRect.left, imageRect.right)
                    val y = event.y.coerceIn(imageRect.top, imageRect.bottom)
                    val xFrac = (x - imageRect.left) / imageRect.width()
                    val yFrac = (y - imageRect.top) / imageRect.height()
                    when (mode) {
                        Mode.ADD_X -> if (xLines.size < MAX_LINES) onXLineTapped?.invoke(yFrac)
                        Mode.ADD_Y -> if (yLines.size < MAX_LINES) onYLineTapped?.invoke(xFrac)
                        Mode.QUERY -> onQueryTapped?.invoke(xFrac, yFrac)
                    }
                }
                return true
            }
        }
        return false
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.isEmpty) return
        val ascent = -labelPaint.ascent()

        // X lines — horizontal, green
        for (line in xLines) {
            val y = imageRect.top + line.position * imageRect.height()
            linePaint.color = COLOR_X
            canvas.drawLine(imageRect.left, y, imageRect.right, y, linePaint)
            labelPaint.color = COLOR_X
            val label = "X=${fmtVal(line.value)}"
            val textY = if (y - 6f > imageRect.top + ascent) y - 6f else y + ascent + 4f
            canvas.drawText(label, imageRect.left + 6f, textY, labelPaint)
        }

        // Y lines — vertical, orange, label rotated 90° CW
        for (line in yLines) {
            val x = imageRect.left + line.position * imageRect.width()
            linePaint.color = COLOR_Y
            canvas.drawLine(x, imageRect.top, x, imageRect.bottom, linePaint)
            labelPaint.color = COLOR_Y
            val label = "Y=${fmtVal(line.value)}"
            canvas.save()
            // After translate(tx,ty) + rotate(90°): screen = (tx - localY, ty + localX)
            // Setting tx = x + ascent puts text body to the right of the line
            canvas.translate(x + ascent, imageRect.top + 6f)
            canvas.rotate(90f)
            canvas.drawText(label, 0f, 0f, labelPaint)
            canvas.restore()
        }

        // Query crosshair
        val qp = queryPoint ?: return
        val qx = imageRect.left + qp.x * imageRect.width()
        val qy = imageRect.top + qp.y * imageRect.height()
        canvas.drawLine(imageRect.left, qy, imageRect.right, qy, crosshairPaint)
        canvas.drawLine(qx, imageRect.top, qx, imageRect.bottom, crosshairPaint)
        dotPaint.color = COLOR_QUERY
        canvas.drawCircle(qx, qy, 14f, dotPaint)
        dotPaint.color = Color.BLACK
        canvas.drawCircle(qx, qy, 5f, dotPaint)
    }

    private fun fmtVal(v: Double): String =
        if (v == floor(v) && !v.isInfinite()) v.toLong().toString()
        else "%.3f".format(v)
}
