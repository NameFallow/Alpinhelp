package com.coordscanner

import android.graphics.*
import android.view.MotionEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class MapSelectionOverlay(
    private val onBoxReady: (BoundingBox) -> Unit
) : Overlay() {

    private var p1: GeoPoint? = null
    private var p2: GeoPoint? = null
    private var drawing = false

    private val fillPaint = Paint().apply {
        color = Color.argb(50, 255, 200, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.argb(220, 255, 200, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                p1 = GeoPoint(proj.fromPixels(e.x.toInt(), e.y.toInt()))
                p2 = p1; drawing = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                p2 = GeoPoint(proj.fromPixels(e.x.toInt(), e.y.toInt()))
                map.invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!drawing) return false
                p2 = GeoPoint(proj.fromPixels(e.x.toInt(), e.y.toInt()))
                drawing = false
                buildBox()?.let { onBoxReady(it) }
                map.invalidate()
                return true
            }
        }
        return false
    }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val a = p1 ?: return
        val b = p2 ?: return
        val proj = map.projection
        val pa = android.graphics.Point()
        val pb = android.graphics.Point()
        proj.toPixels(a, pa)
        proj.toPixels(b, pb)
        val rect = RectF(
            minOf(pa.x, pb.x).toFloat(), minOf(pa.y, pb.y).toFloat(),
            maxOf(pa.x, pb.x).toFloat(), maxOf(pa.y, pb.y).toFloat()
        )
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, borderPaint)
    }

    private fun buildBox(): BoundingBox? {
        val a = p1 ?: return null
        val b = p2 ?: return null
        if (a.latitude == b.latitude && a.longitude == b.longitude) return null
        return BoundingBox(
            maxOf(a.latitude, b.latitude),
            maxOf(a.longitude, b.longitude),
            minOf(a.latitude, b.latitude),
            minOf(a.longitude, b.longitude)
        )
    }

    fun reset() { p1 = null; p2 = null; drawing = false }
}
