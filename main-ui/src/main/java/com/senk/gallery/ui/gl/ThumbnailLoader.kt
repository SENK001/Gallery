package com.senk.gallery.ui.gl

import android.content.Context
import android.os.CancellationSignal
import android.util.Size
import com.senk.gallery.data.entity.MediaItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ThumbnailLoader(
    context: Context,
    private val store: MediaTextureStore,
    private val onUploaded: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val executor = Executors.newFixedThreadPool(4)
    private val inFlight = ConcurrentHashMap<Long, CancellationSignal>()

    fun request(item: MediaItem, sizePx: Int) {
        val uri = item.uri ?: return
        if (store.textureOf(item.id) != null || store.isFailed(item.id)) {
            return
        }
        if (inFlight.containsKey(item.id)) {
            return
        }
        val signal = CancellationSignal()
        if (inFlight.putIfAbsent(item.id, signal) != null) {
            return
        }
        executor.execute {
            try {
                val size = sizePx.coerceIn(96, 1024)
                val bitmap = appContext.contentResolver.loadThumbnail(
                    uri,
                    Size(size, size),
                    signal,
                )
                store.enqueue(item.id, bitmap)
                onUploaded()
            } catch (e: Exception) {
                if (!signal.isCanceled) {
                    store.markFailed(item.id)
                }
            } finally {
                inFlight.remove(item.id)
            }
        }
    }

    fun shutdown() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        executor.shutdownNow()
    }
}
