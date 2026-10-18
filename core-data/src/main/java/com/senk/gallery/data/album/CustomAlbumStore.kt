package com.senk.gallery.data.album

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 自建相册的持久化存储。
 *
 * 自建相册是**应用内逻辑集合**：只记录媒体条目 ID 与相册元信息，不复制、不移动任何文件。
 * 因此它天然跨目录：同一张照片可以同时属于多个自建相册，删除相册也不会影响照片本身。
 *
 * 存储介质：SharedPreferences（`gallery_custom_albums`），内容为**版本化 JSON**：
 * ```json
 * {"version":1,"albums":[{"id":"uuid","name":"名称","item_ids":[1,2],"cover_uri":null,"created_at":0}]}
 * ```
 * - 所有公开方法都在同一把锁内读写，可安全地被 UI 线程与 Provider 的 Binder 线程同时调用；
 * - JSON 损坏（格式非法、字段缺失）时**回退为空列表**并记日志，不抛异常、不清空已有数据
 *   （下一次写入会覆盖成合法内容）；
 * - 相册顺序 = 创建顺序；条目顺序 = 加入顺序（内容查询按该顺序展示）。
 */
class CustomAlbumStore private constructor(context: Context) {

    /** 一条自建相册记录。 [itemIds] 为加入顺序的 MediaStore `_id` 列表（已去重）。 */
    data class CustomAlbum(
        val id: String,
        val name: String,
        val itemIds: List<Long>,
        val coverUri: String?,
        val createdAt: Long,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读写锁：所有方法都在这把锁内完成「读-改-写」，避免 UI 与 Provider 并发写丢更新。 */
    private val lock = Any()

    /** 全部自建相册（创建顺序）。 */
    fun albums(): List<CustomAlbum> = synchronized(lock) { read() }

    fun album(id: String): CustomAlbum? =
        synchronized(lock) { read().firstOrNull { it.id == id } }

    fun containsName(name: String): Boolean {
        val normalized = normalizeName(name) ?: return false
        return synchronized(lock) {
            read().any { it.name.equals(normalized, ignoreCase = true) }
        }
    }

    /**
     * 新建相册。
     *
     * @return 新建的相册；名称为空/超长/含控制字符或**重名**时返回 null（调用方据此提示）。
     */
    fun create(name: String): CustomAlbum? {
        val normalized = normalizeName(name) ?: return null
        return synchronized(lock) {
            val current = read()
            if (current.any { it.name.equals(normalized, ignoreCase = true) }) {
                return@synchronized null
            }
            val album = CustomAlbum(
                id = UUID.randomUUID().toString(),
                name = normalized,
                itemIds = emptyList(),
                coverUri = null,
                createdAt = System.currentTimeMillis(),
            )
            if (!write(current + album)) {
                return@synchronized null
            }
            album
        }
    }

    /** 重命名；新名非法或与其它相册重名时返回 false。 */
    fun rename(id: String, name: String): Boolean {
        val normalized = normalizeName(name) ?: return false
        return synchronized(lock) {
            val current = read()
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) {
                return@synchronized false
            }
            if (current.any { it.id != id && it.name.equals(normalized, ignoreCase = true) }) {
                return@synchronized false
            }
            if (current[index].name == normalized) {
                return@synchronized true
            }
            val updated = current.toMutableList()
            updated[index] = updated[index].copy(name = normalized)
            write(updated)
        }
    }

