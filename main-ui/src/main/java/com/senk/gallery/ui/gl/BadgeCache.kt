package com.senk.gallery.ui.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil

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

    fun videoBadge(text: String): Int = cache.get("video:$text") ?: createVideoBadge(text).also {
        cache.put("video:$text", it)
    }

    fun motionBadge(): Int = cache.get(KEY_MOTION) ?: createMotionBadge().also {
        cache.put(KEY_MOTION, it)
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

    private fun createVideoBadge(text: String): Int {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
        }
        val triangleWidth = 7f * density
        val padding = 5f * density
        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val width = ceil(padding * 2 + triangleWidth + 4f * density + textWidth).toInt()
        val height = ceil(padding * 2 + textHeight).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        val radius = 3f * density
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radius,
            radius,
            bgPaint,
        )
        val path = Path().apply {
            moveTo(padding, padding + textHeight * 0.15f)
            lineTo(padding, height - padding - textHeight * 0.15f)
            lineTo(padding + triangleWidth, height / 2f)
            close()
        }
        canvas.drawPath(path, textPaint)
        val baseline = (height - (metrics.descent + metrics.ascent)) / 2f - padding * 0.2f
        canvas.drawText(text, padding + triangleWidth + 4f * density, baseline, textPaint)
        return GlUtils.uploadTexture(bitmap).also { bitmap.recycle() }
    }

    private fun createMotionBadge(): Int {
        val size = ceil(14f * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
        }
        val inset = 3.6f * density
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - inset, ringPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(size / 2f, size / 2f, 1.6f * density, dotPaint)
        return GlUtils.uploadTexture(bitmap).also { bitmap.recycle() }
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
    }
}
