package com.senk.gallery.data.provider

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.senk.gallery.data.entity.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * 媒体写入（「添加到」页的物理复制实现）。
 *
 * 只做一件事：把 MediaStore 里已存在的媒体条目**复制**到目标目录，生成新文件。
 * 不移动、不覆盖源文件；重名自动加 `(1)`/`(2)` 后缀。
 *
 * 写入流程（Android 10+ Scoped Storage 标准做法）：
 * 1. 按源条目 mime 选择 `Images` 或 `Video` 集合；
 * 2. `insert(DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH, IS_PENDING=1, DATE_TAKEN)`；
 * 3. 64KB 缓冲流式复制（不整块读入内存，大视频不会 OOM/ANR）；
 * 4. `update(IS_PENDING=0)` 发布文件（发布前对其它应用不可见）。
 *
 * 失败/取消：删除刚插入的 pending 行（`finally` 保证），调用方据此提示用户，不静默失败。
 * 协程取消：复制循环内检查取消，抛 [CancellationException] 前先清理。
 */
object MediaWriter {

    private const val TAG = "MediaWriter"

    /** 流复制缓冲区：64KB（与 Os.copyFileRange 同量级，兼顾吞吐与内存）。 */
    private const val BUFFER_SIZE = 64 * 1024

    /** 单次 `IN (...)` 查询的 id 数上限，低于 SQLite 默认变量上限（999）。 */
    private const val ID_CHUNK_SIZE = 900

    /** 重名时最多尝试的序号，避免极端情况下死循环。 */
    private const val MAX_DUPLICATE_INDEX = 999L

    data class CopyResult(
        /** 成功复制的条目数。 */
        val success: Int,
        /** 失败的条目数。 */
        val failed: Int,
        /** 失败条目的源媒体 ID（便于调用方在日志/提示里定位）。 */
        val failedIds: List<Long>,
    ) {
        val total: Int get() = success + failed
    }

    /**
     * 把 [mediaIds] 指向的媒体复制到 [relativePath] 目录。
     *
     * @param relativePath MediaStore 相对路径，如 `DCIM/Camera/`、`Pictures/WeChat/`；
     *   末尾斜杠可省略，内部会规范化；包含 `..` 等非法路径时返回全失败。
     * @param onProgress 进度回调 `(done, total)`，**在 IO 线程触发**，调用方需自行切主线程；
     *   开始时回调一次 `(0, total)`，每处理完一条回调一次。
     * @return 复制结果（成功/失败条数、失败 id）。协程被取消时抛 [CancellationException]。
     */
    suspend fun copyToFolder(
        resolver: ContentResolver,
        mediaIds: List<Long>,
        relativePath: String,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): CopyResult = withContext(Dispatchers.IO) {
        val targetPath = normalizeRelativePath(relativePath)
        val ids = mediaIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) {
            return@withContext CopyResult(0, 0, emptyList())
        }
        if (targetPath.isEmpty()) {
            Log.w(TAG, "invalid target relative path: $relativePath")
            return@withContext CopyResult(0, ids.size, ids)
        }

        val sources = querySources(resolver, ids)
        // 目标目录已用的文件名（Images + Video 两个集合），用于重名去重
        val usedNames = existingDisplayNames(resolver, targetPath)
        var success = 0
        val failedIds = ArrayList<Long>()

