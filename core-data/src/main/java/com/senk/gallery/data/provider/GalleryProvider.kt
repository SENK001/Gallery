package com.senk.gallery.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.senk.gallery.data.album.FavoriteStore

class GalleryProvider : ContentProvider() {

    private var mediaObserver: ContentObserver? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                ctx.contentResolver.notifyChange(GalleryContract.CONTENT_URI, null)
            }
        }
        ctx.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            true,
            observer,
        )
        mediaObserver = observer
        return true
    }

    override fun shutdown() {
        mediaObserver?.let { context?.contentResolver?.unregisterContentObserver(it) }
        mediaObserver = null
        super.shutdown()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val resolver = context?.contentResolver ?: return null
        val cursor = try {
            queryInternal(uri, resolver)
        } catch (e: Exception) {
            Log.e(TAG, "query failed: $uri", e)
            throw e
        }
        cursor?.setNotificationUri(resolver, GalleryContract.CONTENT_URI)
        return cursor
    }

    private fun queryInternal(uri: Uri, resolver: android.content.ContentResolver): Cursor? {
        // 收藏是应用内自建库（FavoriteStore），查询时覆盖 MediaStore 的系统 is_favorite 列。
        // 一次查询内只取一次快照；EXIF 查询用不到，但读取成本很低（内存缓存）。
        val favoriteIds = FavoriteStore.get(context!!).ids()
        return when (MATCHER.match(uri)) {
            CODE_MEDIA_DIR -> MediaStoreFetcher.queryMedia(
                resolver,
                limitOf(uri),
                offsetOf(uri),
                favoriteIds,
            )
            CODE_MEDIA_ITEM -> MediaStoreFetcher.queryMediaById(
                resolver,
                uri.pathSegments[1].toLong(),
                favoriteIds,
            )
            CODE_MEDIA_EXIF -> ExifFetcher.queryExif(
                resolver,
                uri.pathSegments[1].toLong(),
            )
            CODE_ALBUMS -> AlbumResolver.queryAlbums(
                context!!,
                resolver,
                uri.getQueryParameter(GalleryContract.QUERY_CATEGORY)
                    ?: GalleryContract.CATEGORY_COMMON,
            )
            CODE_ALBUM_ITEMS -> AlbumResolver.queryAlbumItems(
                context!!,
                resolver,
                uri.pathSegments[1],
                null,
                null,
                limitOf(uri),
                offsetOf(uri),
                favoriteIds,
            )
            CODE_FOLDER_ITEMS -> AlbumResolver.queryAlbumItems(
                context!!,
                resolver,
                com.senk.gallery.data.entity.MediaSet.TYPE_FOLDER,
                Uri.decode(uri.pathSegments[2]),
                null,
                limitOf(uri),
                offsetOf(uri),
                favoriteIds,
            )
            CODE_APP_ITEMS -> AlbumResolver.queryAlbumItems(
                context!!,
                resolver,
                com.senk.gallery.data.entity.MediaSet.TYPE_THIRD_PARTY,
                null,
                Uri.decode(uri.pathSegments[2]),
                limitOf(uri),
                offsetOf(uri),
                favoriteIds,
            )
            CODE_CUSTOM_ITEMS -> AlbumResolver.queryAlbumItems(
                context!!,
                resolver,
                com.senk.gallery.data.entity.MediaSet.TYPE_CUSTOM,
                Uri.decode(uri.pathSegments[2]),
                null,
                limitOf(uri),
                offsetOf(uri),
                favoriteIds,
            )
            else -> null
        }
    }

    /**
     * 唯一写操作：收藏状态。
     *
     * **不再写系统媒体库**（避免 `RecoverableSecurityException` + 系统授权弹窗），改为写应用内
     * 自建收藏库 [FavoriteStore]；写入后通知根 URI，收藏相册/查看器会刷新。
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (MATCHER.match(uri) != CODE_MEDIA_ITEM) {
            return 0
        }
        val ctx = context ?: return 0
        val id = uri.pathSegments[1].toLongOrNull() ?: return 0
        val favorite = values?.getAsInteger(GalleryContract.Columns.FAVORITE) ?: return 0
        val changed = FavoriteStore.get(ctx).setFavorite(id, favorite == 1)
        if (changed) {
            ctx.contentResolver.notifyChange(GalleryContract.CONTENT_URI, null)
        }
        // 与 ContentResolver.update 语义一致：返回受影响条目数（状态未变化时也可视为 1 条已确认）；
        // 这里只在真正变化时返回 1，调用方不需要据此做额外处理。
        return if (changed) 1 else 0
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        CODE_MEDIA_DIR -> GalleryContract.MIME_MEDIA_DIR
        CODE_MEDIA_ITEM -> GalleryContract.MIME_MEDIA_ITEM
        CODE_MEDIA_EXIF -> GalleryContract.MIME_EXIF_ITEM
        CODE_ALBUMS -> GalleryContract.MIME_ALBUM_DIR
        CODE_ALBUM_ITEMS, CODE_FOLDER_ITEMS, CODE_APP_ITEMS, CODE_CUSTOM_ITEMS ->
            GalleryContract.MIME_MEDIA_DIR
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Insert is not supported")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Delete is not supported")

    private fun limitOf(uri: Uri): Int =
        uri.getQueryParameter(GalleryContract.QUERY_LIMIT)?.toIntOrNull() ?: 0

    private fun offsetOf(uri: Uri): Int =
        uri.getQueryParameter(GalleryContract.QUERY_OFFSET)?.toIntOrNull() ?: 0

    companion object {

        private const val TAG = "GalleryProvider"

        private const val CODE_MEDIA_DIR = 1
        private const val CODE_MEDIA_ITEM = 2
        private const val CODE_MEDIA_EXIF = 3
        private const val CODE_ALBUMS = 4
        private const val CODE_ALBUM_ITEMS = 5
        private const val CODE_FOLDER_ITEMS = 6
        private const val CODE_APP_ITEMS = 7
        private const val CODE_CUSTOM_ITEMS = 8

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(GalleryContract.AUTHORITY, GalleryContract.PATH_MEDIA, CODE_MEDIA_DIR)
            addURI(
                GalleryContract.AUTHORITY,
                "${GalleryContract.PATH_MEDIA}/#",
                CODE_MEDIA_ITEM,
            )
            addURI(
                GalleryContract.AUTHORITY,
                "${GalleryContract.PATH_MEDIA}/#/${GalleryContract.PATH_EXIF}",
                CODE_MEDIA_EXIF,
            )
            addURI(GalleryContract.AUTHORITY, GalleryContract.PATH_ALBUMS, CODE_ALBUMS)
            // UriMatcher 按子节点注册顺序匹配：先注册的通配模式会遮挡后注册的
            // 精确节点，导致多段 URI 匹配失败，因此具体模式必须放在通配模式之前
            addURI(
                GalleryContract.AUTHORITY,
                "${GalleryContract.PATH_ALBUMS}/${GalleryContract.PATH_FOLDER}/*",
                CODE_FOLDER_ITEMS,
            )
            addURI(
                GalleryContract.AUTHORITY,
                "${GalleryContract.PATH_ALBUMS}/${GalleryContract.PATH_APP}/*",
                CODE_APP_ITEMS,
            )
            // 自建相册内容：albums/custom/{albumId}；同样必须注册在通配 albums/* 之前
            addURI(
                GalleryContract.AUTHORITY,
                "${GalleryContract.PATH_ALBUMS}/${GalleryContract.PATH_CUSTOM}/*",
                CODE_CUSTOM_ITEMS,
            )
            addURI(
                GalleryContract.AUTHORITY,
                "${GalleryContract.PATH_ALBUMS}/*",
                CODE_ALBUM_ITEMS,
            )
        }
    }
}
