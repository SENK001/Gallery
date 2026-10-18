package com.senk.gallery.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 收藏（应用内自建库）的 SQLite 存储。
 *
 * 表结构（v2）：
 * ```sql
 * CREATE TABLE favorites (
 *   media_id   INTEGER PRIMARY KEY NOT NULL,  -- MediaStore 媒体 id
 *   created_at INTEGER NOT NULL,              -- 收藏时间（毫秒），用于稳定排序
 *   is_trashed INTEGER NOT NULL DEFAULT 0     -- 删除标记：1 = 媒体已进回收站/删除
 * )
 * ```
 *
 * `is_trashed` 的语义（见 `FavoriteStore`）：
 * - 媒体被删除（进系统回收站）后，收藏行不立即删除，而是打上删除标记；
 *   收藏相册的数量/封面/内容查询只统计未标记的行，避免「封面变灰、点进去空白」。
 * - 媒体从回收站恢复后清除标记，收藏状态自动恢复。
 * - 彻底删除（`createDeleteRequest`）时直接删除收藏行。
 *
 * 设计说明：
 * - 只保存媒体 id，不复制媒体文件、不修改系统媒体库（系统 `is_favorite` 不再作为数据源）。
 * - 数据库文件在应用私有目录（`databases/gallery_favorites.db`），跨进程重启持久化。
 * - **不做旧数据兼容**：收藏从 SharedPreferences 方案切到 SQLite 属开发期换方案，
 *   旧 SharedPreferences 里的收藏数据直接丢弃（不迁移、不读取）。
 *
 * 单例使用（[get]），与 `FavoriteStore` 一一对应；SQLite 自身负责并发串行化。
 */
internal class FavoriteDb private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_FAVORITES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "onUpgrade $oldVersion -> $newVersion")
        // v1 -> v2：增加删除标记列（ALTER 保留既有收藏数据；开发期也不做旧 prefs 兼容）。
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COLUMN_IS_TRASHED " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    companion object {

        private const val TAG = "FavoriteDb"

        /** 数据库名（应用私有目录 `databases/` 下）。 */
        const val DB_NAME = "gallery_favorites.db"

        /** 当前 schema 版本；升级时在 [onUpgrade] 做迁移。 */
        private const val VERSION = 2

        /** 收藏表名。 */
        const val TABLE = "favorites"

        /** 媒体 id 列。 */
        const val COLUMN_MEDIA_ID = "media_id"

        /** 收藏时间列（毫秒）。 */
        const val COLUMN_CREATED_AT = "created_at"

        /** 删除标记列（1 = 媒体已被删除/进回收站，不再计入收藏）。 */
        const val COLUMN_IS_TRASHED = "is_trashed"

        private const val CREATE_FAVORITES =
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COLUMN_MEDIA_ID INTEGER PRIMARY KEY NOT NULL, " +
                "$COLUMN_CREATED_AT INTEGER NOT NULL, " +
                "$COLUMN_IS_TRASHED INTEGER NOT NULL DEFAULT 0)"

        @Volatile
        private var instance: FavoriteDb? = null

        /** 进程内单例（同一数据库文件只保留一个 SQLiteOpenHelper）。 */
        fun get(context: Context): FavoriteDb =
            instance ?: synchronized(this) {
                instance ?: FavoriteDb(context.applicationContext).also { instance = it }
            }
    }
}
