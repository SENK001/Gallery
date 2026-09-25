package com.senk.gallery.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

object ImageViewThumbLoader {

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(imageView: ImageView, uriString: String?, sizePx: Int = 256) {
        val tag = uriString ?: ""
        imageView.tag = tag
        if (uriString.isNullOrEmpty()) {
            imageView.setImageBitmap(null)
            imageView.setBackgroundColor(0xFFEEEEEE.toInt())
            return
        }
        imageView.setBackgroundColor(0xFFEEEEEE.toInt())
        imageView.setImageBitmap(null)
        executor.execute {
            val bitmap: Bitmap? = try {
                imageView.context.contentResolver.loadThumbnail(
                    Uri.parse(uriString),
                    Size(sizePx, sizePx),
                    null,
                )
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                if (imageView.tag == tag) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }
}
