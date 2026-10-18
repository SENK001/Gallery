package com.senk.gallery.ui

import android.app.Activity
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.senk.gallery.data.album.CustomAlbumStore
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.data.provider.GalleryCursorReader
import com.senk.gallery.external.GalleryExternal
import com.senk.gallery.ui.addto.AlbumNameDialog
import com.senk.gallery.ui.picker.ImagePickerActivity
import kotlinx.coroutines.launch

class AlbumsFragment : Fragment(R.layout.fragment_albums) {

    private var list: RecyclerView? = null
    private var emptyView: TextView? = null
    private var adapter: AlbumListAdapter? = null
    private var observer: ContentObserver? = null
    private var loading = false

    /** 已应用的顶部 padding，避免 insets 每次回调都重设一次。 */
    private var lastPaddingTop = -1

    private lateinit var store: CustomAlbumStore

    /** 新建相册后正在等待选图结果的目标相册（null 表示当前没有等待中的选图）。 */
    private var pendingNewAlbum: GalleryCursorReader.AlbumInfo? = null

    /**
     * 新建相册后的选图页结果：把选中的照片/视频加入刚创建的相册。
     *
     * 选图页即 `ImagePickerActivity`（照片/相册双 tab、多选、图片+视频都支持）；
     * 用户取消时保留刚创建的空相册（与旧行为一致，可之后再「添加到」或删除）。
     */
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        onPickedForNewAlbum(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CustomAlbumStore.get(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.album_list)
        emptyView = view.findViewById(R.id.empty_view)
        adapter = AlbumListAdapter(
            onAlbumClick = { album ->
                if (album.albumType == MediaSet.TYPE_RECENTLY_DELETED) {
                    RecentlyDeletedActivity.start(requireContext())
                } else {
                    AlbumDetailActivity.start(requireContext(), album)
                }
            },
            onActionClick = { action ->
                if (action.id == ACTION_NEW_ALBUM) {
                    showCreateAlbumDialog()
                }
            },
            onAlbumLongClick = { album -> onAlbumLongPressed(album) },
        )
        list?.adapter = adapter
        (list?.layoutManager as? GridLayoutManager)?.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    adapter?.spanSize(position) ?: 1
            }
        applyTopInsetOnInsets(view)
        registerObserver()
        refresh()
    }

    /**
     * 相册页**保持原样**：标题栏是不透明白底，内容不从这里下面穿过。
     * 由于宿主（MainActivity）的 pager 现在铺满全屏、不再给根布局加内边距，
     * 改由列表自己按「状态栏 + 标题栏」预留顶部，等价于改动前的占位式布局。
     *
     * 用 padding（配合布局里的 `clipToPadding=false`）而不是 margin，保留滚动手感。
     * 同样必须走 insets 监听：onViewCreated 时视图还没 attach，读不到真实 inset。
     */
    private fun applyTopInsetOnInsets(root: View) {
        val list = this.list ?: return
        val basePaddingTop = list.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val target = basePaddingTop + statusBar + toolbarHeightPx()
            if (target != lastPaddingTop) {
                lastPaddingTop = target
                list.setPadding(
                    list.paddingLeft,
                    target,
                    list.paddingRight,
                    list.paddingBottom,
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun toolbarHeightPx(): Int {
        val tv = android.util.TypedValue()
        val ok = requireContext().theme.resolveAttribute(
            androidx.appcompat.R.attr.actionBarSize,
            tv,
            true,
        )
        return if (ok) {
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            0
        }
    }

    override fun onDestroyView() {
        observer?.let {
            requireContext().contentResolver.unregisterContentObserver(it)
        }
        observer = null
        list = null
        emptyView = null
        adapter = null
        super.onDestroyView()
    }

    private fun registerObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        requireContext().contentResolver.registerContentObserver(
            GalleryContract.CONTENT_URI,
            true,
            observer,
        )
        this.observer = observer
    }

    fun refresh() {
        if (loading || view == null || !isAdded) {
            return
        }
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resolver = requireContext().contentResolver
            val common = MediaQuery.albums(resolver, GalleryContract.CATEGORY_COMMON)
            // 自建相册：逻辑集合，空相册也要显示（数据层不做空相册过滤）
            val custom = MediaQuery.albums(resolver, GalleryContract.CATEGORY_CUSTOM)
            val more = MediaQuery.albums(resolver, GalleryContract.CATEGORY_MORE)
            if (view == null) {
                loading = false
                return@launch
            }
            val rows = ArrayList<AlbumListAdapter.Row>()
            if (common.isNotEmpty()) {
                rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_common)))
                common.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = true)) }
            }
            // 「我的相册」分区：始终显示（首行是「新建相册」入口，没有相册时它就是唯一入口）
            rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_custom)))
            rows.add(
                AlbumListAdapter.Row.Action(
                    title = getString(R.string.album_action_new),
                    iconRes = R.drawable.ic_create_folder,
                    id = ACTION_NEW_ALBUM,
                ),
            )
            custom.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = false)) }
            if (more.isNotEmpty()) {
                rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_more)))
                more.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = false)) }
            }
            // 最近删除：暂缓功能，详见 docs/backlog.md
            adapter?.submit(rows)
            emptyView?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            loading = false
        }
    }

    // ==================== 自建相册：新建 / 删除 ====================

    private fun showCreateAlbumDialog() {
        if (!isAdded) {
            return
        }
        AlbumNameDialog.show(
            context = requireContext(),
            title = getString(R.string.album_new_title),
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
                val albumInfo = GalleryCursorReader.AlbumInfo(
                    id = MediaSet.TYPE_CUSTOM + ":" + album.id,
                    albumType = MediaSet.TYPE_CUSTOM,
                    name = album.name,
                    coverUri = null,
                    count = 0,
                    bucketId = album.id,
                    ownerPackage = null,
                    folderPath = null,
                )
                pendingNewAlbum = albumInfo
                // 创建成功后立即进入选图页（多选、不限数量），选中的照片/视频加入新相册。
                pickMediaLauncher.launch(
                    ImagePickerActivity.intent(
                        context = requireContext(),
                        allowMultiple = true,
                        maxCount = ImagePickerActivity.MAX_COUNT_UNLIMITED,
                        title = getString(R.string.album_pick_title),
                    ),
                )
                null
            }
        }
    }

    /**
     * 处理新建相册后的选图结果。
     *
     * 结果约定（[ImagePickerActivity]）：`RESULT_OK` 时 `EXTRA_SELECTED_IDS` 为所选媒体 id 数组、
     * `GalleryExternal.EXTRA_SELECTED_URIS` 为首选封面 URI；取消时保留空相册并刷新列表。
     */
    private fun onPickedForNewAlbum(resultCode: Int, data: Intent?) {
        val album = pendingNewAlbum
        pendingNewAlbum = null
        val ctx = context ?: return
        if (album == null) {
            return
        }
        val ids = if (resultCode == Activity.RESULT_OK) {
            data?.getLongArrayExtra(ImagePickerActivity.EXTRA_SELECTED_IDS)
                ?.filter { it > 0L }
                ?.distinct()
                .orEmpty()
        } else {
            emptyList()
        }
        if (ids.isEmpty()) {
            // 用户没有选图：相册已创建，保持为空相册并刷新（可之后再加/删除）
            refresh()
            return
        }
        val coverUri = data?.getStringArrayListExtra(GalleryExternal.EXTRA_SELECTED_URIS)
            ?.firstOrNull()
        val added = store.addItems(album.bucketId.orEmpty(), ids, coverUri)
        // 自建相册成员不经过 MediaStore：显式通知根 URI，相册页/选图页等同步刷新
        ctx.contentResolver.notifyChange(GalleryContract.CONTENT_URI, null)
        refresh()
        if (added <= 0) {
            Toast.makeText(ctx, R.string.addto_toast_add_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            ctx,
            getString(R.string.album_pick_added, added),
            Toast.LENGTH_SHORT,
        ).show()
        // 直接进入相册详情，用户立即看到刚加入的照片/视频
        AlbumDetailActivity.start(ctx, album.copy(count = added))
    }

    private fun onAlbumLongPressed(album: GalleryCursorReader.AlbumInfo) {
        // 只有自建相册可删除（相机/目录/三方相册来自 MediaStore，不能在这里删）
        if (album.albumType != MediaSet.TYPE_CUSTOM) {
            return
        }
        if (!isAdded) {
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.album_delete_title)
            .setMessage(getString(R.string.album_delete_message, album.name))
            .setNegativeButton(R.string.album_dialog_cancel, null)
            .setPositiveButton(R.string.album_delete_confirm) { _, _ ->
                val albumId = album.bucketId.orEmpty()
                val removed = albumId.isNotEmpty() && store.delete(albumId)
                val ctx = context
                if (ctx == null) {
                    return@setPositiveButton
                }
                if (removed) {
                    Toast.makeText(
                        ctx,
                        getString(R.string.album_delete_done, album.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    // 自建相册数据不经过 MediaStore，显式通知根 URI，
                    // 让选图页等其它已打开的页面同步刷新
                    ctx.contentResolver.notifyChange(GalleryContract.CONTENT_URI, null)
                    refresh()
                } else {
                    Toast.makeText(ctx, R.string.album_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private companion object {
        const val ACTION_NEW_ALBUM = "new_album"
    }
}
