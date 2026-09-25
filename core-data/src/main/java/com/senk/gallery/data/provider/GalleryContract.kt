package com.senk.gallery.data.provider

import android.net.Uri

object GalleryContract {

    const val AUTHORITY = "com.senk.gallery.data.provider"

    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
    val MEDIA_URI: Uri = CONTENT_URI.buildUpon().appendPath(PATH_MEDIA).build()
    val ALBUMS_URI: Uri = CONTENT_URI.buildUpon().appendPath(PATH_ALBUMS).build()

    const val PATH_MEDIA = "media"
    const val PATH_ALBUMS = "albums"
    const val PATH_EXIF = "exif"
    const val PATH_FOLDER = "folder"
    const val PATH_APP = "app"

    const val QUERY_CATEGORY = "category"
    const val QUERY_LIMIT = "limit"
    const val QUERY_OFFSET = "offset"
    const val QUERY_FOLDER = "folder"
    const val QUERY_PACKAGE = "package"

    const val CATEGORY_COMMON = "common"
    const val CATEGORY_MORE = "more"

    const val CURSOR_DIR = "vnd.android.cursor.dir"
    const val CURSOR_ITEM = "vnd.android.cursor.item"
    const val MIME_ALBUM_DIR = "$CURSOR_DIR/vnd.gallery.album"
    const val MIME_MEDIA_DIR = "$CURSOR_DIR/vnd.gallery.media"
    const val MIME_MEDIA_ITEM = "$CURSOR_ITEM/vnd.gallery.media"
    const val MIME_EXIF_ITEM = "$CURSOR_ITEM/vnd.gallery.exif"

    fun withPage(uri: Uri, limit: Int, offset: Int): Uri =
        uri.buildUpon()
            .appendQueryParameter(QUERY_LIMIT, limit.toString())
            .appendQueryParameter(QUERY_OFFSET, offset.toString())
            .build()

    fun mediaUri(id: Long): Uri =
        MEDIA_URI.buildUpon().appendPath(id.toString()).build()

    fun exifUri(id: Long): Uri =
        mediaUri(id).buildUpon().appendPath(PATH_EXIF).build()

    fun mediaPageUri(limit: Int, offset: Int): Uri =
        MEDIA_URI.buildUpon()
            .appendQueryParameter(QUERY_LIMIT, limit.toString())
            .appendQueryParameter(QUERY_OFFSET, offset.toString())
            .build()

    fun albumsUri(category: String): Uri =
        ALBUMS_URI.buildUpon().appendQueryParameter(QUERY_CATEGORY, category).build()

    fun albumItemsUri(albumType: String): Uri =
        ALBUMS_URI.buildUpon().appendPath(albumType).build()

    fun albumItemsPageUri(albumType: String, limit: Int, offset: Int): Uri =
        albumItemsUri(albumType).buildUpon()
            .appendQueryParameter(QUERY_LIMIT, limit.toString())
            .appendQueryParameter(QUERY_OFFSET, offset.toString())
            .build()

    fun folderAlbumItemsUri(bucketId: String): Uri =
        ALBUMS_URI.buildUpon().appendPath(PATH_FOLDER).appendPath(bucketId).build()

    fun folderAlbumItemsPageUri(bucketId: String, limit: Int, offset: Int): Uri =
        folderAlbumItemsUri(bucketId).buildUpon()
            .appendQueryParameter(QUERY_LIMIT, limit.toString())
            .appendQueryParameter(QUERY_OFFSET, offset.toString())
            .build()

    fun appAlbumItemsUri(packageName: String): Uri =
        ALBUMS_URI.buildUpon().appendPath(PATH_APP).appendPath(packageName).build()

    fun appAlbumItemsPageUri(packageName: String, limit: Int, offset: Int): Uri =
        appAlbumItemsUri(packageName).buildUpon()
            .appendQueryParameter(QUERY_LIMIT, limit.toString())
            .appendQueryParameter(QUERY_OFFSET, offset.toString())
            .build()

