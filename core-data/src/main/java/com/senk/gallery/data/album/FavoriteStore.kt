package com.senk.gallery.data.album

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.senk.gallery.data.db.FavoriteDb

/**
 * 收藏（**应用内自建库**）的读写入口，底层使用 **SQLite**（见 [FavoriteDb]）。
 *
 * 设计背景：
 * - 系统媒体库的 `is_favorite` 对第三方应用写回需要用户逐次授权（Android 12+ 抛
 *   `RecoverableSecurityException`，弹系统授权框）；批量收藏体验差，部分设备上还会直接失败。
 * - 因此收藏改为**应用私有数据**：只保存 MediaStore 媒体 id，不复制文件、不修改系统媒体库。
 * - 读取媒体时由 [com.senk.gallery.data.provider.GalleryProvider] 用本库的值**覆盖**
 *   `is_favorite` 列，「收藏」相册的成员/数量/封面也由本库计算（见 `AlbumResolver`），
 *   从而查看器爱心、多选收藏、收藏相册三处状态一致。
 *
 * 存储：SQLite 数据库 `gallery_favorites.db` 的 `favorites` 表
 * （`media_id INTEGER PRIMARY KEY`、`created_at INTEGER`）；旧版 SharedPreferences 数据
 * 在首次建库时由 [FavoriteDb] 自动导入。SQLite 自身保证并发安全，类内不再额外加锁。
 */
class FavoriteStore private constructor(context: Context) {

    private val db: FavoriteDb = FavoriteDb.get(context)

    /** 全部收藏 id（只读副本，顺序为收藏时间升序；调用方不要依赖顺序）。 */
    fun ids(): Set<Long> {
        val ids = LinkedHashSet<Long>()
        db.readableDatabase.rawQuery(
            "SELECT ${FavoriteDb.COLUMN_MEDIA_ID} FROM ${FavoriteDb.TABLE} " +
                "ORDER BY ${FavoriteDb.COLUMN_CREATED_AT} ASC, ${FavoriteDb.COLUMN_MEDIA_ID} ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0))
            }
        }
        return ids
    }

    fun isFavorite(id: Long): Boolean {
        if (id <= 0L) {
            return false
        }
        return DatabaseUtils.longForQuery(
            db.readableDatabase,
            "SELECT COUNT(*) FROM ${FavoriteDb.TABLE} WHERE ${FavoriteDb.COLUMN_MEDIA_ID} = ?",
            arrayOf(id.toString()),
        ) > 0L
    }

    fun count(): Int = DatabaseUtils.longForQuery(
        db.readableDatabase,
        "SELECT COUNT(*) FROM ${FavoriteDb.TABLE}",
        null,
    ).toInt()

    /**
     * 设置单条收藏状态。
     *
     * @return 是否发生变化（已是目标状态时返回 false，不触发刷新）。
     */
    fun setFavorite(id: Long, favorite: Boolean): Boolean {
        if (id <= 0L) {
            return false
        }
        val database = db.writableDatabase
        return if (favorite) {
            val values = ContentValues().apply {
                put(FavoriteDb.COLUMN_MEDIA_ID, id)
                put(FavoriteDb.COLUMN_CREATED_AT, System.currentTimeMillis())
            }
            database.insertWithOnConflict(
                FavoriteDb.TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        } else {
            database.delete(
                FavoriteDb.TABLE,
                "${FavoriteDb.COLUMN_MEDIA_ID} = ?",
                arrayOf(id.toString()),
            ) > 0
        }
    }

    /**
     * 批量设置收藏状态（单事务，避免逐条提交）。
     *
     * @return 实际发生变化的条数（0 表示全部已是目标状态）。
     */
    fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean): Int {
        val validIds = mediaIds.filter { it > 0L }.distinct()
        if (validIds.isEmpty()) {
            return 0
        }
        val database = db.writableDatabase
        database.beginTransaction()
        return try {
            var changed = 0
            val now = System.currentTimeMillis()
            validIds.forEachIndexed { index, id ->
                val itemChanged = if (favorite) {
                    val values = ContentValues().apply {
                        put(FavoriteDb.COLUMN_MEDIA_ID, id)
                        put(FavoriteDb.COLUMN_CREATED_AT, now + index)
                    }
                    database.insertWithOnConflict(
                        FavoriteDb.TABLE,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                } else {
                    database.delete(
                        FavoriteDb.TABLE,
                        "${FavoriteDb.COLUMN_MEDIA_ID} = ?",
                        arrayOf(id.toString()),
                    ) > 0
                }
                if (itemChanged) {
                    changed++
                }
            }
            database.setTransactionSuccessful()
            changed
        } finally {
            database.endTransaction()
        }
    }

    companion object {

        @Volatile
        private var instance: FavoriteStore? = null

        /** 进程内单例（数据库本身也是单例，见 [FavoriteDb.get]）。 */
        fun get(context: Context): FavoriteStore =
            instance ?: synchronized(this) {
                instance ?: FavoriteStore(context.applicationContext).also { instance = it }
            }
    }
}
