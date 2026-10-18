package com.senk.gallery.data.provider

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import com.senk.gallery.data.album.CustomAlbumStore
import com.senk.gallery.data.album.FavoriteStore
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.entity.MediaItem
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

    /** 一次 `IN (...)` 的 id 数上限，低于 SQLite 默认变量上限（999）。 */
    private const val ID_CHUNK_SIZE = 900

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
            // 注意：收藏是**应用内自建库**（FavoriteStore），此 selection 仅为占位——
            // 列表与内容查询都走 FavoriteStore（见 queryCommonAlbums / queryAlbumItems），
            // 不会再读/写 MediaStore 的 is_favorite。
            MediaSet.TYPE_FAVORITE,
            "收藏",
            MediaStoreFetcher.BASE_SELECTION,
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

    /**
     * 「最近删除」相册 spec。
     *
     * 不属于 [COMMON_SPECS]：相册页里它固定放在「更多」之后（无封面），由 UI 单独查询
     * [GalleryContract.CATEGORY_RECENTLY_DELETED] 追加；这里仅用于相册内容查询的筛选串。
     */
    private val TRASH_SPEC = AlbumSpec(
        MediaSet.TYPE_RECENTLY_DELETED,
        "最近删除",
        MediaStoreFetcher.TRASH_SELECTION,
    )

    fun commonSpec(type: String): AlbumSpec? = when (type) {
        MediaSet.TYPE_RECENTLY_DELETED -> TRASH_SPEC
        else -> COMMON_SPECS.firstOrNull { it.type == type }
    }

    fun queryAlbums(context: Context, resolver: ContentResolver, category: String): Cursor {
        val cursor = MatrixCursor(GalleryContract.AlbumColumns.ALL)
        when (category) {
            GalleryContract.CATEGORY_MORE ->
                queryMoreAlbums(context, resolver).forEach { addAlbumRow(cursor, it) }
            GalleryContract.CATEGORY_CUSTOM ->
                queryCustomAlbumRows(context, resolver, cursor)
            GalleryContract.CATEGORY_RECENTLY_DELETED ->
                queryRecentlyDeletedAlbum(resolver).forEach { addAlbumRow(cursor, it) }
            else -> {
                // 相册页刷新时顺带校正收藏库的删除标记（其它应用/系统删掉媒体后，
                // 收藏相册不应再统计/展示这些条目）。
                reconcileFavoriteFlags(context, resolver)
                queryCommonAlbums(context, resolver).forEach { addAlbumRow(cursor, it) }
            }
        }
        return cursor
    }

    /**
     * 「最近删除」相册行：回收站非空时返回 1 行（无封面），否则空列表。
     *
     * 该行不参与「常用」分组，UI 把它追加在相册页最底部（更多相册之后）。
     */
    private fun queryRecentlyDeletedAlbum(resolver: ContentResolver): List<MediaSet> {
        val count = MediaStoreFetcher.count(
            resolver,
            MediaStoreFetcher.TRASH_SELECTION,
            null,
            trashedOnly = true,
        )
        if (count <= 0) {
            return emptyList()
        }
        val album = albumOf(MediaSet.TYPE_RECENTLY_DELETED)
        album.name = TRASH_SPEC.name
        album.count = count
        // 按产品要求：最近删除入口不显示封面
        album.coverUri = null
        return listOf(album)
    }

    /**
     * 校正收藏库的删除标记。
     *
     * 收藏是应用内 SQLite 库，媒体被删除（进回收站/彻底删除）后收藏行仍然存在：
     * - 已收藏但已不可见（非 trashed 查询不到）→ 标记 `is_trashed = 1`，不再计入/展示；
     * - 已标记但重新可见（从回收站恢复）→ 清除标记，收藏恢复。
     *
     * 只在相册页刷新（category=common）与打开收藏相册时执行，收藏数量通常很小。
     */
    fun reconcileFavoriteFlags(context: Context, resolver: ContentResolver) {
        val store = FavoriteStore.get(context)
        val activeIds = store.ids()
        val trashedIds = store.trashedIds()
        val knownIds = activeIds + trashedIds
        if (knownIds.isEmpty()) {
            return
        }
        val visibleIds = queryVisibleIds(resolver, knownIds)
        val toTrash = activeIds - visibleIds
        if (toTrash.isNotEmpty()) {
            store.markTrashed(toTrash, true)
        }
        val toRestore = trashedIds.intersect(visibleIds)
        if (toRestore.isNotEmpty()) {
            store.markTrashed(toRestore, false)
        }
    }

    /** 已知 id 集合里当前仍可见（未 trashed、非 pending）的媒体 id。 */
    private fun queryVisibleIds(resolver: ContentResolver, ids: Set<Long>): Set<Long> {
        val visible = HashSet<Long>(ids.size)
        ids.toList().chunked(ID_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(", ") { "?" }
            val selection = "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns._ID} IN ($placeholders))"
            MediaStoreFetcher.queryItems(
                resolver,
                selection,
                chunk.map { it.toString() }.toTypedArray(),
                sortOrder = MediaStore.MediaColumns._ID,
            ).forEach { item -> visible.add(item.id) }
        }
        return visible
    }

    /**
     * 「我的相册」= 自建相册（应用内逻辑集合）。
     *
     * 与系统相册不同：**空的自建相册也要显示**（用户刚建好、还没加照片时需要一个入口），
     * 因此不做 count 过滤。行契约与目录相册一致，`bucket_id` 位放自建相册 UUID，
     * 内容查询走 `albums/custom/{uuid}`。
     */
    private fun queryCustomAlbumRows(
        context: Context,
        resolver: ContentResolver,
        cursor: MatrixCursor,
    ) {
        CustomAlbumStore.get(context).albums().forEach { album ->
            val coverUri = album.coverUri ?: coverOfCustomAlbum(resolver, album.itemIds)
            // 与 addAlbumRow 同样的显式 Array<Any?>：列值混合 String / Int / null
            val row: Array<Any?> = arrayOf(
                MediaSet.TYPE_CUSTOM + ":" + album.id,
                MediaSet.TYPE_CUSTOM,
                album.name,
                coverUri,
                album.itemIds.size,
                album.id,
                null,
                null,
            )
            cursor.addRow(row)
        }
    }

    /** 自建相册没有显式封面时，按现有成员取最新一条的 URI 兜底。 */
    private fun coverOfCustomAlbum(resolver: ContentResolver, itemIds: List<Long>): String? {
        // 只取前 900 个 id（SQLite 变量上限内），封面不需要全量成员
        val ids = itemIds.take(ID_CHUNK_SIZE)
        if (ids.isEmpty()) {
            return null
        }
        return queryItemsByIds(resolver, ids).values
            .maxByOrNull { it.dateTaken }
            ?.uri
            ?.toString()
    }

    private fun queryCommonAlbums(context: Context, resolver: ContentResolver): List<MediaSet> {
        val albums = ArrayList<MediaSet>()
        val favoriteIds = FavoriteStore.get(context).ids()
        COMMON_SPECS.forEach { spec ->
            if (spec.type == MediaSet.TYPE_FAVORITE) {
                // 收藏相册来自应用内收藏库：空收藏不显示（与其它常用相册的隐藏规则一致）
                if (favoriteIds.isEmpty()) {
                    return@forEach
                }
                val album = albumOf(spec.type)
                album.name = spec.name
                album.count = favoriteIds.size
                album.coverUri = latestItemOfIds(resolver, favoriteIds)?.uri?.toString()
                albums.add(album)
                return@forEach
            }
            val trashedOnly = spec.type == MediaSet.TYPE_RECENTLY_DELETED
            val count = MediaStoreFetcher.count(resolver, spec.selection, null, trashedOnly)
            if (count <= 0) {
                return@forEach
            }
            val album = albumOf(spec.type)
            album.name = spec.name
            album.count = count
            album.coverUri = MediaStoreFetcher.latestItem(
                resolver,
                spec.selection,
                null,
                trashedOnly,
            )?.uri?.toString()
            // Keep sure covers exist even when COUNT is unavailable.
            albums.add(album)
        }
        return albums
    }

    /** 一组 id 里拍摄时间最新的一条（收藏相册封面；只查前 900 个 id）。 */
    private fun latestItemOfIds(resolver: ContentResolver, ids: Set<Long>): MediaItem? {
        val idList = ids.take(ID_CHUNK_SIZE)
        if (idList.isEmpty()) {
            return null
        }
        return queryItemsByIds(resolver, idList).values.maxByOrNull { it.dateTaken }
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
        context: Context,
        resolver: ContentResolver,
        albumType: String,
        bucketId: String?,
        packageName: String?,
        limit: Int,
        offset: Int,
        favoriteIds: Set<Long>,
    ): Cursor {
        if (albumType == MediaSet.TYPE_CUSTOM) {
            return queryCustomAlbumItems(
                context,
                resolver,
                bucketId.orEmpty(),
                limit,
                offset,
                favoriteIds,
            )
        }
        if (albumType == MediaSet.TYPE_FAVORITE) {
            return queryFavoriteAlbumItems(resolver, favoriteIds, limit, offset)
        }
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
        return MediaStoreFetcher.queryItemsCursor(
            resolver,
            selection,
            selectionArgs,
            limit,
            offset,
            favoriteIds,
            trashedOnly = albumType == MediaSet.TYPE_RECENTLY_DELETED,
        )
    }

    /**
     * 收藏相册内容：成员来自应用内收藏库（按拍摄时间倒序），不读 MediaStore 的 is_favorite。
     *
     * 收藏数量通常不大，按 id 集合直接交给 MediaStore 排序分页；超过 `IN (...)` 变量上限时
     * 退化为「全量取回 → 内存排序 → 切片」。
     */
    private fun queryFavoriteAlbumItems(
        resolver: ContentResolver,
        favoriteIds: Set<Long>,
        limit: Int,
        offset: Int,
    ): Cursor {
        if (favoriteIds.isEmpty()) {
            return MediaStoreFetcher.itemsCursor(emptyList())
        }
        val safeOffset = offset.coerceAtLeast(0)
        val idList = favoriteIds.toList()
        if (idList.size <= ID_CHUNK_SIZE) {
            val placeholders = idList.joinToString(", ") { "?" }
            val selection = "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns._ID} IN ($placeholders))"
            return MediaStoreFetcher.queryItemsCursor(
                resolver,
                selection,
                idList.map { it.toString() }.toTypedArray(),
                limit,
                safeOffset,
                favoriteIds,
            )
        }
        val items = queryItemsByIds(resolver, idList, favoriteIds)
            .values
            .sortedByDescending { it.dateTaken }
        val page = if (limit <= 0) {
            items.drop(safeOffset)
        } else {
            items.drop(safeOffset).take(limit)
        }
        return MediaStoreFetcher.itemsCursor(page)
    }

    /**
     * 自建相册内容：**按加入顺序**返回，再按 limit/offset 切片。
     *
     * MediaStore 查询不保证 `_id IN (...)` 的返回顺序，因此先切出本页要的 id，
     * 再按切片顺序重排成 MediaItem 列表。相册不存在 / 成员被删除时返回空游标，
     * 不抛异常（列表页的 item_count 仍按记录数展示，属已知口径差异）。
     */
    private fun queryCustomAlbumItems(
        context: Context,
        resolver: ContentResolver,
        albumId: String,
        limit: Int,
        offset: Int,
        favoriteIds: Set<Long>,
    ): Cursor {
        if (albumId.isEmpty()) {
            return MediaStoreFetcher.itemsCursor(emptyList())
        }
        val album = CustomAlbumStore.get(context).album(albumId)
            ?: return MediaStoreFetcher.itemsCursor(emptyList())
        val safeOffset = offset.coerceAtLeast(0)
        val pageIds = if (limit <= 0) {
            album.itemIds.drop(safeOffset)
        } else {
            album.itemIds.drop(safeOffset).take(limit)
        }
        if (pageIds.isEmpty()) {
            return MediaStoreFetcher.itemsCursor(emptyList())
        }
        val byId = queryItemsByIds(resolver, pageIds, favoriteIds)
        val ordered = pageIds.mapNotNull { byId[it] }
        return MediaStoreFetcher.itemsCursor(ordered)
    }

    /** `_id IN (...)` 分批查询，返回 id → MediaItem（按分批规避 SQLite 变量上限）。 */
    private fun queryItemsByIds(
        resolver: ContentResolver,
        ids: List<Long>,
        favoriteIds: Set<Long>? = null,
    ): Map<Long, MediaItem> {
        val items = HashMap<Long, MediaItem>(ids.size)
        ids.distinct().chunked(ID_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(", ") { "?" }
            val selection = "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns._ID} IN ($placeholders))"
            MediaStoreFetcher.queryItems(
                resolver,
                selection,
                chunk.map { it.toString() }.toTypedArray(),
                // 顺序由调用方按加入顺序重排，这里仅取一个合法排序列（Provider 要求非空）
                sortOrder = MediaStore.MediaColumns._ID,
                favoriteIds = favoriteIds,
            ).forEach { item -> items[item.id] = item }
        }
        return items
    }

    private fun albumOf(type: String): MediaSet = when (type) {
        MediaSet.TYPE_CAMERA -> CameraAlbum()
        MediaSet.TYPE_VIDEO -> VideoAlbum()
        MediaSet.TYPE_SCREENSHOT -> ScreenshotAlbum()
        MediaSet.TYPE_SCREEN_RECORD -> ScreenRecordAlbum()
        MediaSet.TYPE_LIVE_PHOTO -> LivePhotoAlbum()
        MediaSet.TYPE_FAVORITE -> FavoriteAlbum()
        MediaSet.TYPE_PANORAMA -> PanoramaAlbum()
        // TYPE_ALL 与 TYPE_RECENTLY_DELETED 共用 AllItemAlbum，但 albumType 必须保持入参值：
        // 「最近删除」的 album_type 要能被 UI 识别并路由到专用页面，不能退化成 all。
        else -> AllItemAlbum().apply { albumType = type }
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
        albumType == MediaSet.TYPE_CUSTOM && bucketId != null ->
            GalleryContract.customAlbumItemsUri(bucketId)
        else -> GalleryContract.albumItemsUri(albumType)
    }
}
