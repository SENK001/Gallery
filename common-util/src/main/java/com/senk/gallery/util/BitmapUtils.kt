package com.senk.gallery.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

object BitmapUtils {

    /**
     * Samples a stream down so that the decoded bitmap never exceeds [maxWidth] x [maxHeight].
     * The stream must support [InputStream.reset] or be opened twice.
     */
    fun decodeSampled(
        openStream: () -> InputStream?,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maxWidth,
                maxHeight,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Int {
        var sampleSize = 1
        if (maxWidth <= 0 || maxHeight <= 0) {
            return sampleSize
        }
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sampleSize >= maxWidth && halfHeight / sampleSize >= maxHeight) {
            sampleSize *= 2
            halfWidth = width / 2
            halfHeight = height / 2
        }
        return sampleSize.coerceAtLeast(1)
    }
}
