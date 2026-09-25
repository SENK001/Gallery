package com.senk.gallery.data.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.entity.MediaObject

object MediaStoreFetcher {

    val COLLECTION: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    val MEDIA_TYPE_SELECTION: String = run {
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($image, $video)"
    }

    val VISIBLE_SELECTION: String =
        "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"

    val BASE_SELECTION: String = "$MEDIA_TYPE_SELECTION AND $VISIBLE_SELECTION"

    val SORT_DATE_DESC: String =
        "COALESCE(${MediaStore.MediaColumns.DATE_TAKEN}, ${MediaStore.MediaColumns.DATE_MODIFIED} * 1000) DESC"

    private val QUERY_PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.ORIENTATION,
        MediaStore.MediaColumns.IS_FAVORITE,
        MediaStore.MediaColumns.IS_TRASHED,
        MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.XMP,
    )

    private val SCAN_PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
    )

    data class BucketInfo(
        val bucketId: String,
        val bucketName: String,
        val relativePath: String,
        val ownerPackage: String?,
        var count: Int = 0,
        var hasImage: Boolean = false,
        var hasVideo: Boolean = false,
        var coverId: Long = 0L,
        var coverDate: Long = 0L,
    ) {
        val coverUri: Uri?
            get() = if (coverId == 0L) null else {
                ContentUris.withAppendedId(COLLECTION, coverId)
            }
    }

    fun queryMedia(resolver: ContentResolver, limit: Int, offset: Int): Cursor {
        val cursor = resolver.query(
            COLLECTION,
            QUERY_PROJECTION,
            queryArgs(BASE_SELECTION, null, SORT_DATE_DESC, limit, offset),
            null,
        )
        val result = MatrixCursor(GalleryContract.Columns.ALL)
        cursor?.use {
            while (it.moveToNext()) {
                result.addRow(rowOf(readItem(it)))
            }
        }
        return result
    }

    fun queryMediaById(resolver: ContentResolver, id: Long): Cursor {
        val cursor = resolver.query(
            COLLECTION,
            QUERY_PROJECTION,
            queryArgs(
                "${MediaStore.MediaColumns._ID} = ?",
                arrayOf(id.toString()),
                null,
            ),
            null,
        )
        val result = MatrixCursor(GalleryContract.Columns.ALL)
        cursor?.use {
            if (it.moveToNext()) {
                result.addRow(rowOf(readItem(it)))
            }
        }
        return result
    }

    fun queryItems(
        resolver: ContentResolver,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String = SORT_DATE_DESC,
        limit: Int = 0,
        offset: Int = 0,
    ): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        resolver.query(
            COLLECTION,
            QUERY_PROJECTION,
            queryArgs(selection, selectionArgs, sortOrder, limit, offset),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                items.add(readItem(cursor))
            }
        }
        return items
    }

    fun queryItemsCursor(
        resolver: ContentResolver,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
        offset: Int,
    ): Cursor {
        val items = queryItems(resolver, selection, selectionArgs, SORT_DATE_DESC, limit, offset)
        return itemsCursor(items)
    }

    fun count(resolver: ContentResolver, selection: String?, selectionArgs: Array<String>?): Int {
        try {
            resolver.query(
                COLLECTION,
                arrayOf("count(*)"),
                queryArgs(selection, selectionArgs, null),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0)
                }
            }
            return 0
        } catch (e: Exception) {
            // Fallback for providers that do not support aggregate projection.
            resolver.query(
                COLLECTION,
                arrayOf(MediaStore.MediaColumns._ID),
                queryArgs(selection, selectionArgs, null),
                null,
            )?.use { cursor ->
                return cursor.count
            }
        }
        return 0
    }

    fun latestItem(
        resolver: ContentResolver,
        selection: String?,
        selectionArgs: Array<String>?,
    ): MediaItem? =
        queryItems(resolver, selection, selectionArgs, SORT_DATE_DESC, limit = 1).firstOrNull()

    fun scanBuckets(resolver: ContentResolver): List<BucketInfo> {
        val buckets = LinkedHashMap<String, BucketInfo>()
        resolver.query(
            COLLECTION,
            SCAN_PROJECTION,
            queryArgs(BASE_SELECTION, null, null),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val ownerIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdIndex) ?: continue
                var info = buckets[bucketId]
                if (info == null) {
                    info = BucketInfo(
                        bucketId = bucketId,
                        bucketName = cursor.getString(bucketNameIndex) ?: bucketId,
                        relativePath = cursor.getString(pathIndex).orEmpty(),
                        ownerPackage = cursor.getString(ownerIndex)?.takeIf { it.isNotEmpty() },
                    )
                    buckets[bucketId] = info
                }
                info.count++
                val type = cursor.getInt(typeIndex)
                if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    info.hasVideo = true
                } else {
                    info.hasImage = true
                }
                val taken = cursor.getLong(takenIndex)
                val date = if (taken > 0L) taken else cursor.getLong(modifiedIndex) * 1000L
                if (date > info.coverDate) {
                    info.coverDate = date
                    info.coverId = cursor.getLong(idIndex)
                }
            }
        }
        return buckets.values.toList()
    }

    fun itemsCursor(items: List<MediaItem>): Cursor {
        val result = MatrixCursor(GalleryContract.Columns.ALL)
        items.forEach { result.addRow(rowOf(it)) }
        return result
    }

    fun readItem(cursor: Cursor): MediaItem {
        val item = MediaItem()
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
        item.id = id
        item.mediaType = when (type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaObject.TYPE_VIDEO
            else -> MediaObject.TYPE_IMAGE
        }
        item.uri = ContentUris.withAppendedId(typeUri(type), id)
        item.name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
        item.mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
        item.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000L
        item.size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
        item.width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH))
        item.height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT))
        item.duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION))
        item.orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION))
        item.isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)) == 1
        item.isTrashed = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)) == 1
        item.isPending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)) == 1
        item.bucketId = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID))
        item.bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME))
        item.relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
        item.ownerPackage = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
        val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN))
        item.dateTaken = if (dateTaken > 0L) dateTaken else item.dateModified
        val xmp = readXmp(cursor, cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.XMP))
        item.isMotionPhoto = XmpParser.isMotionPhoto(xmp)
        item.motionVideoLength = XmpParser.motionVideoLength(xmp)
        item.isPanorama = XmpParser.isPanorama(xmp) ||
            XmpParser.isPanoramaBySize(item.width, item.height)
        return item
    }

    /**
     * MediaProvider stores the XMP packet as BLOB; Cursor.getString() would throw
     * "Unable to convert BLOB to string", so read it by column type.
     */
    private fun readXmp(cursor: Cursor, columnIndex: Int): String? {
        return try {
            when (cursor.getType(columnIndex)) {
                Cursor.FIELD_TYPE_STRING -> cursor.getString(columnIndex)
                Cursor.FIELD_TYPE_BLOB ->
                    cursor.getBlob(columnIndex)?.toString(Charsets.UTF_8)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun typeUri(mediaType: Int): Uri =
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }

    private fun rowOf(item: MediaItem): Array<Any?> = arrayOf(
        item.id,
        item.uri?.toString(),
        item.name,
        item.mimeType,
        item.mediaType,
        item.dateTaken,
        item.dateModified,
        item.size,
        item.width,
        item.height,
        item.duration,
        item.orientation,
        if (item.isFavorite) 1 else 0,
        if (item.isTrashed) 1 else 0,
        if (item.isPending) 1 else 0,
        if (item.isMotionPhoto) 1 else 0,
        item.motionVideoLength,
        if (item.isPanorama) 1 else 0,
        item.bucketId,
        item.bucketName,
        item.relativePath,
        item.ownerPackage,
    )

    private fun queryArgs(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        limit: Int = 0,
        offset: Int = 0,
    ): Bundle {
        val args = Bundle()
        if (!selection.isNullOrEmpty()) {
            args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        }
        if (!selectionArgs.isNullOrEmpty()) {
            args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        }
        if (!sortOrder.isNullOrEmpty()) {
            args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
        }
        if (limit > 0) {
            args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        if (offset > 0) {
            args.putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        return args
    }
}
