package com.coordscanner

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

/**
 * Полигональное лассо: пользователь тапает по карте, каждая точка — вершина полигона.
 * Карта продолжает работать на pan/zoom — overlay перехватывает только короткие тапы
 * без сдвига пальца (одиночный tap).
 *
 * API: addVertex (внешний вызов не нужен — overlay сам ловит тапы), undoLastVertex,
 * clear, getVertices. Применение выделения и закрытие режима — Activity, через
 * чтение getVertices() и вызов callback'а вручную.
 */
class MapPolygonOverlay(
    private val onVertexAdded: () -> Unit,
) : Overlay() {

    private val vertices = mutableListOf<GeoPoint>()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 200, 0)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 180, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 200, 0)
        style = Paint.Style.FILL
    }
    private val vertexStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun getVertices(): List<GeoPoint> = vertices.toList()
    fun vertexCount(): Int = vertices.size

    fun clear() {
        vertices.clear()
    }

    fun undoLastVertex(): Boolean {
        if (vertices.isEmpty()) return false
        vertices.removeAt(vertices.lastIndex)
        return true
    }

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        // Тап-слоп берём из контекста карты — viewconfig зависит от плотности экрана.
        val slop = ViewConfiguration.get(map.context).scaledTouchSlop
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downTime = e.eventTime; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) moved = true
            }
            MotionEvent.ACTION_UP -> {
                val dt = e.eventTime - downTime
                if (!moved && dt < ViewConfiguration.getTapTimeout() + 100L) {
                    val gp = map.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                    vertices.add(gp)
                    map.invalidate()
                    onVertexAdded()
                    return true
                }
            }
        }
        return false
    }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || vertices.isEmpty()) return
        val proj = map.projection
        val pts = vertices.map { gp ->
            val p = Point(); proj.toPixels(gp, p); p
        }

        if (pts.size >= 3) {
            val path = Path()
            path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until pts.size) path.lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        } else if (pts.size == 2) {
            canvas.drawLine(
                pts[0].x.toFloat(), pts[0].y.toFloat(),
                pts[1].x.toFloat(), pts[1].y.toFloat(),
                strokePaint
            )
        }

        for (p in pts) {
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, vertexPaint)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, vertexStroke)
        }
    }
}
