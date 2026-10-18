package com.senk.gallery.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

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
 * - **一次性迁移**：旧版本曾用 SharedPreferences（`gallery_favorites` / `favorites_json`）
 *   保存收藏，首次创建本库时把旧数据导入并删除旧键；导入失败不崩溃（保留旧键以便排查）。
 *
 * 单例使用（[get]），与 `FavoriteStore` 一一对应；SQLite 自身负责并发串行化。
 */
internal class FavoriteDb private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    VERSION,
) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_FAVORITES)
        migrateLegacySharedPreferences(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "onUpgrade $oldVersion -> $newVersion")
        // v1 为初始版本；后续版本只做 ALTER TABLE / 数据迁移，不要 DROP 用户数据。
    }

    /**
     * 旧版收藏存在 SharedPreferences（JSON）里：首次建库时导入一次。
     *
     * 迁移是幂等的（`INSERT OR IGNORE`）；成功后删除旧键，避免以后数据库被重建时
     * 用过期数据覆盖。解析失败只记日志、保留旧键，不阻塞应用启动。
     */
    private fun migrateLegacySharedPreferences(db: SQLiteDatabase) {
        val prefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(LEGACY_PREFS_KEY, null) ?: return
        try {
            val ids = JSONObject(raw).optJSONArray(LEGACY_JSON_IDS)
            if (ids == null) {
                Log.w(TAG, "legacy favorites json has no ids array")
                return
            }
            var imported = 0
            val now = System.currentTimeMillis()
            for (i in 0 until ids.length()) {
                val id = ids.optLong(i, 0L)
                if (id <= 0L) {
                    continue
                }
                val values = ContentValues().apply {
                    put(COLUMN_MEDIA_ID, id)
                    put(COLUMN_CREATED_AT, now + i)
                }
                if (db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    imported++
                }
            }
            prefs.edit().remove(LEGACY_PREFS_KEY).apply()
            Log.i(TAG, "migrated $imported legacy favorites to sqlite")
        } catch (e: Exception) {
            Log.e(TAG, "legacy favorites migration failed", e)
        }
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

        // 旧版本 SharedPreferences 迁移参数（只读，不新增写入路径）。
        private const val LEGACY_PREFS_NAME = "gallery_favorites"
        private const val LEGACY_PREFS_KEY = "favorites_json"
        private const val LEGACY_JSON_IDS = "ids"

        @Volatile
        private var instance: FavoriteDb? = null

        /** 进程内单例（同一数据库文件只保留一个 SQLiteOpenHelper）。 */
        fun get(context: Context): FavoriteDb =
            instance ?: synchronized(this) {
                instance ?: FavoriteDb(context.applicationContext).also { instance = it }
            }
    }
}
