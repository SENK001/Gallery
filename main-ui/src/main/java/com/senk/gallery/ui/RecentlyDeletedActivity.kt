package com.senk.gallery.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.senk.gallery.data.album.FavoriteStore
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.ui.dialog.DeleteDialogs
import com.senk.gallery.ui.dialog.GalleryDialog
import com.senk.gallery.ui.grid.GridPageController
import com.senk.gallery.ui.grid.MediaGridView
import com.senk.gallery.ui.selection.SelectionState
import com.senk.gallery.ui.theme.ThemeUtils
import com.senk.gallery.ui.util.MediaManageAccess
import com.senk.gallery.ui.transition.ViewerTransition
import com.senk.gallery.util.SystemBarUtils
import kotlinx.coroutines.launch

/**
 * 「最近删除」页：展示系统回收站里的图片/视频（`is_trashed = 1`）。
 *
 * 数据：`content://…/albums/recently_deleted`（Provider → `AlbumResolver` 的
 * `TRASH_SELECTION` + `QUERY_ARG_MATCH_TRASHED = MATCH_ONLY`）；系统 30 天后自动清理。
 *
 * 交互（与查看器/相册页一致的交互习惯）：
 * - 点击缩略图 → 查看器（baseUri 也是最近删除列表，翻页范围即回收站）；
 * - 长按或标题栏「多选」进入多选 → 标题「已选择 N 项」、右上角选择框；
 * - 底部动作栏：**恢复** / **彻底删除**。默认走 `MediaStore.createTrashRequest` /
 *   `createDeleteRequest` 由系统确认；持有 MANAGE_MEDIA 时直接 update/delete，
 *   彻底删除改为应用自定义危险确认弹窗；
 * - 标题栏溢出菜单：**清空**（一次性彻底删除回收站全部条目，同样走系统确认）。
 *
 * 恢复/删除成功后：退出多选、重新加载列表；空回收站时「最近删除」入口随相册页自动隐藏。
 */
class RecentlyDeletedActivity : AppCompatActivity() {

    private lateinit var grid: MediaGridView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var actionBar: View
    private lateinit var restoreAction: View
    private lateinit var deleteAction: View
    private var pageController: GridPageController? = null
    private var multiSelectItem: MenuItem? = null
    private var selectAllItem: MenuItem? = null
    private var clearItem: MenuItem? = null

    /** 上一次回调的选中数量：用于「取消最后一项时自动退出多选」。 */
    private var lastSelectionCount = 0

    /** `toggleSelectAll` 期间置 true：取消全选导致 count=0 时不自动退出多选。 */
    private var suppressAutoExitOnZero = false

    /** 正在等待系统确认的动作（恢复 / 彻底删除 / 清空）。 */
    private enum class PendingAction { RESTORE, DELETE, CLEAR_ALL }

    private var pendingAction: PendingAction? = null
    private var pendingCount = 0

    /** 正在等待系统确认的媒体 id：恢复时清收藏删除标记，彻底删除时移除收藏行。 */
    private var pendingIds: List<Long> = emptyList()

    private val trashBaseUri: Uri by lazy {
        GalleryContract.albumItemsUri(MediaSet.TYPE_RECENTLY_DELETED)
    }

