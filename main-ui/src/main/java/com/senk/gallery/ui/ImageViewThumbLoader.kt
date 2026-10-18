package com.senk.gallery.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.senk.gallery.ui.theme.ThemeUtils
import java.util.concurrent.Executors

/**
 * ImageView 缩略图加载器（相册封面、宫格格子共用）。
 *
 * 设计要点：
 * - **带 LRU Bitmap 缓存**：宫格滚动/多选刷新会反复绑同一批格子，解码结果按
 *   「uri + 目标边长」缓存，避免每次重绑都 `loadThumbnail`（旧实现每次都重新解码）。
 * - **tag 防复用错位**：`imageView.tag` 记录本次请求的缓存键；异步解码回来时
 *   若 View 已被复用给别的条目，直接丢弃结果。
 * - 占位色走 [ThemeUtils.surfacePlaceholder]，深色模式不会白块闪烁。
 * - 4 线程解码，`loadThumbnail` 由系统媒体库负责采样/旋转，尺寸上限由调用方给。
 *
 * 注意：缓存按屏幕内尺寸取整，避免每个像素值都产生一个缓存键。
 */
object ImageViewThumbLoader {

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 缓存上限 = 可用堆的 1/8，按 Bitmap 实际字节数计。 */
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 加载缩略图。
     *
     * @param uriString 媒体 content URI；为空时只铺占位色。
     * @param sizePx 目标边长（像素）。内部会取整到 32 的倍数再按缓存查找。
     */
    fun load(imageView: ImageView, uriString: String?, sizePx: Int = 256) {
        val bucket = bucketSize(sizePx)
        val key = if (uriString.isNullOrEmpty()) "" else "$uriString@$bucket"
        imageView.tag = key
        imageView.setBackgroundColor(ThemeUtils.surfacePlaceholder(imageView.context))
        if (uriString.isNullOrEmpty()) {
            imageView.setImageBitmap(null)
            return
        }
        val cached = cache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageBitmap(null)
        executor.execute {
            // 同一张图在排队期间可能已被别的格子解码进缓存，这里再查一次
            val bitmap: Bitmap? = cache.get(key) ?: try {
                imageView.context.contentResolver.loadThumbnail(
                    Uri.parse(uriString),
                    Size(bucket, bucket),
                    null,
                )
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                cache.put(key, bitmap)
            }
            mainHandler.post {
                if (imageView.tag == key) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** 清空缓存（内存紧张/宿主销毁通知时可由调用方触发）。 */
    fun clearCache() {
        cache.evictAll()
    }

    /** 目标尺寸取整到 32px 的倍数：同尺寸档位共用缓存，避免逐像素键膨胀。 */
    private fun bucketSize(sizePx: Int): Int =
        ((sizePx.coerceIn(96, 1024) + 31) / 32) * 32
}
