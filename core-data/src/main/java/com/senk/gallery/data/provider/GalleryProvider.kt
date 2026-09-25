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

    private fun queryInternal(uri: Uri, resolver: android.content.ContentResolver): Cursor? =
        when (MATCHER.match(uri)) {
            CODE_MEDIA_DIR -> MediaStoreFetcher.queryMedia(
                resolver,
                limitOf(uri),
                offsetOf(uri),
            )
            CODE_MEDIA_ITEM -> MediaStoreFetcher.queryMediaById(
                resolver,
                uri.pathSegments[1].toLong(),
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
                resolver,
                uri.pathSegments[1],
                null,
                null,
                limitOf(uri),
                offsetOf(uri),
            )
            CODE_FOLDER_ITEMS -> AlbumResolver.queryAlbumItems(
                resolver,
                com.senk.gallery.data.entity.MediaSet.TYPE_FOLDER,
                Uri.decode(uri.pathSegments[2]),
                null,
                limitOf(uri),
                offsetOf(uri),
            )
            CODE_APP_ITEMS -> AlbumResolver.queryAlbumItems(
                resolver,
                com.senk.gallery.data.entity.MediaSet.TYPE_THIRD_PARTY,
                null,
                Uri.decode(uri.pathSegments[2]),
                limitOf(uri),
                offsetOf(uri),
            )
            else -> null
        }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (MATCHER.match(uri) != CODE_MEDIA_ITEM) {
            return 0
        }
        val resolver = context?.contentResolver ?: return 0
        val id = uri.pathSegments[1].toLong()
        val favorite = values?.getAsInteger(GalleryContract.Columns.FAVORITE) ?: return 0
        val mediaUri = MediaStoreFetcher.queryItems(
            resolver,
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(id.toString()),
            limit = 1,
        ).firstOrNull()?.uri ?: return 0
        val updateValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_FAVORITE, favorite)
        }
        val count = resolver.update(mediaUri, updateValues, null, null)
        if (count > 0) {
            resolver.notifyChange(GalleryContract.CONTENT_URI, null)
        }
        return count
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        CODE_MEDIA_DIR -> GalleryContract.MIME_MEDIA_DIR
        CODE_MEDIA_ITEM -> GalleryContract.MIME_MEDIA_ITEM
        CODE_MEDIA_EXIF -> GalleryContract.MIME_EXIF_ITEM
        CODE_ALBUMS -> GalleryContract.MIME_ALBUM_DIR
        CODE_ALBUM_ITEMS, CODE_FOLDER_ITEMS, CODE_APP_ITEMS -> GalleryContract.MIME_MEDIA_DIR
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
            addURI(
                GalleryContract.AUTHORITY,
                "${GalleryContract.PATH_ALBUMS}/*",
                CODE_ALBUM_ITEMS,
            )
        }
    }
}
