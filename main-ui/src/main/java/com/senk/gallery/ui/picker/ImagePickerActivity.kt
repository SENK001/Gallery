package com.senk.gallery.ui.picker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.provider.AlbumResolver
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.data.provider.GalleryCursorReader
import com.senk.gallery.external.GalleryExternal
import com.senk.gallery.ui.AlbumListAdapter
import com.senk.gallery.ui.MediaQuery
import com.senk.gallery.ui.R
import com.senk.gallery.ui.grid.MediaGridView
import com.senk.gallery.ui.grid.MediaPager
import com.senk.gallery.ui.selection.SelectionState
import com.senk.gallery.ui.theme.ThemeUtils
import kotlinx.coroutines.launch

/**
 * 选图页：可被外部调用（经 `main` 的 `ExternalEntryActivity` 路由）与内部复用。
 *
 * 交互约定：
 * - 顶部工具栏 = 关闭（相册内容二级视图时为返回）+ 标题；下方两个分类 tab「照片 / 相册」。
 * - 照片 tab：全量图片+视频，按拍摄时间倒序分页（宫格复用 `ui.grid.MediaGridView`）。
 * - 相册 tab：常用 / 我的相册 / 更多 三组；点相册在同一 Activity 内进入其内容（返回回相册列表）。
 * - 两个 tab 共享**同一份选择集合**（跨来源选图）：宫格的 [SelectionState] 只保存 id，
 *   本类维护 `id -> MediaItem` 的有序映射用于结果组装与跨数据源保留。
 * - 单选模式（[GalleryExternal.EXTRA_ALLOW_MULTIPLE] = false）点击一张立即返回；
 *   多选模式点击切换勾选，底部确认栏显示「已选择 N 项」+「完成」，
 *   超过 maxCount 时提示且不选中，未选择时「完成」禁用。
 *
 * 结果契约（供 `main` 的 `ExternalEntryActivity` 消费）：
 * - `data` = 第一张的原始 MediaStore URI；
 * - `ClipData` 含全部原始 URI（多选）；
 * - [GalleryExternal.EXTRA_SELECTED_URIS] = ArrayList<String> 原始 URI；
 * - [EXTRA_SELECTED_IDS] = LongArray 媒体 id；
 * - [GalleryExternal.EXTRA_SELECTED_COUNT] = Int 数量。
 */
class ImagePickerActivity : AppCompatActivity() {

    private var allowMultiple = false
    private var maxCount = GalleryExternal.DEFAULT_MAX_COUNT
    private var pickerTitle: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var mediaPane: FrameLayout
    private lateinit var albumList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var confirmBar: View
    private lateinit var selectedCountView: TextView
    private lateinit var doneButton: MaterialButton

    private var albumAdapter: AlbumListAdapter? = null

    /** 照片 tab / 相册内容 tab 共用的宫格与分页数据源。 */
    private lateinit var grid: MediaGridView
    private var pager: MediaPager? = null

    /**
     * 数据源版本号：切换照片/相册时自增。
     * 旧数据源的异步加载结果回来时据此丢弃，避免把上一来源的条目画到当前视图。
     */
    private var loadToken = 0
    private var loading = false

    /** 是否处于「相册内容」二级视图（从相册 tab 点进某个相册）。 */
    private var inAlbumContent = false

    /** 相册内容视图对应的相册（决定内容 URI 与标题）。 */
    private var albumInfo: GalleryCursorReader.AlbumInfo? = null

    private var observer: ContentObserver? = null

