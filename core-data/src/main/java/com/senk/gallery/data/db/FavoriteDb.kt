package com.senk.gallery.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 收藏（应用内自建库）的 SQLite 存储。
 *
 * 表结构（v1）：
 * ```sql
 * CREATE TABLE favorites (
 *   media_id   INTEGER PRIMARY KEY NOT NULL,  -- MediaStore 媒体 id
 *   created_at INTEGER NOT NULL               -- 收藏时间（毫秒），用于稳定排序
 * )
 * ```
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
        // v1 为初始版本；后续版本只做 ALTER TABLE / 数据迁移，不要 DROP 用户数据。
    }

    companion object {

        private const val TAG = "FavoriteDb"

        /** 数据库名（应用私有目录 `databases/` 下）。 */
        const val DB_NAME = "gallery_favorites.db"

        /** 当前 schema 版本；升级时在 [onUpgrade] 做迁移。 */
        private const val VERSION = 1

        /** 收藏表名。 */
        const val TABLE = "favorites"

        /** 媒体 id 列。 */
        const val COLUMN_MEDIA_ID = "media_id"

        /** 收藏时间列（毫秒）。 */
        const val COLUMN_CREATED_AT = "created_at"

        private const val CREATE_FAVORITES =
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COLUMN_MEDIA_ID INTEGER PRIMARY KEY NOT NULL, " +
                "$COLUMN_CREATED_AT INTEGER NOT NULL)"

        @Volatile
        private var instance: FavoriteDb? = null

        /** 进程内单例（同一数据库文件只保留一个 SQLiteOpenHelper）。 */
        fun get(context: Context): FavoriteDb =
            instance ?: synchronized(this) {
                instance ?: FavoriteDb(context.applicationContext).also { instance = it }
            }
    }
}
