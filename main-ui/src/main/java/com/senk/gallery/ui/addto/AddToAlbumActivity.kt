package com.senk.gallery.ui.addto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.senk.gallery.data.album.CustomAlbumStore
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.data.provider.GalleryCursorReader
import com.senk.gallery.data.provider.MediaWriter
import com.senk.gallery.ui.MediaQuery
import com.senk.gallery.ui.R
import com.senk.gallery.ui.theme.ThemeUtils
import com.senk.gallery.util.DisplayUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「添加到」目标相册选择页。
 *
 * 设计（与自建相册的数据模型一致）：
 * - **自建相册 = 逻辑加入**：只把媒体 ID 追加进 `CustomAlbumStore`，不复制文件；
 * - **相机 / 文件夹相册 = 物理复制**：用 [MediaWriter] 把媒体写成目标目录下的新文件
 *   （`DCIM/Camera/` 或该目录相册的 relativePath），复制完成后相册可见。
 *
 * 页面结构：标题栏 + 分组列表
 * 1. 自建相册：首行「新建相册」动作行（建完立即加入并返回），其后是已有自建相册；
 * 2. 文件夹相册：「相机」+ provider `category=more` 中的目录相册。
 *
 * 结果契约（供多选「添加到」入口消费）：
 * - `RESULT_OK` 时 [EXTRA_ADDED_COUNT] = 实际加入/复制成功的数量；
 * - 取消（返回键）不设置结果。
 */
class AddToAlbumActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: AddToAlbumAdapter

    /** 正在执行复制/新建，期间忽略列表点击与返回，避免重复目标任务。 */
    private var busy = false

    private val mediaIds: List<Long> by lazy {
        intent.getLongArrayExtra(EXTRA_MEDIA_IDS)
            ?.filter { it > 0L }
            ?.distinct()
            .orEmpty()
    }

    private val store: CustomAlbumStore by lazy { CustomAlbumStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_to_album)
        applyInsets()

        toolbar = findViewById(R.id.addto_toolbar)
        toolbar.setNavigationOnClickListener {
            if (!busy) {
                finish()
            }
        }
        list = findViewById(R.id.addto_list)
        emptyView = findViewById(R.id.addto_empty)
        adapter = AddToAlbumAdapter(
            onAction = { action ->
                if (action.id == ACTION_NEW_ALBUM) {
                    createAlbumAndAdd()
                }
            },
            onCustomAlbumClick = ::addToCustomAlbum,
            onFolderAlbumClick = { info, isCamera -> copyToFolder(info, isCamera) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        if (mediaIds.isEmpty()) {
            list.isVisible = false
            emptyView.isVisible = true
            return
        }
        loadTargets()
    }

    /**
     * 系统栏避让。
     *
     * targetSdk 36 下窗口强制 edge-to-edge（内容默认铺到状态栏/导航栏下面），本页不是铺满式页面，
     * 因此把 system bars inset 直接作为根布局 padding：标题栏落在状态栏下方、列表不被导航栏压住。
     * 必须走 `setOnApplyWindowInsetsListener`：onCreate 时视图尚未 attach，直接读 root insets 恒为 0。
     */
    private fun applyInsets() {
        val root = findViewById<android.view.View>(R.id.addto_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** 读取三类目标：自建相册（provider）、相机（常用）、目录相册（更多）。 */
    private fun loadTargets() {
        lifecycleScope.launch {
            val resolver = contentResolver
            val custom = MediaQuery.albums(resolver, GalleryContract.CATEGORY_CUSTOM)
            val common = MediaQuery.albums(resolver, GalleryContract.CATEGORY_COMMON)
            val more = MediaQuery.albums(resolver, GalleryContract.CATEGORY_MORE)
            if (isFinishing || isDestroyed) {
                return@launch
            }
            // 「相机」即使当前没有照片也保留入口（复制到 DCIM/Camera/）
            val camera = common.firstOrNull { it.albumType == MediaSet.TYPE_CAMERA }
                ?: cameraFallback()
            val folders = more.filter { it.albumType == MediaSet.TYPE_FOLDER }

            val rows = ArrayList<AddToAlbumAdapter.Row>()
            rows.add(AddToAlbumAdapter.Row.Header(getString(R.string.addto_section_custom)))
            rows.add(
                AddToAlbumAdapter.Row.Action(
                    id = ACTION_NEW_ALBUM,
                    title = getString(R.string.addto_new_album),
                    iconRes = R.drawable.ic_create_folder,
                ),
            )
            if (custom.isEmpty()) {
                rows.add(AddToAlbumAdapter.Row.Caption(getString(R.string.addto_empty_custom)))
            } else {
                custom.forEach { rows.add(AddToAlbumAdapter.Row.CustomAlbum(it)) }
            }
            rows.add(AddToAlbumAdapter.Row.Header(getString(R.string.addto_section_folders)))
            rows.add(AddToAlbumAdapter.Row.FolderAlbum(camera, isCamera = true))
            folders.forEach { rows.add(AddToAlbumAdapter.Row.FolderAlbum(it, isCamera = false)) }
            adapter.submit(rows)
        }
    }

    // ==================== 自建相册（逻辑加入） ====================

    /** 「新建相册」：建完立即把所选加入该相册并返回（新相册必然全量加入）。 */
    private fun createAlbumAndAdd() {
        if (busy) {
            return
        }
        busy = true
        AlbumNameDialog.show(
            context = this,
            title = getString(R.string.album_new_title),
            onDismiss = { busy = false },
        ) { name ->
            val trimmed = name.trim()
            val album = store.create(trimmed)
            if (album == null) {
                if (store.containsName(trimmed)) {
                    getString(R.string.album_name_duplicate_error)
                } else {
                    getString(R.string.album_name_invalid_error)
                }
            } else {
                val added = store.addItems(album.id, mediaIds)
                notifyCustomAlbumsChanged()
                if (added > 0) {
                    toast(getString(R.string.addto_toast_added, album.name))
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDED_COUNT, added))
                } else {
                    // 新相册为空，正常不会走到；保守起见不谎报成功
                    toast(getString(R.string.addto_toast_add_failed))
                }
                finish()
                null
            }
        }
    }

    private fun addToCustomAlbum(info: GalleryCursorReader.AlbumInfo) {
        val albumId = info.bucketId ?: return
        if (busy) {
            return
        }
        busy = true
        lifecycleScope.launch {
            try {
                val added = withContext(Dispatchers.IO) { store.addItems(albumId, mediaIds) }
                notifyCustomAlbumsChanged()
                if (added > 0) {
                    toast(getString(R.string.addto_toast_added, info.name))
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDED_COUNT, added))
                } else {
                    // 全部已在相册中：不返回 RESULT_OK，避免调用方误报「已添加 N 项」
                    toast(getString(R.string.addto_toast_already, info.name))
                }
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                toast(getString(R.string.addto_toast_add_failed))
            }
        }
    }

    // ==================== 相机 / 文件夹相册（物理复制） ====================

    private fun copyToFolder(info: GalleryCursorReader.AlbumInfo, isCamera: Boolean) {
        if (busy) {
            return
        }
        busy = true
        val targetName = if (isCamera) getString(R.string.addto_camera) else info.name
        val targetPath = if (isCamera) {
            CAMERA_RELATIVE_PATH
        } else {
            info.folderPath?.takeIf { it.isNotBlank() } ?: "Pictures/${info.name}/"
        }
        val progress = createProgressViews(mediaIds.size)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.addto_copy_title, targetName))
            .setView(progress.container)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            try {
                val result = MediaWriter.copyToFolder(
                    resolver = contentResolver,
                    mediaIds = mediaIds,
                    relativePath = targetPath,
                ) { done, total ->
                    // MediaWriter 的进度回调在 IO 线程触发，切回主线程更新 UI
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }
                        progress.bar.progress = done
                        progress.text.text = getString(R.string.addto_copying, done, total)
                    }
                }
                dismissSafely(dialog)
                busy = false
                if (result.success > 0) {
                    val message = if (result.failed > 0) {
                        getString(R.string.addto_toast_copy_partial, result.success, result.failed)
                    } else {
                        getString(R.string.addto_toast_copied, result.success, targetName)
                    }
                    toast(message)
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDED_COUNT, result.success))
                    finish()
                } else {
                    // 全失败：留在本页让用户重试/换目标，不要静默关闭
                    toast(getString(R.string.addto_toast_copy_failed, result.failed))
                }
            } catch (e: CancellationException) {
                dismissSafely(dialog)
                throw e
            } catch (e: Exception) {
                dismissSafely(dialog)
                busy = false
                toast(getString(R.string.addto_toast_copy_failed, mediaIds.size))
            }
        }
    }

    private class ProgressViews(
        val container: LinearLayout,
        val bar: LinearProgressIndicator,
        val text: TextView,
    )

    /** 复制进度视图：标题 + 「正在复制 x/y…」+ 进度条（不用布局文件，随对话框生命周期回收）。 */
    private fun createProgressViews(total: Int): ProgressViews {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                DisplayUtils.dp2px(this@AddToAlbumActivity, 24f).toInt(),
                DisplayUtils.dp2px(this@AddToAlbumActivity, 20f).toInt(),
                DisplayUtils.dp2px(this@AddToAlbumActivity, 24f).toInt(),
                DisplayUtils.dp2px(this@AddToAlbumActivity, 8f).toInt(),
            )
        }
        val text = TextView(this).apply {
            text = getString(R.string.addto_copying, 0, total)
            setTextColor(ThemeUtils.textSecondary(this@AddToAlbumActivity))
            textSize = 14f
        }
        val bar = LinearProgressIndicator(this).apply {
            max = total.coerceAtLeast(1)
            progress = 0
            isIndeterminate = false
            setIndicatorColor(ThemeUtils.brandPrimary(this@AddToAlbumActivity))
        }
        container.addView(text)
        container.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = DisplayUtils.dp2px(this@AddToAlbumActivity, 12f).toInt()
            },
        )
        return ProgressViews(container, bar, text)
    }

    // ==================== 工具 ====================

    private fun cameraFallback(): GalleryCursorReader.AlbumInfo =
        GalleryCursorReader.AlbumInfo(
            id = MediaSet.TYPE_CAMERA,
            albumType = MediaSet.TYPE_CAMERA,
            name = getString(R.string.addto_camera),
            coverUri = null,
            count = 0,
            bucketId = null,
            ownerPackage = null,
            folderPath = CAMERA_RELATIVE_PATH,
        )

    /** 自建相册数据在 Provider 之外变化，显式通知根 URI，相册页/选图页才会刷新。 */
    private fun notifyCustomAlbumsChanged() {
        contentResolver.notifyChange(GalleryContract.CONTENT_URI, null)
    }

    private fun dismissSafely(dialog: android.app.Dialog) {
        if (isFinishing || isDestroyed) {
            return
        }
        try {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        } catch (e: Exception) {
            // Activity 已销毁时 dismiss 可能抛异常，忽略
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {

        /** 传入待添加的媒体 ID（MediaStore `_id`）。 */
        const val EXTRA_MEDIA_IDS = "media_ids"

        /** 返回给调用方的实际添加/复制成功数量（`RESULT_OK` 时读取）。 */
        const val EXTRA_ADDED_COUNT = "added_count"

        private const val ACTION_NEW_ALBUM = "new_album"
        private const val CAMERA_RELATIVE_PATH = "DCIM/Camera/"

        fun intent(context: Context, mediaIds: LongArray): Intent =
            Intent(context, AddToAlbumActivity::class.java)
                .putExtra(EXTRA_MEDIA_IDS, mediaIds)
    }
}
