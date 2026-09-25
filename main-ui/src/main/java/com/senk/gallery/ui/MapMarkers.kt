package com.senk.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory

object MapMarkers {

    private val markerColor = 0xFF2A6BF2.toInt()
    private var cached: BitmapDescriptor? = null

    fun dot(context: Context): BitmapDescriptor {
        cached?.let { return it }
        val density = context.resources.displayMetrics.density
        val size = (18 * density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = markerColor
        canvas.drawCircle(size / 2f, size / 2f, size * 0.32f, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap).also { cached = it }
    }
}
