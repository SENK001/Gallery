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
 * （`media_id INTEGER PRIMARY KEY`、`created_at INTEGER`、`is_trashed INTEGER`）。
 *
 * **删除标记（`is_trashed`）**：
 * - 媒体被移入系统回收站后，收藏行保留但标记 `is_trashed = 1`；
 *   [ids] / [count] / [isFavorite] 都只看未标记的行，避免收藏相册出现
 *   「封面变灰、点进去空白」。
 * - 媒体从回收站恢复时调用 [markTrashed] 清除标记，收藏自动恢复。
 * - 彻底删除时调用 [removeIds] 移除收藏行。
 * - 其它应用/系统删除媒体时，`AlbumResolver.reconcileFavoriteFlags` 会在相册页刷新时
 *   校正标记（已收藏但不可见 → 标记；已标记但重新可见 → 清除）。
 */
class FavoriteStore private constructor(context: Context) {

    private val db: FavoriteDb = FavoriteDb.get(context)

    /** 全部**有效**收藏 id（不含已删除的标记行；调用方不要依赖顺序）。 */
    fun ids(): Set<Long> = idsWhere(FavoriteDb.COLUMN_IS_TRASHED + " = 0")

    /** 已标记为删除的收藏 id（媒体进回收站/被删除，但收藏行暂时保留）。 */
    fun trashedIds(): Set<Long> = idsWhere(FavoriteDb.COLUMN_IS_TRASHED + " = 1")

    private fun idsWhere(where: String): Set<Long> {
        val ids = LinkedHashSet<Long>()
        db.readableDatabase.rawQuery(
            "SELECT ${FavoriteDb.COLUMN_MEDIA_ID} FROM ${FavoriteDb.TABLE} " +
                "WHERE $where " +
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
            "SELECT COUNT(*) FROM ${FavoriteDb.TABLE} " +
                "WHERE ${FavoriteDb.COLUMN_MEDIA_ID} = ? " +
                "AND ${FavoriteDb.COLUMN_IS_TRASHED} = 0",
            arrayOf(id.toString()),
        ) > 0L
    }

    /** 有效收藏数量（不含删除标记行）。 */
    fun count(): Int = DatabaseUtils.longForQuery(
        db.readableDatabase,
        "SELECT COUNT(*) FROM ${FavoriteDb.TABLE} WHERE ${FavoriteDb.COLUMN_IS_TRASHED} = 0",
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
            insertFavorite(database, id, System.currentTimeMillis()) != -1L
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
                    insertFavorite(database, id, now + index) != -1L
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

    /**
     * 批量设置删除标记（媒体进回收站 = true；从回收站恢复 = false）。
     *
     * 只更新已存在的收藏行；未收藏的媒体不受影响。
     *
     * @return 实际发生变化的行数。
     */
    fun markTrashed(mediaIds: Collection<Long>, trashed: Boolean): Int {
        val validIds = mediaIds.filter { it > 0L }.distinct()
        if (validIds.isEmpty()) {
            return 0
        }
        val target = if (trashed) 1 else 0
        val database = db.writableDatabase
        var changed = 0
        database.beginTransaction()
        try {
            validIds.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                val placeholders = chunk.joinToString(", ") { "?" }
                val values = ContentValues().apply {
                    put(FavoriteDb.COLUMN_IS_TRASHED, target)
                }
                changed += database.update(
                    FavoriteDb.TABLE,
                    values,
                    "${FavoriteDb.COLUMN_MEDIA_ID} IN ($placeholders) " +
                        "AND ${FavoriteDb.COLUMN_IS_TRASHED} != ?",
                    (chunk.map { it.toString() } + target.toString()).toTypedArray(),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return changed
    }

    /**
     * 彻底删除媒体后移除收藏行（不保留删除标记，避免数据库长期积累无效行）。
     *
     * @return 实际删除的行数。
     */
    fun removeIds(mediaIds: Collection<Long>): Int {
        val validIds = mediaIds.filter { it > 0L }.distinct()
        if (validIds.isEmpty()) {
            return 0
        }
        val database = db.writableDatabase
        var removed = 0
        database.beginTransaction()
        try {
            validIds.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                val placeholders = chunk.joinToString(", ") { "?" }
                removed += database.delete(
                    FavoriteDb.TABLE,
                    "${FavoriteDb.COLUMN_MEDIA_ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return removed
    }

    /**
     * 插入收藏：已存在时不重复插入；若已存在但带删除标记（媒体恢复后重新收藏），
     * 则清除标记并返回变化条数（positive 表示有变化）。
     *
     * @return 新插入或清除标记时返回行变化数（>0），无变化返回 -1（对齐插入失败语义）。
     */
    private fun insertFavorite(database: SQLiteDatabase, id: Long, createdAt: Long): Long {
        val values = ContentValues().apply {
            put(FavoriteDb.COLUMN_MEDIA_ID, id)
            put(FavoriteDb.COLUMN_CREATED_AT, createdAt)
            put(FavoriteDb.COLUMN_IS_TRASHED, 0)
        }
        val inserted = database.insertWithOnConflict(
            FavoriteDb.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted != -1L) {
            return inserted
        }
        // 已存在：如果只是被标记为删除，清除标记即视为重新收藏成功
        val restored = database.update(
            FavoriteDb.TABLE,
            ContentValues().apply { put(FavoriteDb.COLUMN_IS_TRASHED, 0) },
            "${FavoriteDb.COLUMN_MEDIA_ID} = ? AND ${FavoriteDb.COLUMN_IS_TRASHED} = 1",
            arrayOf(id.toString()),
        )
        return if (restored > 0) id else -1L
    }

    companion object {

        private const val SQL_CHUNK_SIZE = 500

        @Volatile
        private var instance: FavoriteStore? = null

        /** 进程内单例（数据库本身也是单例，见 [FavoriteDb.get]）。 */
        fun get(context: Context): FavoriteStore =
            instance ?: synchronized(this) {
                instance ?: FavoriteStore(context.applicationContext).also { instance = it }
            }
    }
}
