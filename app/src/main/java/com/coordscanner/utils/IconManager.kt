package com.coordscanner.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.ImageView
import com.caverock.androidsvg.SVG

class IconManager(private val context: Context) {

    val builtInIcons: List<String> by lazy {
        context.assets.list("alpinequest_icons")?.toList()?.sorted() ?: emptyList()
    }

    fun getIconBytes(iconName: String): ByteArray? =
        try {
            context.assets.open("alpinequest_icons/$iconName").use { it.readBytes() }
        } catch (e: Exception) {
            null
        }

    fun findIcon(name: String): String? =
        builtInIcons.firstOrNull { it.equals(name, ignoreCase = true) }

    fun findIconFuzzy(name: String): String? {
        val lower = name.lowercase()
        return builtInIcons.firstOrNull {
            it.lowercase().contains(lower) ||
                lower.contains(it.substringBeforeLast('.').lowercase())
        }
    }

    // Рендерит иконку в ImageView; для SVG включает программный рендеринг
    fun loadInto(iconName: String, imageView: ImageView) {
        val bytes = getIconBytes(iconName) ?: return
        if (iconName.endsWith(".svg", ignoreCase = true)) {
            try {
                val svg = SVG.getFromInputStream(bytes.inputStream())
                imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                imageView.setImageDrawable(PictureDrawable(svg.renderToPicture()))
            } catch (_: Exception) {}
        } else {
            imageView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
    }

    // Возвращает Bitmap для кэша в фоновом потоке (не вызывать на Main thread)
    fun renderBitmap(iconName: String, sizePx: Int): Bitmap? {
        val bytes = getIconBytes(iconName) ?: return null
        return try {
            if (iconName.endsWith(".svg", ignoreCase = true)) {
                val svg = SVG.getFromInputStream(bytes.inputStream())
                val picture: Picture = svg.renderToPicture(sizePx, sizePx)
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawPicture(picture)
                bmp
            } else {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                }
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                if (raw.width == sizePx && raw.height == sizePx) raw
                else Bitmap.createScaledBitmap(raw, sizePx, sizePx, true)
            }
        } catch (_: Exception) {
            null
        }
    }
}
