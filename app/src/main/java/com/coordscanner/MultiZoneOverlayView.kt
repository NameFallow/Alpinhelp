package com.coordscanner

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class MultiZoneOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class ZoneType { X, Y }

    data class Zone(val id: Int, val type: ZoneType, val rect: RectF)

    companion object {
        val COLOR_X = 0xFF4CAF50.toInt()  // зелёный — X
        val COLOR_Y = 0xFFE53935.toInt()  // красный  — Y
        const val MAX_PER_TYPE = 20

        fun colorFor(type: ZoneType) = if (type == ZoneType.X) COLOR_X else COLOR_Y
    }

    var imageRect = RectF()

    var activeType: ZoneType? = null
        set(value) {
            field = value
            drawingRect = null
            invalidate()
            onStateChanged?.invoke()
        }

    val zones = mutableListOf<Zone>()
    private var nextId = 1
    private var dragStart = PointF()
    private var drawingRect: RectF? = null

    var onZoneAdded: ((Zone) -> Unit)? = null
    var onZoneLongPressed: ((Zone) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    // Long press for delete when not drawing
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var downX = 0f; private var downY = 0f; private var moved = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val liveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 38f; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun xZones(): List<Zone> = zones.filter { it.type == ZoneType.X }
    fun yZones(): List<Zone> = zones.filter { it.type == ZoneType.Y }
    fun xRects(): List<RectF> = xZones().map { it.rect }
    fun yRects(): List<RectF> = yZones().map { it.rect }

    fun canAdd(type: ZoneType) =
        zones.count { it.type == type } < MAX_PER_TYPE

    fun hasMinimum() = xZones().isNotEmpty() && yZones().isNotEmpty()

    fun removeZone(id: Int) {
        zones.removeAll { it.id == id }
        invalidate()
        onStateChanged?.invoke()
    }

    fun clearAll() {
        zones.clear()
        activeType = null
        drawingRect = null
        invalidate()
    }

    // ── Touch ──────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false

        if (activeType == null) {
            // Not drawing — only handle long press to delete
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; moved = false
                    pending?.let { handler.removeCallbacks(it) }
                    pending = Runnable {
                        val z = zones.firstOrNull { it.rect.contains(downX, downY) }
                        if (z != null) onZoneLongPressed?.invoke(z)
                    }.also { handler.postDelayed(it, 500) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) + abs(event.y - downY) > 15f) {
                        moved = true; pending?.let { handler.removeCallbacks(it) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    return true
                }
            }
            return false
        }

        // Drawing mode
        val x = event.x.coerceIn(imageRect.left, imageRect.right)
        val y = event.y.coerceIn(imageRect.top, imageRect.bottom)
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
                val type = activeType
                drawingRect = null
                if (rect != null && type != null && rect.width() > 20f && rect.height() > 20f) {
                    val zone = Zone(nextId++, type, RectF(rect))
                    zones.add(zone)
                    onZoneAdded?.invoke(zone)
                }
                activeType = null
            }
        }
        return true
    }

    // ── Draw ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val xList = xZones()
        val yList = yZones()

        for ((idx, zone) in xList.withIndex()) drawZone(canvas, zone, "X${idx + 1}", COLOR_X)
        for ((idx, zone) in yList.withIndex()) drawZone(canvas, zone, "Y${idx + 1}", COLOR_Y)

        val live = drawingRect ?: return
        val color = activeType?.let { colorFor(it) } ?: Color.WHITE
        fillPaint.color = (color and 0x00FFFFFF) or (0x22 shl 24)
        canvas.drawRect(live, fillPaint)
        liveBorderPaint.color = color
        canvas.drawRect(live, liveBorderPaint)
    }

    private fun drawZone(canvas: Canvas, zone: Zone, label: String, color: Int) {
        val r = zone.rect
        fillPaint.color = (color and 0x00FFFFFF) or (0x33 shl 24)
        canvas.drawRect(r, fillPaint)
        borderPaint.color = color
        canvas.drawRect(r, borderPaint)
        labelPaint.color = color
        val tw = labelPaint.measureText(label)
        canvas.drawText(label, r.left + (r.width() - tw) / 2f, r.top + 48f, labelPaint)
    }
}
