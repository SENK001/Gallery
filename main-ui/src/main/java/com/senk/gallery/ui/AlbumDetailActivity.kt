package com.senk.gallery.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.senk.gallery.data.album.CustomAlbumStore
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.data.provider.GalleryCursorReader
import com.senk.gallery.external.GalleryExternal
import com.senk.gallery.ui.grid.GridPageController
import com.senk.gallery.ui.grid.MediaGridView
import com.senk.gallery.ui.picker.ImagePickerActivity
import com.senk.gallery.ui.selection.SelectionActionBar
import com.senk.gallery.ui.selection.SelectionActions
import com.senk.gallery.ui.selection.SelectionState
import com.senk.gallery.ui.theme.ThemeUtils
import com.senk.gallery.util.DisplayUtils
import com.senk.gallery.util.SystemBarUtils

/**
 * 相册详情页：与首页照片页同一套宫格（[MediaGridView]）+ 分页 + 多选 + 顶部渐变 chrome。
 *
 * 与照片页的差异只有两处：
 * - 本页没有底部胶囊导航，宫格底部预留只需系统导航栏高度（多选时再加动作栏高度）；
 * - 顶栏在本页（Activity），因此标题/返回键/菜单的状态切换也在本页完成。
 *
 * 渐变 chrome 的规则与照片页完全一致（40px 死区，见 [onGridScrolled]）；
 * 多选时标题 =「已选择 N 项」、导航图标 = 关闭、菜单 = 全选，返回键先退出多选。
 */