    /** 恢复 / 彻底删除 / 清空都要经过系统「是否允许修改媒体」确认框。 */
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        onConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recently_deleted)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)

        toolbar = findViewById(R.id.trash_toolbar)
        grid = findViewById(R.id.trash_grid)
        actionBar = findViewById(R.id.trash_action_bar)

        applyInsets()
        setupToolbar()
        setupGrid()
        setupActions()
        setupBackHandling()
        load()
    }

    private fun setupActions() {
        restoreAction = findViewById(R.id.trash_action_restore)
        deleteAction = findViewById(R.id.trash_action_delete)
        restoreAction.setOnClickListener { restoreSelected() }
        deleteAction.setOnClickListener { deleteSelected() }
        setTrashActionsEnabled(false)
    }

    /** 未选择任何项时置灰禁用「恢复 / 彻底删除」（点击无响应、不弹 Toast，也不穿透到宫格）。 */
    private fun setTrashActionsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        for (action in listOf(restoreAction, deleteAction)) {
            // 保持 clickable=true：禁用时仍消费触摸，避免点到下面的宫格误选
            action.isEnabled = enabled
            action.alpha = alpha
        }
    }

    /** 本页不是铺满式页面：把 system bars 的 inset 直接作为根布局 padding。 */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.recently_deleted_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    // ==================== 初始化 ====================

    private fun setupToolbar() {
        toolbar.title = getString(R.string.trash_title)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // 菜单用代码建（main-ui 不新增 menu 资源，与相册详情页同一约定）。
        multiSelectItem = toolbar.menu.add(
            Menu.NONE,
            MENU_MULTI_SELECT,
            0,
            R.string.grid_multi_select,
        ).apply {
            icon = ContextCompat.getDrawable(this@RecentlyDeletedActivity, R.drawable.ic_multi_select)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                grid.selection.enter()
                true
            }
        }
        selectAllItem = toolbar.menu.add(
            Menu.NONE,
            MENU_SELECT_ALL,
            0,
            R.string.grid_select_all,
        ).apply {
            icon = ContextCompat.getDrawable(this@RecentlyDeletedActivity, R.drawable.ic_select_all)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
            setOnMenuItemClickListener {
                suppressAutoExitOnZero = true
                try {
                    pageController?.toggleSelectAll()
                } finally {
                    suppressAutoExitOnZero = false
                }
                true
            }
        }
        clearItem = toolbar.menu.add(
            Menu.NONE,
            MENU_CLEAR,
            1,
            R.string.trash_clear,
        ).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                confirmClearAll()
                true
            }
        }
        applyIcons()
        updateMenuVisibility()
    }

    private fun setupGrid() {
        grid.columns = COLUMNS
        grid.setEmptyText(getString(R.string.trash_empty))
        grid.onItemClickWithView = { item, cellView -> openViewer(item, cellView) }
        grid.onItemLongClick = { item ->
            if (!grid.selection.isActive) {
                grid.selection.enter(item)
            }
        }
        grid.onLoadMore = { pageController?.load(reset = false) }
        grid.onSelectionChanged = { state -> handleSelectionChanged(state) }
        val controller = GridPageController(this, grid, trashBaseUri, PAGE_SIZE)
        pageController = controller
        controller.onLoaded = {
            clearItem?.isEnabled = controller.items().isNotEmpty()
        }
        controller.start(lifecycleScope)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (grid.selection.isActive) {
                        grid.selection.exit()
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    private fun load() {
        pageController?.load(reset = true)
    }

    override fun onDestroy() {
        pageController?.stop()
        pageController = null
        super.onDestroy()
    }

    // ==================== 多选 UI ====================

    private fun handleSelectionChanged(state: SelectionState) {
        // 用户取消最后一项时自动退出多选（通过菜单刚进入、还没选任何项时不触发）
        if (state.isActive && state.count == 0 && lastSelectionCount > 0 &&
            !suppressAutoExitOnZero
        ) {
            lastSelectionCount = 0
            grid.selection.exit()
            return
        }
        lastSelectionCount = state.count
        setTrashActionsEnabled(state.isActive && state.count > 0)
        actionBar.isVisible = state.isActive
        if (state.isActive) {
            toolbar.title = getString(R.string.grid_selected_count, state.count)
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.navigationContentDescription = getString(R.string.grid_exit_selection)
            // 全选按钮切换语义：全部已选时显示「取消全选」
            selectAllItem?.title = getString(
                if (pageController?.isAllSelected() == true) {
                    R.string.grid_deselect_all
                } else {
                    R.string.grid_select_all
                },
            )
        } else {
            toolbar.title = getString(R.string.trash_title)
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.navigationContentDescription = getString(R.string.trash_back)
        }
        applyIcons()
        updateMenuVisibility()
    }

    private fun updateMenuVisibility() {
        val selectionActive = grid.selection.isActive
        multiSelectItem?.isVisible = !selectionActive
        selectAllItem?.isVisible = selectionActive
        clearItem?.isVisible = !selectionActive
        clearItem?.isEnabled = pageController?.items()?.isNotEmpty() == true
    }

    private fun applyIcons() {
        val color = ColorStateList.valueOf(ThemeUtils.iconOnSurface(this))
        toolbar.navigationIcon?.setTint(color.defaultColor)
        multiSelectItem?.iconTintList = color
        selectAllItem?.iconTintList = color
    }

    private fun openViewer(item: MediaItem, cellView: View? = null) {
        val index = pageController?.indexOf(item.id) ?: return
        if (index < 0) {
            return
        }
        if (cellView != null) {
            ViewerTransition.remember(item.id, cellView)
        }
        startActivity(ViewerActivity.intent(this, trashBaseUri, index))
        overridePendingTransition(0, 0)
        overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    }

    // ==================== 恢复 / 彻底删除 ====================

    private fun restoreSelected() {
        val items = pageController?.selectedItems().orEmpty()
        val uris = items.mapNotNull { it.uri }
        if (uris.isEmpty()) {
            return
        }
        if (MediaManageAccess.isGranted(this)) {
            // MANAGE_MEDIA：直接恢复，不再弹系统确认框
            if (MediaManageAccess.restoreFromTrash(this, uris)) {
                pendingAction = PendingAction.RESTORE
                pendingCount = uris.size
                pendingIds = items.map { it.id }
                onConsentResult(true)
            } else {
                // 权限实际执行时失效：回退系统确认
                requestSystemRestore(items, uris)
            }
            return
        }
        requestSystemRestore(items, uris)
    }

    private fun requestSystemRestore(items: List<MediaItem>, uris: List<Uri>) {
        try {
            val request = MediaStore.createTrashRequest(contentResolver, uris, false)
            pendingAction = PendingAction.RESTORE
            pendingCount = uris.size
            pendingIds = items.map { it.id }
            consentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            pendingAction = null
            toast(getString(R.string.trash_action_failed))
        }
    }

    private fun deleteSelected() {
        val items = pageController?.selectedItems().orEmpty()
        val uris = items.mapNotNull { it.uri }
        if (uris.isEmpty()) {
            return
        }
        if (MediaManageAccess.isGranted(this)) {
            // MANAGE_MEDIA：应用自定义危险确认弹窗 → 直接彻底删除，不再弹系统确认框
            DeleteDialogs.confirmPermanentDelete(this, uris.size) {
                if (MediaManageAccess.deletePermanently(this, uris)) {
                    pendingAction = PendingAction.DELETE
                    pendingCount = uris.size
                    pendingIds = items.map { it.id }
                    onConsentResult(true)
                } else {
                    requestSystemDelete(items, uris)
                }
            }
            return
        }
        requestSystemDelete(items, uris)
    }

    private fun requestSystemDelete(items: List<MediaItem>, uris: List<Uri>) {
        try {
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            pendingAction = PendingAction.DELETE
            pendingCount = uris.size
            pendingIds = items.map { it.id }
            consentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            pendingAction = null
            toast(getString(R.string.trash_action_failed))
        }
    }

    /** 清空：先二次确认，再删（有 MANAGE_MEDIA 直接删，否则走系统「彻底删除」授权）。 */
    private fun confirmClearAll() {
        lifecycleScope.launch {
            val all = MediaQuery.media(
                contentResolver,
                GalleryContract.albumItemsPageUri(MediaSet.TYPE_RECENTLY_DELETED, 0, 0),
            )
            val uris = all.mapNotNull { it.uri }
            if (uris.isEmpty()) {
                return@launch
            }
            GalleryDialog.builder(this@RecentlyDeletedActivity)
                .setTitle(getString(R.string.trash_clear_title))
                .setMessage(getString(R.string.trash_clear_message, uris.size))
                .setNegativeButton(getString(R.string.trash_clear_cancel))
                .setPositiveButton(getString(R.string.trash_clear_confirm), destructive = true) {
                    if (MediaManageAccess.isGranted(this@RecentlyDeletedActivity)) {
                        if (MediaManageAccess.deletePermanently(
                                this@RecentlyDeletedActivity,
                                uris,
                            )
                        ) {
                            pendingAction = PendingAction.CLEAR_ALL
                            pendingCount = uris.size
                            pendingIds = all.map { it.id }
                            onConsentResult(true)
                        } else {
                            // 权限实际执行时失效：回退系统确认
                            requestSystemClear(all, uris)
                        }
                        return@setPositiveButton
                    }
                    requestSystemClear(all, uris)
                }
                .show()
        }
    }

    private fun requestSystemClear(all: List<MediaItem>, uris: List<Uri>) {
        try {
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            pendingAction = PendingAction.CLEAR_ALL
            pendingCount = uris.size
            pendingIds = all.map { it.id }
            consentLauncher.launch(
                IntentSenderRequest.Builder(request.intentSender).build(),
            )
        } catch (e: Exception) {
            pendingAction = null
            toast(getString(R.string.trash_clear_failed))
        }
    }

    private fun onConsentResult(granted: Boolean) {
        val action = pendingAction ?: return
        val count = pendingCount
        val ids = pendingIds
        pendingAction = null
        pendingIds = emptyList()
        if (!granted) {
            return
        }
        // 收藏库联动：恢复 → 清除删除标记（收藏状态回来）；
        // 彻底删除/清空 → 移除收藏行（不留无效数据）。
        val store = FavoriteStore.get(this)
        when (action) {
            PendingAction.RESTORE -> store.markTrashed(ids, false)
            PendingAction.DELETE, PendingAction.CLEAR_ALL -> store.removeIds(ids)
        }
        val message = when (action) {
            PendingAction.RESTORE -> getString(R.string.trash_restore_done, count)
            PendingAction.DELETE -> getString(R.string.trash_delete_done, count)
            PendingAction.CLEAR_ALL -> getString(R.string.trash_clear_done, count)
        }
        toast(message)
        if (grid.selection.isActive) {
            grid.selection.exit()
        }
        // MediaStore 变化会经 Provider 通知触发自动重载；这里显式再加载一次保证即时
        load()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {

        private const val COLUMNS = 4
        private const val PAGE_SIZE = 200

        /** 未选择任何项时动作按钮的透明度（与 SelectionActionBar 的禁用态一致）。 */
        private const val DISABLED_ALPHA = 0.38f

        /** 代码创建的菜单项 id（menu 资源约定放在 main 模块，本页不新增 main-ui menu 资源）。 */
        private const val MENU_MULTI_SELECT = 0x2001
        private const val MENU_SELECT_ALL = 0x2002
        private const val MENU_CLEAR = 0x2003

        fun start(context: Context) {
            context.startActivity(Intent(context, RecentlyDeletedActivity::class.java))
        }
    }
}