    /**
     * 已选媒体：id -> MediaItem，按选择先后顺序。
     * 与宫格 [SelectionState] 双向对齐（状态是唯一权威，本映射用于结果组装与跨来源保留实例）。
     */
    private val selectedItems = LinkedHashMap<Long, MediaItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_image_picker)

        readIntentExtras()
        bindViews()
        setupToolbar()
        setupTabs()
        setupInsets()
        setupAlbumList()
        setupConfirmBar()
        registerBackCallback()
        setupMediaGrid()
        registerObserver()
        ThemeUtils.applySystemBarAppearance(this, ThemeUtils.surfaceChrome(this))

        if (allowMultiple) {
            // 多选态常驻：勾选框可见；上限交给 SelectionState，越界时宫格回调 onSelectionLimitReached。
            grid.selection.maxCount = maxCount
            grid.selectionMode = true
            confirmBar.isVisible = true
            updateConfirmBar()
        }
        switchTab(TAB_PHOTOS)
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    // ==================== 初始化 ====================

    private fun readIntentExtras() {
        allowMultiple = intent.getBooleanExtra(GalleryExternal.EXTRA_ALLOW_MULTIPLE, false)
        maxCount = intent.getIntExtra(GalleryExternal.EXTRA_MAX_COUNT, GalleryExternal.DEFAULT_MAX_COUNT)
            .let { if (it <= 0) GalleryExternal.DEFAULT_MAX_COUNT else it }
        pickerTitle = intent.getStringExtra(GalleryExternal.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.picker_title_default)
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.picker_toolbar)
        tabs = findViewById(R.id.picker_tabs)
        mediaPane = findViewById(R.id.picker_media_pane)
        albumList = findViewById(R.id.picker_album_list)
        emptyView = findViewById(R.id.picker_empty_view)
        confirmBar = findViewById(R.id.picker_confirm_bar)
        selectedCountView = findViewById(R.id.picker_selected_count)
        doneButton = findViewById(R.id.picker_done)
    }

    private fun setupToolbar() {
        toolbar.setTitle(pickerTitle)
        updateNavigationIcon(back = false)
        toolbar.setNavigationOnClickListener { handleBack() }
    }

    private fun setupTabs() {
        tabs.addTab(
            tabs.newTab().setText(R.string.gallery_tab_photos).apply {
                contentDescription = getString(R.string.picker_tab_photos_desc)
            },
        )
        tabs.addTab(
            tabs.newTab().setText(R.string.gallery_tab_albums).apply {
                contentDescription = getString(R.string.picker_tab_albums_desc)
            },
        )
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            /** 相册 tab 的二级视图里再点一次相册 tab → 回到相册列表。 */
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == TAB_ALBUMS && inAlbumContent) {
                    showAlbumList()
                }
            }
        })
    }

    /**
     * 顶栏铺满窗口，内容不铺到系统栏下面，因此根的 inset 直接作为 padding：
     * 状态栏区域露出根布局的 chrome 底色，底部确认栏避开系统导航栏。
     */
    private fun setupInsets() {
        val root = findViewById<View>(R.id.picker_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupAlbumList() {
        // 注意用命名参数：适配器后续参数（onActionClick/onAlbumLongClick）都有默认值，
        // 尾随 lambda 会绑定到最后一个参数，不能依赖尾随 lambda 语法。
        val adapter = AlbumListAdapter(onAlbumClick = { album -> openAlbum(album) })
        albumAdapter = adapter
        albumList.adapter = adapter
        (albumList.layoutManager as? GridLayoutManager)?.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = adapter.spanSize(position)
            }
    }

    private fun setupConfirmBar() {
        doneButton.contentDescription = getString(R.string.picker_done)
        doneButton.setOnClickListener { finishWithSelection() }
        updateConfirmBar()
    }

    private fun setupMediaGrid() {
        grid = MediaGridView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            columns = COLUMNS
            // 空态文案随数据源切换（照片 / 某相册内容）
            setEmptyText(getString(R.string.picker_photos_empty))
            onItemClick = { item ->
                // 只有单选模式会走到这里（多选态的点击由宫格内部 toggle）
                if (!allowMultiple) {
                    finishWithItems(listOf(item))
                }
            }
            onLoadMore = { loadPage(reset = false) }
            onSelectionChanged = { state -> syncSelectionFromState(state) }
            onSelectionLimitReached = { limit -> showMaxCountToast(limit) }
        }
        mediaPane.addView(grid)
        pager = MediaPager(this, GalleryContract.MEDIA_URI, PAGE_SIZE)
    }

    private fun registerObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (isFinishing || isDestroyed) {
                    return
                }
                // 当前视图的数据源变化时刷新（多选集合按 id 保留，不受影响）
                when {
                    mediaPane.isVisible -> loadPage(reset = true)
                    albumList.isVisible -> refreshAlbums()
                }
            }
        }
        contentResolver.registerContentObserver(GalleryContract.CONTENT_URI, true, observer)
        this.observer = observer
    }

    // ==================== Tab / 视图切换 ====================

    private fun switchTab(position: Int) {
        when (position) {
            TAB_ALBUMS -> showAlbumList()
            else -> showPhotos()
        }
    }

    /** 照片 tab：显示全量媒体宫格。 */
    private fun showPhotos() {
        inAlbumContent = false
        albumInfo = null
        updateNavigationIcon(back = false)
        toolbar.setTitle(pickerTitle)
        albumList.isVisible = false
        mediaPane.isVisible = true
        emptyView.isVisible = false
        loadDataSource(GalleryContract.MEDIA_URI, R.string.picker_photos_empty)
    }

    /** 相册 tab：显示相册分组列表（常用 / 我的相册 / 更多）。 */
    private fun showAlbumList() {
        inAlbumContent = false
        albumInfo = null
        updateNavigationIcon(back = false)
        toolbar.setTitle(pickerTitle)
        albumList.isVisible = true
        mediaPane.isVisible = false
        emptyView.isVisible = false
        refreshAlbums()
    }

    /** 相册 tab 二级视图：显示某个相册的内容宫格。 */
    private fun openAlbum(album: GalleryCursorReader.AlbumInfo) {
        inAlbumContent = true
        albumInfo = album
        updateNavigationIcon(back = true)
        toolbar.setTitle(album.name)
        albumList.isVisible = false
        mediaPane.isVisible = true
        emptyView.isVisible = false
        // 相册内容 URI：统一由 core-data 的 AlbumResolver 决定（目录 / 第三方 / 自建相册各自分支）
        val baseUri = AlbumResolver.albumItemsUri(
            album.albumType,
            album.bucketId,
            album.ownerPackage,
        )
        loadDataSource(baseUri, R.string.picker_album_items_empty)
    }

    private fun refreshAlbums() {
        lifecycleScope.launch {
            val resolver = contentResolver
            // 「最近删除」是回收站入口，不是可选图来源：选图页的相册 tab 中过滤掉。
            val common = MediaQuery.albums(resolver, GalleryContract.CATEGORY_COMMON)
                .filter { it.albumType != MediaSet.TYPE_RECENTLY_DELETED }
            val custom = MediaQuery.albums(resolver, GalleryContract.CATEGORY_CUSTOM)
            val more = MediaQuery.albums(resolver, GalleryContract.CATEGORY_MORE)
            if (isFinishing || isDestroyed) {
                return@launch
            }
            val rows = ArrayList<AlbumListAdapter.Row>()
            if (common.isNotEmpty()) {
                rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_common)))
                common.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = true)) }
            }
            if (custom.isNotEmpty()) {
                rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_custom)))
                custom.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = false)) }
            }
            if (more.isNotEmpty()) {
                rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_more)))
                more.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = false)) }
            }
            albumAdapter?.submit(rows)
            // 空态与列表二者只有一个可见：列表（重新）有内容时必须把空态藏起来
            if (rows.isEmpty()) {
                emptyView.setText(R.string.picker_albums_empty)
            }
            emptyView.isVisible = rows.isEmpty()
        }
    }

    // ==================== 宫格数据加载 ====================

    /**
     * 切换数据源并重新加载第一页。
     *
     * 每次切换都新建 [MediaPager]（版本号 +1）：旧数据源的在途查询结果回来后
     * 因 [loadToken] 不匹配被丢弃，不会串到新视图里。
     */
    private fun loadDataSource(baseUri: Uri, emptyTextRes: Int) {
        loadToken++
        loading = false
        pager = MediaPager(this, baseUri, PAGE_SIZE)
        grid.setEmptyText(getString(emptyTextRes))
        // 立即清空上一来源的条目，避免切换瞬间显示错内容 / 空态与内容不一致
        grid.setItems(emptyList())
        loadPage(reset = true)
    }

    /**
     * 加载一页：reset = 从第一页重载（刷新/切数据源），否则追加下一页（滚动触发）。
     * 加载完成后同步宫格数据，并把已选集合里属于当前数据源的 MediaItem 实例刷新一遍。
     */
    private fun loadPage(reset: Boolean) {
        val currentPager = pager ?: return
        if (loading && !reset) {
            return
        }
        val token = loadToken
        loading = true
        lifecycleScope.launch {
            val beforeSize = currentPager.items().size
            val changed = currentPager.load(reset)
            if (isFinishing || isDestroyed) {
                loading = false
                return@launch
            }
            if (token != loadToken) {
                // 数据源已切换：丢弃旧结果
                loading = false
                return@launch
            }
            if (changed) {
                val full = currentPager.items()
                if (reset) {
                    grid.setItems(full)
                    grid.scrollToTop()
                } else if (full.size > beforeSize) {
                    grid.appendItems(full.subList(beforeSize, full.size))
                }
                refreshSelectedItemInstances()
            }
            loading = false
        }
    }

    // ==================== 选择与结果 ====================

    /**
     * 宫格选择状态变化：以 [SelectionState] 为权威同步本类的 id -> MediaItem 映射。
     * 跨 tab/相册保留：状态里移除的 id 从映射删除；当前已加载数据里的新选中项补齐实例。
     */
    private fun syncSelectionFromState(state: SelectionState) {
        val ids = state.selectedIds
        selectedItems.keys.retainAll(ids)
        pager?.items()?.forEach { item ->
            if (ids.contains(item.id)) {
                selectedItems[item.id] = item
            }
        }
        updateConfirmBar()
    }

    /** 数据刷新后用新实例替换映射里的旧 MediaItem（id/uri 不变，顺序保持）。 */
    private fun refreshSelectedItemInstances() {
        val ids = selectedItems.keys
        if (ids.isEmpty()) {
            return
        }
        pager?.items()?.forEach { item ->
            if (ids.contains(item.id)) {
                selectedItems[item.id] = item
            }
        }
    }

    private fun updateConfirmBar() {
        selectedCountView.text = getString(R.string.picker_selected_count, selectedItems.size)
        doneButton.isEnabled = selectedItems.isNotEmpty()
    }

    private fun showMaxCountToast(limit: Int) {
        Toast.makeText(
            this,
            getString(R.string.picker_max_count_reached, limit),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun finishWithSelection() {
        if (selectedItems.isEmpty()) {
            return
        }
        finishWithItems(selectedItems.values.toList())
    }

    /** 组装结果并关闭页面；`items` 至少一个元素。数据全部使用**原始 MediaStore URI**。 */
    private fun finishWithItems(items: List<MediaItem>) {
        val valid = items.filter { it.uri != null }
        if (valid.isEmpty()) {
            finish()
            return
        }
        val uris = valid.map { it.uri.toString() }
        val result = Intent()
        // data = 第一张的原始 URI
        result.data = valid.first().uri
        // ClipData = 全部原始 URI（多选；单选也带上，对只读 ClipData 的调用方更友好）
        val clip = ClipData.newRawUri(null, valid.first().uri)
        valid.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        result.clipData = clip
        result.putStringArrayListExtra(GalleryExternal.EXTRA_SELECTED_URIS, ArrayList(uris))
        result.putExtra(EXTRA_SELECTED_IDS, valid.map { it.id }.toLongArray())
        result.putExtra(GalleryExternal.EXTRA_SELECTED_COUNT, valid.size)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun updateNavigationIcon(back: Boolean) {
        toolbar.setNavigationIcon(if (back) R.drawable.ic_arrow_back else R.drawable.ic_close)
        toolbar.navigationIcon?.setTint(ThemeUtils.iconOnSurface(this))
        toolbar.navigationContentDescription =
            getString(if (back) R.string.picker_back else R.string.picker_close)
    }

    /**
     * 返回语义：
     * - 相册内容二级视图 → 回相册列表；
     * - 其余情况（含相册列表、照片 tab）→ 取消并关闭选图页。
     */
    private fun handleBack() {
        if (inAlbumContent) {
            showAlbumList()
            return
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun registerBackCallback() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            },
        )
    }

    companion object {

        /**
         * 结果 extra：所选媒体的 MediaStore id 数组。
         * `main/ExternalEntryActivity` 用它把 id 映射成对外代理 URI。
         */
        const val EXTRA_SELECTED_IDS = "com.senk.gallery.extra.SELECTED_IDS"

        /**
         * 内部调用表示"不限数量"的 [maxCount] 取值（外部调用契约仍以 ≤0 表示用默认 9）。
         * 用于「相册页新建相册后选择照片/视频」等需要一次加入多张的内部场景。
         */
        const val MAX_COUNT_UNLIMITED = Int.MAX_VALUE

        private const val TAB_PHOTOS = 0
        private const val TAB_ALBUMS = 1
        private const val COLUMNS = 4
        private const val PAGE_SIZE = MediaPager.DEFAULT_PAGE_SIZE

        /**
         * 构造选图页 Intent（内部复用入口）。
         *
         * @param allowMultiple 是否多选；false 时点选一张立即返回。
         * @param maxCount 多选上限（≤0 时用 [GalleryExternal.DEFAULT_MAX_COUNT]；
         *   不限数量传 [MAX_COUNT_UNLIMITED]）。
         * @param title 选图页标题（可选，空串时用默认「选择照片」）。
         */
        fun intent(
            context: Context,
            allowMultiple: Boolean = false,
            maxCount: Int = GalleryExternal.DEFAULT_MAX_COUNT,
            title: String? = null,
        ): Intent = Intent(context, ImagePickerActivity::class.java)
            .putExtra(GalleryExternal.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            .putExtra(GalleryExternal.EXTRA_MAX_COUNT, if (maxCount <= 0) GalleryExternal.DEFAULT_MAX_COUNT else maxCount)
            .apply { title?.let { putExtra(GalleryExternal.EXTRA_TITLE, it) } }
    }
}
