package com.senk.gallery.data.provider

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.pojo.AllItemAlbum
import com.senk.gallery.data.pojo.CameraAlbum
import com.senk.gallery.data.pojo.FavoriteAlbum
import com.senk.gallery.data.pojo.FolderAlbum
import com.senk.gallery.data.pojo.LivePhotoAlbum
import com.senk.gallery.data.pojo.PanoramaAlbum
import com.senk.gallery.data.pojo.ScreenRecordAlbum
import com.senk.gallery.data.pojo.ScreenshotAlbum
import com.senk.gallery.data.pojo.ThirdPartyAlbum
import com.senk.gallery.data.pojo.VideoAlbum

object AlbumResolver {

    private const val PICTURES_PREFIX = "Pictures/"

    private val KNOWN_APPS = linkedMapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.tencent.tim" to "TIM",
        "com.tencent.mobileqqi" to "QQ",
        "com.sina.weibo" to "微博",
        "com.xingin.xhs" to "小红书",
        "com.tencent.qqlite" to "QQ Lite",
    )

    private val KNOWN_APP_BUCKETS = mapOf(
        "weixin" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "micromsg" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "qqi" to "com.tencent.mobileqq",
        "mobileqq" to "com.tencent.mobileqq",
        "tencent" to "com.tencent.mobileqq",
        "weibo" to "com.sina.weibo",
    )

    private val EXCLUDED_PACKAGES = setOf(
        "com.senk.gallery",
        "com.android.providers.media",
        "com.android.providers.downloads",
        "com.android.documentsui",
        "com.android.shell",
        "com.android.bluetooth",
        "android",
    )

    data class AlbumSpec(
        val type: String,
        val name: String,
        val selection: String,
    )

    private val COMMON_SPECS = listOf(
        AlbumSpec(MediaSet.TYPE_ALL, "全部", MediaStoreFetcher.BASE_SELECTION),
        AlbumSpec(
            MediaSet.TYPE_CAMERA,
            "相机",
            "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/Camera/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/100ANDRO/%')",
        ),
        AlbumSpec(
            MediaSet.TYPE_VIDEO,
            "视频",
            "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
        ),
        AlbumSpec(
            MediaSet.TYPE_SCREENSHOT,
            "截屏",
            "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}) AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Pictures/Screenshots/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/Screenshots/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Pictures/截图/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/截图/%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Screenshot_%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Screenshot-%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '截屏%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '屏幕截图%')",
        ),
        AlbumSpec(
            MediaSet.TYPE_SCREEN_RECORD,
            "录屏",
            "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/ScreenRecorder/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Movies/ScreenRecorder/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/Screen recordings/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Movies/Screen recordings/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'Pictures/ScreenRecordings/%' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/录屏/%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Screenrecorder%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'ScreenRecord%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Screen recording%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '录屏%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '屏幕录制%')",
        ),
        AlbumSpec(
            MediaSet.TYPE_LIVE_PHOTO,
            "动态照片",
            "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns.XMP} LIKE '%MotionPhoto%' OR " +
                "${MediaStore.MediaColumns.XMP} LIKE '%MicroVideo%')",
        ),
        AlbumSpec(
            MediaSet.TYPE_FAVORITE,
            "收藏",
            "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns.IS_FAVORITE} = 1)",
        ),
        AlbumSpec(
            MediaSet.TYPE_PANORAMA,
            "全景",
            "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns.XMP} LIKE '%GPano:%' OR " +
                "(${MediaStore.MediaColumns.WIDTH} >= 6000 AND " +
                "${MediaStore.MediaColumns.WIDTH} >= ${MediaStore.MediaColumns.HEIGHT} * 3))",
        ),
    )

    fun commonSpec(type: String): AlbumSpec? = COMMON_SPECS.firstOrNull { it.type == type }

    fun queryAlbums(context: Context, resolver: ContentResolver, category: String): Cursor {
        val albums = when (category) {
            GalleryContract.CATEGORY_MORE -> queryMoreAlbums(context, resolver)
            else -> queryCommonAlbums(context, resolver)
        }
        val cursor = MatrixCursor(GalleryContract.AlbumColumns.ALL)
        albums.forEach { addAlbumRow(cursor, it) }
        return cursor
    }

    private fun queryCommonAlbums(context: Context, resolver: ContentResolver): List<MediaSet> {
        val albums = ArrayList<MediaSet>()
        COMMON_SPECS.forEach { spec ->
            val count = MediaStoreFetcher.count(resolver, spec.selection, null)
            if (count <= 0) {
                return@forEach
            }
            val album = albumOf(spec.type)
            album.name = spec.name
            album.count = count
            album.coverUri = MediaStoreFetcher.latestItem(resolver, spec.selection, null)?.uri?.toString()
            // Keep sure covers exist even when COUNT is unavailable.
            albums.add(album)
        }
        return albums
    }

    /**
     * Groups buckets by owning third-party app. Note that `owner_package_name` is
     * redacted (null) for other apps, so the known app bucket names are used as fallback.
     */
    private fun groupBucketsByPackage(
        buckets: List<MediaStoreFetcher.BucketInfo>,
    ): LinkedHashMap<String, MutableList<MediaStoreFetcher.BucketInfo>> {
        val appGroups = LinkedHashMap<String, MutableList<MediaStoreFetcher.BucketInfo>>()
        buckets.forEach { bucket ->
            val packageName = bucket.ownerPackage
                ?.takeIf { it.isNotBlank() && !isExcluded(it) }
                ?: KNOWN_APP_BUCKETS[bucket.bucketName.lowercase()]
            if (packageName != null) {
                appGroups.getOrPut(packageName) { ArrayList() }.add(bucket)
            }
        }
        return appGroups
    }

    private fun queryMoreAlbums(context: Context, resolver: ContentResolver): List<MediaSet> {
        val buckets = MediaStoreFetcher.scanBuckets(resolver)
        val albums = ArrayList<MediaSet>()

        val appGroups = groupBucketsByPackage(buckets)
        appGroups.forEach { (packageName, group) ->
            val album = ThirdPartyAlbum()
            album.packageName = packageName
            album.appLabel = appLabel(context, packageName)
            album.name = album.appLabel
            album.count = group.sumOf { it.count }
            val cover = group.maxByOrNull { it.coverDate }
            album.coverUri = cover?.coverUri?.toString()
            albums.add(album)
        }
        albums.sortWith(compareBy({ appOrder(it.name) }, { it.name }))

        val folderAlbums = buckets
            .asSequence()
            .filter { it.relativePath.startsWith(PICTURES_PREFIX) && it.relativePath.length > PICTURES_PREFIX.length }
            .filter { bucket ->
                val claimed = bucket.ownerPackage
                    ?.takeIf { it.isNotBlank() && !isExcluded(it) }
                    ?: KNOWN_APP_BUCKETS[bucket.bucketName.lowercase()]
                claimed == null
            }
            .map { bucket ->
                FolderAlbum().apply {
                    albumType = MediaSet.TYPE_FOLDER
                    name = bucket.bucketName
                    bucketId = bucket.bucketId
                    folderPath = bucket.relativePath
                    count = bucket.count
                    coverUri = bucket.coverUri?.toString()
                }
            }
            .sortedBy { it.name.lowercase() }
            .toList()
        albums.addAll(folderAlbums)

        return albums
    }

    fun queryAlbumItems(
        resolver: ContentResolver,
        albumType: String,
        bucketId: String?,
        packageName: String?,
        limit: Int,
        offset: Int,
    ): Cursor {
        val selection: String?
        val selectionArgs: Array<String>?
        when (albumType) {
            MediaSet.TYPE_FOLDER -> {
                selection = "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                    "(${MediaStore.MediaColumns.BUCKET_ID} = ?)"
                selectionArgs = arrayOf(bucketId.orEmpty())
            }
            MediaSet.TYPE_THIRD_PARTY -> {
                // owner_package_name 对其他应用不可见（读取为 null），
                // 因此按分组得到的 bucket 集合查询；无 bucket 时回退 owner_package
                val group = groupBucketsByPackage(MediaStoreFetcher.scanBuckets(resolver))
                    .get(packageName.orEmpty())
                    .orEmpty()
                if (group.isNotEmpty()) {
                    val placeholders = group.joinToString(", ") { "?" }
                    selection = "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                        "(${MediaStore.MediaColumns.BUCKET_ID} IN ($placeholders))"
                    selectionArgs = group.map { it.bucketId }.toTypedArray()
                } else {
                    selection = "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                        "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?)"
                    selectionArgs = arrayOf(packageName.orEmpty())
                }
            }
            else -> {
                val spec = commonSpec(albumType) ?: commonSpec(MediaSet.TYPE_ALL)!!
                selection = spec.selection
                selectionArgs = null
            }
        }
        return MediaStoreFetcher.queryItemsCursor(resolver, selection, selectionArgs, limit, offset)
    }

    private fun albumOf(type: String): MediaSet = when (type) {
        MediaSet.TYPE_CAMERA -> CameraAlbum()
        MediaSet.TYPE_VIDEO -> VideoAlbum()
        MediaSet.TYPE_SCREENSHOT -> ScreenshotAlbum()
        MediaSet.TYPE_SCREEN_RECORD -> ScreenRecordAlbum()
        MediaSet.TYPE_LIVE_PHOTO -> LivePhotoAlbum()
        MediaSet.TYPE_FAVORITE -> FavoriteAlbum()
        MediaSet.TYPE_PANORAMA -> PanoramaAlbum()
        else -> AllItemAlbum()
    }

    private fun addAlbumRow(cursor: MatrixCursor, album: MediaSet) {
        val (bucketId, packageName) = when (album) {
            is FolderAlbum -> album.bucketId to null
            is ThirdPartyAlbum -> null to album.packageName
            else -> null to null
        }
        // 显式标注 Array<Any?>：本行的列值混合了 String / Int / Uri? / null，
        // 不标注时 arrayOf 推断出的交叉类型在 addRow 的 reified 参数上会被 Kotlin
        // 报「Reification of an intersection type」警告，并将在未来版本变为错误。
        val row: Array<Any?> = arrayOf(
            album.albumType + ":" + (bucketId ?: packageName ?: ""),
            album.albumType,
            album.name,
            album.coverUri,
            album.count,
            bucketId,
            (album as? FolderAlbum)?.folderPath,
            packageName,
        )
        cursor.addRow(row)
    }

    private fun appLabel(context: Context, packageName: String): String {
        KNOWN_APPS[packageName]?.let { return it }
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun appOrder(name: String): Int {
        val known = KNOWN_APPS.values.indexOf(name)
        return if (known >= 0) known else KNOWN_APPS.size
    }

    private fun isExcluded(packageName: String): Boolean =
        packageName in EXCLUDED_PACKAGES || packageName.startsWith("com.android.")

    fun albumItemsUri(albumType: String, bucketId: String?, packageName: String?): Uri = when {
        albumType == MediaSet.TYPE_FOLDER && bucketId != null ->
            GalleryContract.folderAlbumItemsUri(bucketId)
        albumType == MediaSet.TYPE_THIRD_PARTY && packageName != null ->
            GalleryContract.appAlbumItemsUri(packageName)
        else -> GalleryContract.albumItemsUri(albumType)
    }
}