        onProgress?.invoke(0, ids.size)
        ids.forEachIndexed { index, id ->
            coroutineContext.ensureActive()
            val source = sources[id]
            if (source == null) {
                Log.w(TAG, "source media not found: $id")
                failedIds.add(id)
            } else {
                try {
                    copyOne(resolver, source, targetPath, usedNames)
                    success++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "copy failed: id=$id target=$targetPath", e)
                    failedIds.add(id)
                }
            }
            onProgress?.invoke(index + 1, ids.size)
        }

        if (success > 0) {
            // 显式通知本应用根 URI：MediaStore 变更通知不一定覆盖 Files 集合监听，
            // 复制完成后相册页/详情页需要立刻刷新（不依赖 Provider 的内部观察时序）。
            resolver.notifyChange(GalleryContract.CONTENT_URI, null)
        }
        CopyResult(success = success, failed = failedIds.size, failedIds = failedIds)
    }

    /** 复制单条；失败时删除已插入的 pending 行并抛出异常。 */
    private suspend fun copyOne(
        resolver: ContentResolver,
        source: MediaItem,
        targetPath: String,
        usedNames: MutableSet<String>,
    ) {
        val sourceUri = source.uri ?: throw IOException("source uri is null")
        val mimeType = resolveMimeType(source)
        val isVideo = mimeType.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }

        val displayName = uniqueDisplayName(source, mimeType, usedNames)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (source.dateTaken > 0L) {
                put(MediaStore.MediaColumns.DATE_TAKEN, source.dateTaken)
            }
        }
        val targetUri = insertResilient(resolver, collection, values)
            ?: throw IOException("insert returned null")
        // 占位：本条处理完前，同批次的其它条目不能再取同名
        usedNames.add(displayName.lowercase())

        var published = false
        try {
            copyStream(resolver, sourceUri, targetUri)
            coroutineContext.ensureActive()
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(targetUri, publish, null, null)
            published = true
        } finally {
            if (!published) {
                // 失败/取消：清理 pending 行，避免媒体库里留下 0 字节/半截文件
                try {
                    resolver.delete(targetUri, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "failed to clean up pending item: $targetUri", e)
                }
            }
        }
    }

    /** 64KB 缓冲流式复制，循环内检查协程取消。 */
    private suspend fun copyStream(resolver: ContentResolver, sourceUri: Uri, targetUri: Uri) {
        val input = resolver.openInputStream(sourceUri)
            ?: throw IOException("openInputStream failed: $sourceUri")
        input.use { source ->
            val output = resolver.openOutputStream(targetUri)
                ?: throw IOException("openOutputStream failed: $targetUri")
            output.use { target ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) {
                        break
                    }
                    target.write(buffer, 0, read)
                }
                target.flush()
            }
        }
    }

    /** `DATE_TAKEN` 在部分 MediaProvider 版本上不允许 insert，失败时去掉该列重试。 */
    private fun insertResilient(
        resolver: ContentResolver,
        collection: Uri,
        values: ContentValues,
    ): Uri? {
        try {
            return resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "insert rejected DATE_TAKEN, retry without it", e)
            val fallback = ContentValues(values)
            fallback.remove(MediaStore.MediaColumns.DATE_TAKEN)
            return resolver.insert(collection, fallback)
        }
    }

    /** 按 `_id IN (...)` 批量取源条目，返回 id → MediaItem。 */
    private fun querySources(
        resolver: ContentResolver,
        ids: List<Long>,
    ): Map<Long, MediaItem> {
        val sources = HashMap<Long, MediaItem>(ids.size)
        ids.chunked(ID_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(", ") { "?" }
            val selection = "(${MediaStoreFetcher.BASE_SELECTION}) AND " +
                "(${MediaStore.MediaColumns._ID} IN ($placeholders))"
            MediaStoreFetcher.queryItems(
                resolver,
                selection,
                chunk.map { it.toString() }.toTypedArray(),
                // 顺序无关（按 id 建 map），这里仅取一个合法排序列（Provider 要求非空）
                sortOrder = MediaStore.MediaColumns._ID,
            ).forEach { item -> sources[item.id] = item }
        }
        return sources
    }

    /**
     * 目标目录下已用的文件名（小写），用于同批复制去重。
     *
     * 只查 `RELATIVE_PATH = ?`：MediaProvider 新建条目统一带结尾斜杠，目标目录由本方法规范化后同口径。
     * 查询失败时返回空集合（继续复制，MediaProvider 侧可能自动改名）。
     */
    private fun existingDisplayNames(
        resolver: ContentResolver,
        relativePath: String,
    ): MutableSet<String> {
        val names = HashSet<String>()
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )
        collections.forEach { collection ->
            try {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(relativePath),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { names.add(it.lowercase()) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "query existing names failed: $collection", e)
            }
        }
        return names
    }

    /** 目标文件名去重：`a.jpg` → `a (1).jpg` → `a (2).jpg` … */
    private fun uniqueDisplayName(
        source: MediaItem,
        mimeType: String,
        usedNames: MutableSet<String>,
    ): String {
        val original = sanitizeFileName(source.name)
            ?: defaultFileName(source.id, mimeType)
        val dot = original.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < original.length - 1
        val base = if (hasExtension) original.substring(0, dot) else original
        val extension = if (hasExtension) original.substring(dot) else ""

        var candidate = original
        var index = 1L
        while (candidate.lowercase() in usedNames && index <= MAX_DUPLICATE_INDEX) {
            candidate = "$base ($index)$extension"
            index++
        }
        return candidate
    }

    private fun sanitizeFileName(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name == "." || name == "..") {
            return null
        }
        // 文件名中不允许出现路径分隔符（MediaStore 的 _display_name 理论上已是纯文件名）
        val sanitized = name.replace('/', '_').replace('\\', '_')
        return sanitized.takeIf { it.isNotBlank() }
    }

    private fun defaultFileName(id: Long, mimeType: String): String {
        val extension = when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "video/mp4" -> ".mp4"
            "video/3gpp" -> ".3gp"
            "video/quicktime" -> ".mov"
            "video/webm" -> ".webm"
            else -> if (mimeType.startsWith("video/")) ".mp4" else ".jpg"
        }
        return "media_$id$extension"
    }

    private fun resolveMimeType(source: MediaItem): String {
        val declared = source.mimeType
        if (!declared.isNullOrBlank() && declared.contains('/')) {
            return declared
        }
        return if (source.isVideo) "video/mp4" else "image/jpeg"
    }

    /**
     * 规范化 RELATIVE_PATH：去首尾空白/斜杠、统一分隔符、拒绝 `..`，返回带结尾斜杠的形式。
     * 非法路径返回空字符串（调用方按失败计数处理）。
     */
    private fun normalizeRelativePath(raw: String): String {
        val path = raw.trim().replace('\\', '/').trim('/')
        if (path.isEmpty()) {
            return ""
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return ""
        }
        return "$path/"
    }
}