    /** 删除相册（只删逻辑集合，**不删照片**）。 */
    fun delete(id: String): Boolean = synchronized(lock) {
        val current = read()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) {
            return@synchronized false
        }
        write(updated)
    }

    /**
     * 向相册追加条目（去重、保持加入顺序）。
     *
     * @param coverUri 相册尚无封面时写入该 URI（通常是本次加入的第一张的封面）。
     * @return 实际新增的条目数；相册不存在或条目全部已在相册中时返回 0。
     */
    fun addItems(albumId: String, mediaIds: List<Long>, coverUri: String? = null): Int =
        synchronized(lock) {
            val current = read()
            val index = current.indexOfFirst { it.id == albumId }
            if (index < 0) {
                return@synchronized 0
            }
            val album = current[index]
            val known = album.itemIds.toHashSet()
            val added = ArrayList<Long>()
            mediaIds.forEach { id ->
                if (id > 0L && known.add(id)) {
                    added.add(id)
                }
            }
            if (added.isEmpty()) {
                return@synchronized 0
            }
            val updated = current.toMutableList()
            updated[index] = album.copy(
                itemIds = album.itemIds + added,
                coverUri = album.coverUri ?: coverUri,
            )
            if (!write(updated)) 0 else added.size
        }

    /** 从相册移除条目（不改动照片文件）。 @return 实际移除的数量。 */
    fun removeItems(albumId: String, mediaIds: List<Long>): Int = synchronized(lock) {
        val current = read()
        val index = current.indexOfFirst { it.id == albumId }
        if (index < 0) {
            return@synchronized 0
        }
        val album = current[index]
        val removeIds = mediaIds.toHashSet()
        val kept = album.itemIds.filterNot { it in removeIds }
        val removed = album.itemIds.size - kept.size
        if (removed <= 0) {
            return@synchronized 0
        }
        val updated = current.toMutableList()
        updated[index] = album.copy(
            itemIds = kept,
            // 相册被删空时清空封面；只移除部分条目时保留旧封面（查询侧会在封面临近失效时兜底重查）。
            coverUri = if (kept.isEmpty()) null else album.coverUri,
        )
        if (!write(updated)) 0 else removed
    }

    // ---------------- JSON / 名称校验 ----------------

    private fun read(): List<CustomAlbum> {
        val raw = prefs.getString(KEY_DATA, null) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val array = root.optJSONArray(KEY_ALBUMS) ?: return emptyList()
            val albums = ArrayList<CustomAlbum>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString(KEY_ID, "").trim()
                if (id.isEmpty()) {
                    continue
                }
                val name = normalizeName(obj.optString(KEY_NAME, "")) ?: continue
                val itemIds = ArrayList<Long>()
                val idsArray = obj.optJSONArray(KEY_ITEM_IDS)
                if (idsArray != null) {
                    val seen = HashSet<Long>()
                    for (j in 0 until idsArray.length()) {
                        val value = idsArray.optLong(j, 0L)
                        if (value > 0L && seen.add(value)) {
                            itemIds.add(value)
                        }
                    }
                }
                val coverUri = obj.optString(KEY_COVER_URI, "").takeIf { it.isNotBlank() }
                albums.add(
                    CustomAlbum(
                        id = id,
                        name = name,
                        itemIds = itemIds,
                        coverUri = coverUri,
                        createdAt = obj.optLong(KEY_CREATED_AT, 0L),
                    ),
                )
            }
            albums
        } catch (e: Exception) {
            // 坏数据（手改 SharedPreferences、旧版本格式等）回退空列表，不崩溃、不清空
            Log.e(TAG, "custom album data is corrupted, fallback to empty list", e)
            emptyList()
        }
    }

    private fun write(albums: List<CustomAlbum>): Boolean = try {
        val array = JSONArray()
        albums.forEach { album ->
            array.put(
                JSONObject()
                    .put(KEY_ID, album.id)
                    .put(KEY_NAME, album.name)
                    .put(KEY_ITEM_IDS, JSONArray(album.itemIds))
                    .put(KEY_COVER_URI, album.coverUri ?: "")
                    .put(KEY_CREATED_AT, album.createdAt),
            )
        }
        val json = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_ALBUMS, array)
        prefs.edit().putString(KEY_DATA, json.toString()).commit()
    } catch (e: Exception) {
        Log.e(TAG, "failed to persist custom albums", e)
        false
    }

    /**
     * 名称规范化：去首尾空白、限制长度、拒绝控制字符。
     *
     * @return 规范化后的名称；不合法返回 null（调用方按「创建失败/重名」提示）。
     */
    private fun normalizeName(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            return null
        }
        if (name.any { Character.isISOControl(it) }) {
            return null
        }
        return name
    }

    companion object {

        private const val TAG = "CustomAlbumStore"

        private const val PREFS_NAME = "gallery_custom_albums"
        private const val KEY_DATA = "albums_json"
        private const val KEY_VERSION = "version"
        private const val KEY_ALBUMS = "albums"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_ITEM_IDS = "item_ids"
        private const val KEY_COVER_URI = "cover_uri"
        private const val KEY_CREATED_AT = "created_at"

        /** 当前数据结构版本；升级格式时递增并在 [read] 里做迁移。 */
        private const val VERSION = 1

        /** 名称最大长度（字符数，按 UTF-16 code unit 计）。 */
        const val MAX_NAME_LENGTH = 40

        @Volatile
        private var instance: CustomAlbumStore? = null

        /** 进程内单例（同一 SharedPreferences 只能有一个写入口，避免并发丢更新）。 */
        fun get(context: Context): CustomAlbumStore =
            instance ?: synchronized(this) {
                instance ?: CustomAlbumStore(context.applicationContext).also { instance = it }
            }
    }
}