    object Columns {

        const val ID = "_id"
        const val URI = "uri"
        const val NAME = "display_name"
        const val MIME_TYPE = "mime_type"
        const val MEDIA_TYPE = "media_type"
        const val DATE_TAKEN = "date_taken"
        const val DATE_MODIFIED = "date_modified"
        const val SIZE = "size"
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val DURATION = "duration"
        const val ORIENTATION = "orientation"
        const val FAVORITE = "is_favorite"
        const val TRASHED = "is_trashed"
        const val PENDING = "is_pending"
        const val MOTION_PHOTO = "is_motion_photo"
        const val MOTION_VIDEO_LENGTH = "motion_video_length"
        const val PANORAMA = "is_panorama"
        const val BUCKET_ID = "bucket_id"
        const val BUCKET_NAME = "bucket_name"
        const val RELATIVE_PATH = "relative_path"
        const val OWNER_PACKAGE = "owner_package"

        val ALL = arrayOf(
            ID,
            URI,
            NAME,
            MIME_TYPE,
            MEDIA_TYPE,
            DATE_TAKEN,
            DATE_MODIFIED,
            SIZE,
            WIDTH,
            HEIGHT,
            DURATION,
            ORIENTATION,
            FAVORITE,
            TRASHED,
            PENDING,
            MOTION_PHOTO,
            MOTION_VIDEO_LENGTH,
            PANORAMA,
            BUCKET_ID,
            BUCKET_NAME,
            RELATIVE_PATH,
            OWNER_PACKAGE,
        )
    }

    object AlbumColumns {

        const val ID = "_id"
        const val ALBUM_TYPE = "album_type"
        const val NAME = "album_name"
        const val COVER_URI = "cover_uri"
        const val ITEM_COUNT = "item_count"
        const val BUCKET_ID = "bucket_id"
        const val RELATIVE_PATH = "relative_path"
        const val OWNER_PACKAGE = "owner_package"

        val ALL = arrayOf(
            ID,
            ALBUM_TYPE,
            NAME,
            COVER_URI,
            ITEM_COUNT,
            BUCKET_ID,
            RELATIVE_PATH,
            OWNER_PACKAGE,
        )
    }

    object ExifColumns {

        const val MAKE = "make"
        const val MODEL = "model"
        const val LENS_MODEL = "lens_model"
        const val F_NUMBER = "f_number"
        const val EXPOSURE_TIME = "exposure_time"
        const val SHUTTER_SPEED = "shutter_speed"
        const val APERTURE = "aperture"
        const val ISO = "iso"
        const val FOCAL_LENGTH = "focal_length"
        const val FOCAL_LENGTH_35MM = "focal_length_35mm"
        const val DATETIME = "datetime"
        const val FLASH = "flash"
        const val WHITE_BALANCE = "white_balance"
        const val SOFTWARE = "software"
        const val ARTIST = "artist"
        const val COPYRIGHT = "copyright"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        const val HAS_LOCATION = "has_location"
        const val IMAGE_WIDTH = "image_width"
        const val IMAGE_HEIGHT = "image_height"
        const val ORIENTATION = "orientation"

        val ALL = arrayOf(
            MAKE,
            MODEL,
            LENS_MODEL,
            F_NUMBER,
            EXPOSURE_TIME,
            SHUTTER_SPEED,
            APERTURE,
            ISO,
            FOCAL_LENGTH,
            FOCAL_LENGTH_35MM,
            DATETIME,
            FLASH,
            WHITE_BALANCE,
            SOFTWARE,
            ARTIST,
            COPYRIGHT,
            LATITUDE,
            LONGITUDE,
            HAS_LOCATION,
            IMAGE_WIDTH,
            IMAGE_HEIGHT,
            ORIENTATION,
        )
    }
}
