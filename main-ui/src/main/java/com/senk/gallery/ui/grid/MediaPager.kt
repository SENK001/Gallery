package com.senk.gallery.ui.grid

import android.content.Context
import android.net.Uri
import android.util.Log
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.data.provider.GalleryCursorReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 宫格分页数据源 —— 照片页、相册详情页、选图页共用的「一页 200 条」加载逻辑。
 *
 * 设计要点：
 * - 查询在 [Dispatchers.IO] 内完成（本类不暴露 ContentResolver，宿主无需自己切线程）。
 * - [load] 返回「数据是否有更新」：宿主据此决定是否重建列表、是否回顶。
 * - 只用 id/uri 之外的 [MediaItem] 快照；重置后旧列表整体替换，不做就地合并。
 * - 并发保护：同一时刻只允许一个 [load] 在跑，重复调用直接返回 false（宿主滚动回调会频繁触发）。
 */
class MediaPager(
    context: Context,
    baseUri: Uri,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {

    private val resolver = context.applicationContext.contentResolver
    private val lock = Any()
    private val data = ArrayList<MediaItem>()

    @Volatile
    private var baseUri: Uri = baseUri

    @Volatile
    private var endReached = false

    @Volatile
    private var loading = false

    /**
     * 加载一页。
     *
     * @param reset true = 从第 0 条重新加载并替换当前列表（内容变化/首次加载）；
     *              false = 从当前列表末尾追加下一页。
     * @return 数据是否有更新（查询失败、正在加载、已到底且非 reset 时返回 false）。
     */
    suspend fun load(reset: Boolean): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (loading) {
                return@withContext false
            }
            if (!reset && endReached) {
                return@withContext false
            }
            loading = true
        }
        try {
            val uri = baseUri
            if (uri == Uri.EMPTY) {
                return@withContext false
            }
            val offset = if (reset) {
                0
            } else {
                synchronized(lock) { data.size }
            }
            val pageUri = GalleryContract.withPage(uri, pageSize, offset)
            val result = try {
                resolver.query(pageUri, null, null, null, null)?.use {
                    GalleryCursorReader.readMediaList(it)
                } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "page query failed: $pageUri", e)
                return@withContext false
            }
            synchronized(lock) {
                if (reset) {
                    data.clear()
                }
                data.addAll(result)
                endReached = result.size < pageSize
            }
            // reset 一定算「有更新」：即使结果为空，宿主要据此把空态/回顶逻辑跑一遍
            reset || result.isNotEmpty()
        } finally {
            synchronized(lock) { loading = false }
        }
    }

    /** 切换数据源（会清空已加载数据，调用方随后应 `load(reset = true)`）。 */
    fun setBaseUri(uri: Uri) {
        synchronized(lock) {
            baseUri = uri
            data.clear()
            endReached = false
        }
    }

    /** 当前已加载的条目快照（副本，调用方可安全遍历）。 */
    fun items(): List<MediaItem> = synchronized(lock) { ArrayList(data) }

    fun endReached(): Boolean = endReached

    fun isLoading(): Boolean = loading

    /** 清空分页状态（保留数据源 URI），供宿主销毁/重进时复用同一实例。 */
    fun clear() {
        synchronized(lock) {
            data.clear()
            endReached = false
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 200
        private const val TAG = "MediaPager"
    }
}
