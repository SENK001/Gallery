package com.senk.gallery.data.album

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏（**应用内自建库**）的持久化存储。
 *
 * 设计背景：
 * - 系统媒体库的 `is_favorite` 对第三方应用写回需要用户逐次授权（Android 12+ 抛
 *   `RecoverableSecurityException`，弹系统授权框）；批量收藏体验差，部分设备上还会直接失败。
 * - 因此收藏改为**应用私有数据**：只保存 MediaStore 媒体 id，不复制文件、不修改系统媒体库。
 * - 读取媒体时由 [com.senk.gallery.data.provider.GalleryProvider] 用本库的值**覆盖**
 *   `is_favorite` 列，「收藏」相册的成员/数量/封面也由本库计算（见 `AlbumResolver`），
 *   从而查看器爱心、多选收藏、收藏相册三处状态一致。
 *
 * 存储：SharedPreferences `gallery_favorites`，版本化 JSON：`{"version":1,"ids":[1,2,3]}`。
 * 线程安全：所有读写在同一把锁内；内存缓存避免每次查询都解析 JSON；坏数据回退空集合不崩溃。
 */
class FavoriteStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    /** 内存缓存（进程内唯一写入口，写入时同步更新；避免每次查询都解析 JSON）。 */
    private var cache: LinkedHashSet<Long>? = null

    /** 全部收藏 id（只读副本，调用方不要依赖顺序）。 */
    fun ids(): Set<Long> = synchronized(lock) { LinkedHashSet(current()) }

    fun isFavorite(id: Long): Boolean = synchronized(lock) { current().contains(id) }

    fun count(): Int = synchronized(lock) { current().size }

    /**
     * 设置单条收藏状态。
     *
     * @return 是否发生变化（已是目标状态时返回 false，不写盘、不触发刷新）。
     */
    fun setFavorite(id: Long, favorite: Boolean): Boolean {
        if (id <= 0L) {
            return false
        }
        return synchronized(lock) {
            val current = current()
            val changed = if (favorite) current.add(id) else current.remove(id)
            if (changed) {
                write(current)
            }
            changed
        }
    }

    /**
     * 批量设置收藏状态。
     *
     * @return 实际发生变化的条数（0 表示全部已是目标状态）。
     */
    fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean): Int {
        val validIds = mediaIds.filter { it > 0L }
        if (validIds.isEmpty()) {
            return 0
        }
        return synchronized(lock) {
            val current = current()
            var changed = 0
            validIds.forEach { id ->
                val itemChanged = if (favorite) current.add(id) else current.remove(id)
                if (itemChanged) {
                    changed++
                }
            }
            if (changed > 0) {
                write(current)
            }
            changed
        }
    }

    /** 当前缓存（懒加载：首次访问从 SharedPreferences 读一次）。调用方必须持有 [lock]。 */
    private fun current(): LinkedHashSet<Long> {
        cache?.let { return it }
        val loaded = read()
        cache = loaded
        return loaded
    }

    private fun read(): LinkedHashSet<Long> {
        val raw = prefs.getString(KEY_DATA, null) ?: return LinkedHashSet()
        return try {
            val root = JSONObject(raw)
            val array = root.optJSONArray(KEY_IDS)
            val ids = LinkedHashSet<Long>(array?.length() ?: 0)
            if (array != null) {
                for (i in 0 until array.length()) {
                    val id = array.optLong(i, 0L)
                    if (id > 0L) {
                        ids.add(id)
                    }
                }
            }
            ids
        } catch (e: Exception) {
            // 坏数据（手改 SharedPreferences / 旧格式）回退空集合，不崩溃
            Log.e(TAG, "favorite data is corrupted, fallback to empty set", e)
            LinkedHashSet()
        }
    }

    private fun write(ids: Set<Long>): Boolean = try {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        val json = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_IDS, array)
        prefs.edit().putString(KEY_DATA, json.toString()).commit()
    } catch (e: Exception) {
        Log.e(TAG, "failed to persist favorites", e)
        false
    }

    companion object {

        private const val TAG = "FavoriteStore"

        private const val PREFS_NAME = "gallery_favorites"
        private const val KEY_DATA = "favorites_json"
        private const val KEY_VERSION = "version"
        private const val KEY_IDS = "ids"

        /** 当前数据结构版本；升级格式时递增并在 [read] 里做迁移。 */
        private const val VERSION = 1

        @Volatile
        private var instance: FavoriteStore? = null

        /** 进程内单例（与 CustomAlbumStore 同理：同一 SharedPreferences 只保留一个写入口）。 */
        fun get(context: Context): FavoriteStore =
            instance ?: synchronized(this) {
                instance ?: FavoriteStore(context.applicationContext).also { instance = it }
            }
    }
}