class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var grid: MediaGridView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusBarBg: View
    private lateinit var scrim: View
    private lateinit var actionBar: SelectionActionBar
    private var pageController: GridPageController? = null
    private var actions: SelectionActions? = null
    private var baseUri: Uri = Uri.EMPTY
    private var albumTitle: String = ""

    /** 相册类型 / 自建相册 id（仅 custom 类型支持标题栏「添加」管理照片）。 */
    private var albumType: String = ""
    private var albumId: String? = null
    private var addPhotosItem: MenuItem? = null
    private var multiSelectItem: MenuItem? = null
    private var selectAllItem: MenuItem? = null

    /** 已进入「缩略图位于标题栏下方」的状态。 */
    private var scrolledPast = false

    /** 顶部预留（状态栏 + 标题栏）像素。 */
    private var topInset = 0

    /** 系统导航栏高度像素。 */
    private var systemBarsBottom = 0

    /** 上一次回调的选中数量：用于「用户取消最后一项时自动退出多选」。 */
    private var lastSelectionCount = 0

    /** `toggleSelectAll` 期间置 true：取消全选导致 count=0 时不自动退出多选。 */
    private var suppressAutoExitOnZero = false

    // ActivityResult（删除授权 / 添加到相册）统一在这里注册，再转给 SelectionActions；
    // 收藏已改为应用内自建库（不写系统媒体库），无需系统授权。
    private val trashConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        actions?.onTrashConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    private val addToAlbumLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        actions?.onAddToAlbumResult(result.resultCode, result.data)
    }

    /**
     * 自建相册「添加」：相册详情页标题栏 → 选图页（预选相册已有成员）→ 按 diff 增删成员。
     */
    private val addPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        onAddPhotosResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_album_detail)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
        grid = findViewById(R.id.album_grid)
        toolbar = findViewById(R.id.toolbar)
        statusBarBg = findViewById(R.id.album_status_bar_bg)
        scrim = findViewById(R.id.album_toolbar_scrim)
        actionBar = findViewById(R.id.selection_action_bar)

        albumTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        albumType = intent.getStringExtra(EXTRA_ALBUM_TYPE).orEmpty()
        albumId = intent.getStringExtra(EXTRA_ALBUM_ID)?.takeIf { it.isNotBlank() }
        baseUri = intent.getStringExtra(EXTRA_BASE_URI)?.let(Uri::parse) ?: Uri.EMPTY
        setupToolbar()
        setupSelectionActions()
        setupGrid()
        applyInsets()
        val controller = GridPageController(this, grid, baseUri, PAGE_SIZE).apply {
            onResetLoaded = {
                // 回顶重载后标题栏恢复静止态
                scrolledPast = false
                applyChrome(false)
                updateGridInsets()
            }
        }
        pageController = controller
        controller.start(lifecycleScope)
    }

    override fun onDestroy() {
        pageController?.stop()
        pageController = null
        actions = null
        super.onDestroy()
    }

    // ==================== 初始化 ====================

    private fun setupToolbar() {
        toolbar.title = albumTitle
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // 菜单用代码建：本页属于 main-ui 模块，不新增 main-ui 的 menu 资源（菜单资源约定放在 main）
        // 自建相册额外提供「添加」：进选图页管理相册成员（预选已有照片，可加/可减）。
        if (albumType == MediaSet.TYPE_CUSTOM && albumId != null) {
            addPhotosItem = toolbar.menu.add(
                Menu.NONE,
                MENU_ADD_PHOTOS,
                0,
                R.string.album_add_action,
            ).apply {
                icon = ContextCompat.getDrawable(this@AlbumDetailActivity, R.drawable.ic_add)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    openAddPhotosPicker()
                    true
                }
            }
        }
        multiSelectItem = toolbar.menu.add(
            Menu.NONE,
            MENU_MULTI_SELECT,
            0,
            R.string.grid_multi_select,
        ).apply {
            icon = ContextCompat.getDrawable(this@AlbumDetailActivity, R.drawable.ic_multi_select)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                enterSelectionMode()
                true
            }
        }
        selectAllItem = toolbar.menu.add(
            Menu.NONE,
            MENU_SELECT_ALL,
            0,
            R.string.grid_select_all,
        ).apply {
            icon = ContextCompat.getDrawable(this@AlbumDetailActivity, R.drawable.ic_select_all)
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
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (grid.selection.isActive) {
                        exitSelectionMode()
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    private fun setupSelectionActions() {
        val selectionActions = SelectionActions(
            context = this,
            onExitSelection = { exitSelectionMode() },
            onReload = { pageController?.load(reset = true) },
            onMessage = { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            },
        ).apply {
            launchTrashConsent = { sender ->
                trashConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            launchAddToAlbum = { intent -> addToAlbumLauncher.launch(intent) }
        }
        actions = selectionActions
        actionBar.onShareClick = { selectionActions.share(selectedItems()) }
        actionBar.onFavoriteClick = { selectionActions.favorite(selectedItems()) }
        actionBar.onAddToAlbumClick = { selectionActions.addToAlbum(selectedItems()) }
        actionBar.onDeleteClick = { selectionActions.delete(selectedItems()) }
    }

    private fun setupGrid() {
        grid.columns = COLUMNS
        grid.setEmptyText(getString(R.string.gallery_empty))
        grid.onItemClick = { item -> openViewer(item) }
        grid.onItemLongClick = { item ->
            if (!grid.selection.isActive) {
                enterSelectionMode(item)
            }
        }
        grid.onLoadMore = { pageController?.load(reset = false) }
        grid.onScrollChanged = { scrollY -> onGridScrolled(scrollY.toFloat()) }
        grid.onSelectionChanged = { state -> handleSelectionChanged(state) }
    }

    // ==================== 多选 ====================

    private fun enterSelectionMode(initial: MediaItem? = null) {
        grid.selection.enter(initial)
    }

    private fun exitSelectionMode() {
        grid.selection.exit()
    }

    private fun handleSelectionChanged(state: SelectionState) {
        // 用户取消最后一项时自动退出多选（进入多选但还没选任何项时不触发）
        if (state.isActive && state.count == 0 && lastSelectionCount > 0 &&
            !suppressAutoExitOnZero
        ) {
            lastSelectionCount = 0
            grid.selection.exit()
            return
        }
        lastSelectionCount = state.count
        // 未选择任何项时置灰禁用底部动作菜单（点击无响应、不弹 Toast）
        actionBar.setActionsEnabled(state.isActive && state.count > 0)
        // 悬浮胶囊：进入多选从「圆」展开成胶囊，退出时收缩隐藏
        if (state.isActive) {
            actionBar.showAnimated()
        } else {
            actionBar.hideAnimated()
        }
        if (state.isActive) {
            toolbar.title = getString(R.string.grid_selected_count, state.count)
            toolbar.setNavigationIcon(R.drawable.ic_close)
            addPhotosItem?.isVisible = false
            multiSelectItem?.isVisible = false
            selectAllItem?.isVisible = true
            // 全选按钮切换语义：全部已选时显示「取消全选」
            selectAllItem?.title = getString(
                if (pageController?.isAllSelected() == true) {
                    R.string.grid_deselect_all
                } else {
                    R.string.grid_select_all
                },
            )
        } else {
            toolbar.title = albumTitle
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            addPhotosItem?.isVisible = true
            multiSelectItem?.isVisible = true
            selectAllItem?.isVisible = false
        }
        // 图标颜色随「是否已上划」重刷（标题/图标颜色只由 applyChrome 决定，避免两处状态打架）
        applyChrome(scrolledPast)
        updateGridInsets()
    }

    private fun selectedItems(): List<MediaItem> =
        pageController?.selectedItems().orEmpty()

    // ==================== 数据加载 ====================

    private fun openViewer(item: MediaItem) {
        val index = pageController?.indexOf(item.id) ?: -1
        if (index < 0) {
            return
        }
        startActivity(ViewerActivity.intent(this, baseUri, index))
    }

    // ==================== 自建相册：添加/管理照片 ====================

    /** 打开选图页，并预选当前相册已有成员（选图页据此自动勾选）。 */
    private fun openAddPhotosPicker() {
        if (albumId == null) {
            return
        }
        addPhotosLauncher.launch(
            ImagePickerActivity.intent(
                context = this,
                allowMultiple = true,
                maxCount = ImagePickerActivity.MAX_COUNT_UNLIMITED,
                title = getString(R.string.album_pick_title),
                preselectedAlbumUri = baseUri.toString(),
            ),
        )
    }

    /**
     * 选图页结果：按「选中集合」更新自建相册成员。
     *
     * 语义：预选已勾选相册现有成员，用户取消勾选 = 从相册移除，新勾选 = 加入；
     * 因此最终成员 = 选图页返回的 id 集合（diff 增删，避免重复写整表）。
     */
    private fun onAddPhotosResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            return
        }
        val id = albumId ?: return
        val store = CustomAlbumStore.get(this)
        val album = store.album(id)
        if (album == null) {
            Toast.makeText(this, R.string.album_update_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIds = data?.getLongArrayExtra(ImagePickerActivity.EXTRA_SELECTED_IDS)
            ?.filter { it > 0L }
            ?.toSet()
            .orEmpty()
        val existingIds = album.itemIds.toSet()
        val toAdd = selectedIds.filterNot { it in existingIds }
        val toRemove = existingIds.filterNot { it in selectedIds }
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            Toast.makeText(this, R.string.album_update_same, Toast.LENGTH_SHORT).show()
            return
        }
        var added = 0
        var removed = 0
        if (toAdd.isNotEmpty()) {
            val coverCandidate = data
                ?.getStringArrayListExtra(GalleryExternal.EXTRA_SELECTED_URIS)
                ?.firstOrNull()
            added = store.addItems(id, toAdd, coverCandidate)
        }
        if (toRemove.isNotEmpty()) {
            removed = store.removeItems(id, toRemove)
        }
        // 若被移除的恰好是封面条目，清掉封面（相册卡由成员最新一条兜底，避免灰封面）
        val coverUri = store.album(id)?.coverUri
        val coverId = coverUri?.let { Uri.parse(it).lastPathSegment?.toLongOrNull() }
        if (coverId != null && coverId in toRemove) {
            store.setCover(id, null)
        }
        contentResolver.notifyChange(GalleryContract.CONTENT_URI, null)
        pageController?.load(reset = true)
        if (added <= 0 && removed <= 0) {
            Toast.makeText(this, R.string.album_update_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            getString(R.string.album_update_done, added, removed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ==================== 顶部 chrome / insets ====================

    /**
     * 宫格铺满整个窗口，因此根布局不吃 inset，只把 inset 分给覆盖层：
     * 状态栏底条高度、标题栏的 marginTop、渐变遮罩高度；
     * 另外把宫格的内容内边距设为「状态栏 + 标题栏」与「导航栏高度（多选时 + 动作栏）」。
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.album_detail_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemBarsBottom = bars.bottom
            // 改 layoutParams 后必须重新赋值才会重新布局
            statusBarBg.layoutParams = statusBarBg.layoutParams.apply { height = bars.top }
            (toolbar.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = bars.top
                it.leftMargin = bars.left
                it.rightMargin = bars.right
                toolbar.layoutParams = it
            }
            val scrimHeight = bars.top + toolbarHeightPx()
            topInset = scrimHeight
            (scrim.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.height = scrimHeight
                scrim.layoutParams = it
            }
            scrim.background = ToolbarScrimDrawable(scrimHeight)
            actionBar.setBottomInset(bars.bottom)
            updateGridInsets()
            insets
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.album_detail_root))
    }

    private fun updateGridInsets() {
        val selectionActive = grid.selection.isActive
        val bottom = if (selectionActive) {
            dp2px(ACTION_BAR_HEIGHT_DP) + systemBarsBottom
        } else {
            systemBarsBottom
        }
        grid.setContentInsets(topInset, bottom)
    }

    private fun toolbarHeightPx(): Int {
        val tv = android.util.TypedValue()
        val ok = theme.resolveAttribute(
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

    private fun dp2px(dp: Float): Int = DisplayUtils.dp2px(this, dp).toInt()

    /**
     * 与首页照片页同样的判定：有 40px 死区，避免刚滑动一帧就闪变。
     *
     * 静止 → 主题色标题栏 + 主题色标题/返回键（宫格第一行在其下方）；
     * 上划 → 标题栏让位给渐变遮罩，标题/返回键/菜单图标转白色，缩略图滚到状态栏区域。
     */
    private fun onGridScrolled(scrollY: Float) {
        val past = scrollY > SCROLL_DEAD_ZONE_PX
        if (past == scrolledPast) {
            return
        }
        scrolledPast = past
        applyChrome(past)
    }

    /**
     * 标题栏背景**始终透明**：静止时透出父容器的 chrome 底色，
     * 上划时由渐变遮罩接管；这里只切换文字/图标颜色与遮罩显隐，
     * 颜色一律经 [ThemeUtils] 取，深色模式自动适配。
     */
    private fun applyChrome(overGrid: Boolean) {
        val titleColor = if (overGrid) {
            ThemeUtils.textOnScrim(this)
        } else {
            ThemeUtils.textPrimary(this)
        }
        val iconColor = if (overGrid) {
            ThemeUtils.iconOnScrim(this)
        } else {
            ThemeUtils.iconOnSurface(this)
        }
        toolbar.setTitleTextColor(titleColor)
        toolbar.navigationIcon?.setTint(iconColor)
        // 菜单图标（多选/全选）同样跟随标题栏明暗；menu item 的 iconTintList 对已创建/未创建的 action view 都生效
        val tint = ColorStateList.valueOf(iconColor)
        addPhotosItem?.iconTintList = tint
        multiSelectItem?.iconTintList = tint
        selectAllItem?.iconTintList = tint
        scrim.isVisible = overGrid
        statusBarBg.isVisible = !overGrid
        // 系统栏图标明暗与底色一致：静止为 chrome 底色，上划为深色遮罩
        ThemeUtils.applySystemBarAppearance(
            this,
            if (overGrid) ThemeUtils.mediaScrim(this) else ThemeUtils.surfaceChrome(this),
        )
    }

    companion object {

        private const val EXTRA_BASE_URI = "base_uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ALBUM_TYPE = "album_type"
        private const val EXTRA_ALBUM_ID = "album_id"
        private const val PAGE_SIZE = 200
        private const val COLUMNS = 4

        /** 滚动死区：小于它视为「还在顶部」，标题栏保持透明、不显示遮罩。 */
        private const val SCROLL_DEAD_ZONE_PX = 40f

        /** 多选动作栏高度（dp），与 activity_album_detail.xml 中动作栏的 layout_height 保持一致。 */
        private const val ACTION_BAR_HEIGHT_DP = 64f

        /** 代码创建的菜单项 id（menu 资源在 main 模块，本页不新增 main-ui menu 资源）。 */
        private const val MENU_ADD_PHOTOS = 0x1000
        private const val MENU_MULTI_SELECT = 0x1001
        private const val MENU_SELECT_ALL = 0x1002

        fun start(context: Context, album: GalleryCursorReader.AlbumInfo) {
            val baseUri = when (album.albumType) {
                MediaSet.TYPE_FOLDER ->
                    GalleryContract.folderAlbumItemsUri(album.bucketId.orEmpty())
                MediaSet.TYPE_THIRD_PARTY ->
                    GalleryContract.appAlbumItemsUri(album.ownerPackage.orEmpty())
                MediaSet.TYPE_CUSTOM ->
                    GalleryContract.customAlbumItemsUri(album.bucketId ?: album.id)
                else -> GalleryContract.albumItemsUri(album.albumType)
            }
            context.startActivity(
                Intent(context, AlbumDetailActivity::class.java)
                    .putExtra(EXTRA_BASE_URI, baseUri.toString())
                    .putExtra(EXTRA_TITLE, album.name)
                    .putExtra(EXTRA_ALBUM_TYPE, album.albumType)
                    .putExtra(EXTRA_ALBUM_ID, album.bucketId)
            )
        }
    }
}
