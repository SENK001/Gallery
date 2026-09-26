package com.senk.gallery.ui.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class BadgeCache(private val density: Float = 1.5f, maxEntries: Int = 48) {

    data class TextTexture(val textureId: Int, val width: Int, val height: Int)

    private val cache = object : LruCache<String, Int>(maxEntries) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Int,
            newValue: Int?,
        ) {
            pendingDeletes.add(oldValue)
        }
    }

    private val pendingDeletes = ConcurrentLinkedQueue<Int>()

    fun videoInfo(text: String): TextTexture {
        val key = "info:$text"
        val existing = cache.get(key)
        if (existing != null) {
            return textSizes[key] ?: TextTexture(existing, 1, 1)
        }
        val texture = createVideoInfo(text)
        cache.put(key, texture.textureId)
        textSizes[key] = texture
        return texture
    }

    fun bottomGradient(): Int = cache.get(KEY_GRADIENT) ?: createBottomGradient().also {
        cache.put(KEY_GRADIENT, it)
    }

    fun motionBadge(): TextTexture {
        val existing = cache.get(KEY_MOTION)
        if (existing != null) {
            return textSizes[KEY_MOTION] ?: TextTexture(existing, 1, 1)
        }
        val texture = createMotionBadge()
        cache.put(KEY_MOTION, texture.textureId)
        textSizes[KEY_MOTION] = texture
        return texture
    }

    fun playIcon(): Int = cache.get(KEY_PLAY) ?: createPlayIcon().also {
        cache.put(KEY_PLAY, it)
    }

    fun emptyText(text: String): TextTexture {
        val key = "text:$text"
        val existing = cache.get(key)
        if (existing != null) {
            return textSizes[key] ?: TextTexture(existing, 1, 1)
        }
        val texture = createTextTexture(text)
        cache.put(key, texture.textureId)
        textSizes[key] = texture
        return texture
    }

    fun drainDeletes() {
        var textureId = pendingDeletes.poll()
        while (textureId != null) {
            GlUtils.deleteTexture(textureId)
            textureId = pendingDeletes.poll()
        }
    }

    fun evictAll() {
        cache.evictAll()
        textSizes.clear()
        drainDeletes()
    }

    private val textSizes = HashMap<String, TextTexture>()

    private fun createBottomGradient(): Int {
        val width = 4
        val height = 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                0x00000000,
                0xB3000000.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return GlUtils.uploadTexture(bitmap).also { bitmap.recycle() }
    }

    private fun createVideoInfo(text: String): TextTexture {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val iconSize = 12f * density
        val strokeWidth = 1.3f * density
        val gap = 3f * density
        val width = ceil(iconSize + gap + textPaint.measureText(text)).toInt().coerceAtLeast(2)
        val height = ceil(textHeight.coerceAtLeast(iconSize)).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerX = iconSize / 2f
        val centerY = height / 2f
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        canvas.drawCircle(centerX, centerY, (iconSize - strokeWidth) / 2f, ringPaint)
        val halfWidth = iconSize * 0.16f
        val halfHeight = iconSize * 0.19f
        val triangleCenterX = centerX + iconSize * 0.04f
        val path = Path().apply {
            moveTo(triangleCenterX - halfWidth, centerY - halfHeight)
            lineTo(triangleCenterX - halfWidth, centerY + halfHeight)
            lineTo(triangleCenterX + halfWidth, centerY)
            close()
        }
        canvas.drawPath(path, textPaint)
        val baseline = (height - textHeight) / 2f - metrics.ascent
        canvas.drawText(text, iconSize + gap, baseline, textPaint)
        val textureId = GlUtils.uploadTexture(bitmap)
        bitmap.recycle()
        return TextTexture(textureId, width, height)
    }

    private fun createMotionBadge(): TextTexture {
        val iconSize = 16f * density
        val unit = iconSize / 1024f
        val dotCount = 36
        val orbitRadius = 428f * unit
        val dotRadius = 21.7f * unit
        val ringOuter = 326.65f * unit
        val ringInner = 279.6f * unit
        val centerOuter = 169.4f * unit
        val centerInner = 83.4f * unit
        val shadowRadius = 1.5f * density
        val padding = shadowRadius + density
        val width = ceil(iconSize + padding * 2).toInt().coerceAtLeast(2)
        val height = width
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = width / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(shadowRadius, 0f, 0.5f * density, 0x59000000)
        }
        for (i in 0 until dotCount) {
            val angle = Math.PI * 2.0 * i / dotCount - Math.PI / 2.0
            canvas.drawCircle(
                center + (orbitRadius * cos(angle)).toFloat(),
                center + (orbitRadius * sin(angle)).toFloat(),
                dotRadius,
                paint,
            )
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringOuter - ringInner
        canvas.drawCircle(center, center, (ringOuter + ringInner) / 2f, paint)
        paint.strokeWidth = centerOuter - centerInner
        canvas.drawCircle(center, center, (centerOuter + centerInner) / 2f, paint)
        val textureId = GlUtils.uploadTexture(bitmap)
        bitmap.recycle()
        return TextTexture(textureId, width, height)
    }

    private fun createPlayIcon(): Int {
        val size = ceil(64f * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val center = size / 2f
        val halfHeight = size * 0.17f
        val halfWidth = size * 0.13f
        val path = Path().apply {
            moveTo(center - halfWidth, center - halfHeight)
            lineTo(center - halfWidth, center + halfHeight)
            lineTo(center + halfWidth * 1.4f, center)
            close()
        }
        canvas.drawPath(path, paint)
        return GlUtils.uploadTexture(bitmap).also { bitmap.recycle() }
    }

    private fun createTextTexture(text: String): TextTexture {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8A000000.toInt()
            textSize = 15f * density
            textAlign = Paint.Align.LEFT
        }
        val metrics = paint.fontMetrics
        val padding = 8f * density
        val width = ceil(paint.measureText(text) + padding * 2).toInt().coerceAtLeast(2)
        val height = ceil(metrics.descent - metrics.ascent + padding * 2).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        canvas.drawText(text, padding, padding - metrics.ascent, paint)
        val textureId = GlUtils.uploadTexture(bitmap)
        bitmap.recycle()
        return TextTexture(textureId, width, height)
    }

    private companion object {
        const val KEY_MOTION = "motion"
        const val KEY_PLAY = "play"
        const val KEY_GRADIENT = "bottom_gradient"
    }
}
