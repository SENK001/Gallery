package com.senk.gallery.data.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.FileNotFoundException

/**
 * 对外选图结果的**代理 Provider**（`content://com.senk.gallery.external/media/{id}`）。
 *
 * 背景：外部应用调用本应用选图后，若直接把 MediaStore 原始 URI（`content://media/...`）返回给调用方，
 * 调用方既没有 `READ_MEDIA_IMAGES/VIDEO`，也拿不到跨应用的持久授权；而把 MediaStore URI 逐个
 * `grantUriPermission` 给外部应用又需要 `MANAGE_*`/`GRANT_*` 等本应用不应持有的权限。
 * 因此这里在本应用自己的 authority 下做一层**只读代理**：
 * - 返回给调用方的是本 Provider 的 URI，`ExternalEntryActivity` 通过 `grantUriPermission`
 *   临时把该 URI 的读权限授予调用方；
 * - 真正读文件时由本 Provider（运行在本应用进程/UID）去访问 MediaStore，使用本应用自己的媒体权限。
 *
 * 安全边界：
 * - Manifest 中 `exported=false` + `grantUriPermissions=true`：第三方**不能**直接读写本 Provider，
 *   只有拿到临时授权的调用方能访问；授权由外部调用路由按「用户实际选择的那几个 id」逐个授予，
 *   因此本 Provider **不提供目录列举**（URI 必须精确到 `media/{id}`）。
 * - 只读：insert / update / delete 一律不支持。
 * - id 非数字 / 不存在 / 无权限时返回 null 或抛 [FileNotFoundException]（不向外泄漏内部异常/崩溃）。
 *
 * URI 与返回约定见 `docs/external-api.md`；选图返回的映射逻辑见 `main` 的 `ExternalEntryActivity`。
 */
class ExternalMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val id = idOf(uri) ?: return null
        val context = context ?: return null
        return try {
            MediaStoreFetcher.queryMediaById(context.contentResolver, id).use { source ->
                if (!source.moveToFirst()) {
                    return@use null
                }
                val cursor = MatrixCursor(QUERY_COLUMNS)
                cursor.addRow(
                    arrayOf(
                        id,
                        source.getString(
                            source.getColumnIndexOrThrow(GalleryContract.Columns.NAME),
                        ),
                        source.getString(
                            source.getColumnIndexOrThrow(GalleryContract.Columns.MIME_TYPE),
                        ),
                        source.getLong(
                            source.getColumnIndexOrThrow(GalleryContract.Columns.SIZE),
                        ),
                        source.getInt(
                            source.getColumnIndexOrThrow(GalleryContract.Columns.MEDIA_TYPE),
                        ),
                    ),
                )
                cursor
            }
        } catch (e: Exception) {
            Log.w(TAG, "query failed: $uri", e)
            null
        }
    }

    override fun getType(uri: Uri): String? {
        val id = idOf(uri) ?: return null
        val context = context ?: return null
        return try {
            MediaStoreFetcher.queryMediaById(context.contentResolver, id).use { source ->
                if (!source.moveToFirst()) {
                    return@use null
                }
                source.getString(
                    source.getColumnIndexOrThrow(GalleryContract.Columns.MIME_TYPE),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "getType failed: $uri", e)
            null
        }
    }

    /**
     * 打开代理 URI 对应的媒体文件。
     *
     * 用本进程（已获得媒体权限）的 ContentResolver 去 openFileDescriptor 真实的 MediaStore URI，
     * 返回其 [ParcelFileDescriptor]；调用方 `openInputStream` / `openFileDescriptor` 即可读取字节。
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = idOf(uri) ?: throw FileNotFoundException("unsupported uri: $uri")
        if (mode != "r" && mode != "rt") {
            // 本 Provider 只读：写模式直接拒绝，避免被当作写入通道。
            throw SecurityException("ExternalMediaProvider is read-only: mode=$mode")
        }
        val context = context ?: throw FileNotFoundException("provider not attached")
        val mediaUri = mediaUriOf(id) ?: throw FileNotFoundException("unknown media id: $id")
        return try {
            context.contentResolver.openFileDescriptor(mediaUri, "r")
                ?: throw FileNotFoundException("cannot open media id: $id")
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            // 无权限 / 文件被删除 / MediaProvider 异常：统一表现为 FileNotFoundException（调用方可见），
            // 详细原因只写日志，避免把内部异常类型与堆栈泄漏给外部应用。
            Log.w(TAG, "openFile failed: $uri", e)
            throw FileNotFoundException("cannot read media id: $id")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("ExternalMediaProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("ExternalMediaProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ExternalMediaProvider is read-only")

    /** 解析并校验 URI：只接受 `content://<authority>/media/{正整数 id}`，多段/非法 id 一律 null。 */
    private fun idOf(uri: Uri): Long? {
        if (uri.scheme != "content" || uri.authority != AUTHORITY) {
            return null
        }
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != PATH_MEDIA) {
            return null
        }
        return segments[1].toLongOrNull()?.takeIf { it > 0L }
    }

    /** 按 id 查 MediaStore 得到真实媒体 URI（图片/视频集合下的 `content://media/.../{id}`）。 */
    private fun mediaUriOf(id: Long): Uri? {
        val context = context ?: return null
        return try {
            MediaStoreFetcher.queryMediaById(context.contentResolver, id).use { source ->
                if (!source.moveToFirst()) {
                    return@use null
                }
                val raw = source.getString(
                    source.getColumnIndexOrThrow(GalleryContract.Columns.URI),
                ) ?: return@use null
                Uri.parse(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve media uri failed: id=$id", e)
            null
        }
    }

    companion object {

        /** 与 core-data Manifest 中 `<provider android:authorities>` 保持一致。 */
        const val AUTHORITY = "com.senk.gallery.external"

        /** URI 路径段：`media/{id}`。 */
        const val PATH_MEDIA = "media"

        private const val TAG = "ExternalMediaProvider"

        /** Provider 根 URI：`content://com.senk.gallery.external/media`。 */
        private val BASE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_MEDIA")

        /**
         * 查询列：与系统 `OpenableColumns` 命名对齐（`display_name` / `size`），
         * 调用方按系统惯例查询时可拿到名称与大小。
         */
        private val QUERY_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )

        /**
         * 把本应用内部的媒体 id 映射为对外代理 URI。
         *
         * 注意：这里只做字符串拼接，不查库；id 的来源应是用户实际选中的媒体（由
         * `ExternalEntryActivity` 从选图结果读取），并会对调用方逐个授权。
         *
         * @param id MediaStore 媒体 id（必须为正数）。
         */
        fun proxyUri(id: Long): Uri {
            require(id > 0L) { "media id must be positive: $id" }
            return ContentUris.withAppendedId(BASE_URI, id)
        }
    }
}
