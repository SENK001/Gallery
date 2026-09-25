package com.senk.gallery.data.provider

import android.database.Cursor
import android.net.Uri
import com.senk.gallery.data.entity.MediaItem

object GalleryCursorReader {

    data class AlbumInfo(
        val id: String,
        val albumType: String,
        val name: String,
        val coverUri: String?,
        val count: Int,
        val bucketId: String?,
        val ownerPackage: String?,
        val folderPath: String?,
    )

    fun readMedia(cursor: Cursor): MediaItem {
        val item = MediaItem()
        item.id = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryContract.Columns.ID))
        val uriString = cursor.getString(cursor.getColumnIndexOrThrow(GalleryContract.Columns.URI))
        item.uri = uriString?.let { Uri.parse(it) }
        item.name = cursor.getString(cursor.getColumnIndexOrThrow(GalleryContract.Columns.NAME))
        item.mimeType = cursor.getString(cursor.getColumnIndexOrThrow(GalleryContract.Columns.MIME_TYPE))
        item.mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.MEDIA_TYPE))
        item.dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryContract.Columns.DATE_TAKEN))
        item.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryContract.Columns.DATE_MODIFIED))
        item.size = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryContract.Columns.SIZE))
        item.width = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.WIDTH))
        item.height = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.HEIGHT))
        item.duration = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryContract.Columns.DURATION))
        item.orientation = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.ORIENTATION))
        item.isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.FAVORITE)) == 1
        item.isTrashed = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.TRASHED)) == 1
        item.isPending = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.PENDING)) == 1
        item.isMotionPhoto = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.MOTION_PHOTO)) == 1
        item.motionVideoLength = cursor.getLong(
            cursor.getColumnIndexOrThrow(GalleryContract.Columns.MOTION_VIDEO_LENGTH),
        )
        item.isPanorama = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryContract.Columns.PANORAMA)) == 1
        item.bucketId = cursor.getString(cursor.getColumnIndexOrThrow(GalleryContract.Columns.BUCKET_ID))
        item.bucketName = cursor.getString(cursor.getColumnIndexOrThrow(GalleryContract.Columns.BUCKET_NAME))
        item.relativePath = cursor.getString(cursor.getColumnIndexOrThrow(GalleryContract.Columns.RELATIVE_PATH))
        item.ownerPackage = cursor.getString(cursor.getColumnIndexOrThrow(GalleryContract.Columns.OWNER_PACKAGE))
        return item
    }

    fun readMediaList(cursor: Cursor): List<MediaItem> {
        val items = ArrayList<MediaItem>(cursor.count)
        while (cursor.moveToNext()) {
            items.add(readMedia(cursor))
        }
        return items
    }

    fun readAlbum(cursor: Cursor): AlbumInfo {
        val idIndex = cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.ID)
        return AlbumInfo(
            id = cursor.getString(idIndex).orEmpty(),
            albumType = cursor.getString(
                cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.ALBUM_TYPE),
            ).orEmpty(),
            name = cursor.getString(
                cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.NAME),
            ).orEmpty(),
            coverUri = cursor.getString(
                cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.COVER_URI),
            ),
            count = cursor.getInt(
                cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.ITEM_COUNT),
            ),
            bucketId = cursor.getString(
                cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.BUCKET_ID),
            ),
            ownerPackage = cursor.getString(
                cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.OWNER_PACKAGE),
            ),
            folderPath = cursor.getString(
                cursor.getColumnIndexOrThrow(GalleryContract.AlbumColumns.RELATIVE_PATH),
            ),
        )
    }

    fun readAlbumList(cursor: Cursor): List<AlbumInfo> {
        val albums = ArrayList<AlbumInfo>(cursor.count)
        while (cursor.moveToNext()) {
            albums.add(readAlbum(cursor))
        }
        return albums
    }
}
