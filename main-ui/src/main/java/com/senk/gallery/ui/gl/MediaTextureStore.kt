package com.senk.gallery.ui.gl

import android.graphics.Bitmap
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class MediaTextureStore(maxBytes: Int = DEFAULT_MAX_BYTES) {

    data class TextureEntry(val textureId: Int, val width: Int, val height: Int)

    private data class UploadRequest(val key: Long, val bitmap: Bitmap)

    private val cache = object : LruCache<Long, TextureEntry>(maxBytes) {
        override fun sizeOf(key: Long, value: TextureEntry): Int =
            (value.width * value.height * 4).coerceAtLeast(1)

        override fun entryRemoved(
            evicted: Boolean,
            key: Long,
            oldValue: TextureEntry,
            newValue: TextureEntry?,
        ) {
            pendingDeletes.add(oldValue.textureId)
        }
    }

    private val uploadQueue = ConcurrentLinkedQueue<UploadRequest>()
    private val pendingDeletes = ConcurrentLinkedQueue<Int>()
    private val failedKeys: MutableSet<Long> =
        Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    fun textureOf(key: Long): TextureEntry? = cache.get(key)

    fun isFailed(key: Long): Boolean = failedKeys.contains(key)

    fun markFailed(key: Long) {
        failedKeys.add(key)
    }

    fun enqueue(key: Long, bitmap: Bitmap) {
        if (cache.get(key) != null || failedKeys.contains(key)) {
            bitmap.recycle()
            return
        }
        uploadQueue.add(UploadRequest(key, bitmap))
    }

    fun drainUploads() {
        var request = uploadQueue.poll()
        while (request != null) {
            val bitmap = request.bitmap
            if (bitmap.isRecycled) {
                request = uploadQueue.poll()
                continue
            }
            val existing = cache.get(request.key)
            if (existing == null) {
                val textureId = GlUtils.uploadTexture(bitmap)
                cache.put(
                    request.key,
                    TextureEntry(textureId, bitmap.width, bitmap.height),
                )
            }
            bitmap.recycle()
            request = uploadQueue.poll()
        }
        var textureId = pendingDeletes.poll()
        while (textureId != null) {
            GlUtils.deleteTexture(textureId)
            textureId = pendingDeletes.poll()
        }
    }

    fun clear() {
        uploadQueue.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        uploadQueue.clear()
        val snapshot = ArrayList<Long>()
        val iterator = cache.snapshot().keys.iterator()
        while (iterator.hasNext()) {
            snapshot.add(iterator.next())
        }
        snapshot.forEach { cache.remove(it) }
        var textureId = pendingDeletes.poll()
        while (textureId != null) {
            GlUtils.deleteTexture(textureId)
            textureId = pendingDeletes.poll()
        }
        failedKeys.clear()
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 32 * 1024 * 1024
    }
}
