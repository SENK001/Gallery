package com.senk.gallery.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.ui.grid.GridPageController
import com.senk.gallery.ui.grid.MediaGridView
import com.senk.gallery.ui.selection.SelectionActionBar
import com.senk.gallery.ui.selection.SelectionActions
import com.senk.gallery.ui.selection.SelectionState
import com.senk.gallery.util.DisplayUtils

/**
 * 照片页：RecyclerView 宫格（[MediaGridView]）+ 分页加载 + 多选模式。
 *
 * 页面结构（fragment_photos.xml）：
 * - 宫格铺满整个窗口（含状态栏/系统导航栏区域），首行位置由 `setContentInsets` 的
 *   top padding 预留（状态栏 + 标题栏）；标题栏本身是 MainActivity 的覆盖层；
 * - 底部多选动作栏只在多选态显示；此时宫格 bottom padding 从「胶囊导航占位 + 导航栏」
 *   切换为「动作栏高度 + 导航栏」。
 *
 * 与宿主的解耦：
 * - 标题栏渐变由 [onGridScroll] 交给 MainActivity（40px 死区判定在宿主）；
 * - 多选状态由 [onSelectionStateChanged] 通知宿主（标题「已选择 N 项」/导航图标/隐藏胶囊导航）；
 * - 宿主通过 [enterSelectionMode]/[exitSelectionMode]/[selectAll] 反向驱动本页。
 * - 分页/内容变化刷新等页面无关逻辑在 [GridPageController]（与相册详情页共用）。
 */
class PhotosFragment : Fragment(R.layout.fragment_photos) {

    private var grid: MediaGridView? = null
    private var actionBar: SelectionActionBar? = null
    private var pageController: GridPageController? = null
    private var actions: SelectionActions? = null

    /** 宫格滚动回调（像素，0 = 顶部），由宿主设置，用于驱动标题栏渐变。 */
    var onGridScroll: ((Float) -> Unit)? = null

    /** 多选状态变化回调（active = 是否多选，count = 已选数量），由宿主设置。 */
    var onSelectionStateChanged: ((active: Boolean, count: Int) -> Unit)? = null

    /** 当前是否处于多选模式。 */
    val isSelectionActive: Boolean
        get() = grid?.selection?.isActive == true

    /** 已应用的顶部预留（状态栏 + 标题栏）像素。 */
    private var topInset = 0

    /** 系统导航栏高度（底部 inset 的一部分）。 */
    private var systemBarsBottom = 0

    /** 上一次回调的选中数量：用于「用户取消最后一项时自动退出多选」。 */
    private var lastSelectionCount = 0

    // ActivityResult 统一在这里注册（生命周期安全），再转给 SelectionActions：
    // 收藏/删除走系统授权 IntentSender，添加到相册走普通 ActivityResult。
    private val trashConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        actions?.onTrashConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    private val favoriteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        actions?.onFavoriteConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    private val addToAlbumLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        actions?.onAddToAlbumResult(result.resultCode, result.data)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mediaGrid = view.findViewById<MediaGridView>(R.id.photo_grid)
        val selectionBar = view.findViewById<SelectionActionBar>(R.id.selection_action_bar)
        grid = mediaGrid
        actionBar = selectionBar

        val selectionActions = SelectionActions(
            context = requireContext(),
            onExitSelection = { exitSelectionMode() },
            onReload = { loadPage(reset = true) },
            onMessage = { message -> toast(message) },
        ).apply {
            launchTrashConsent = { sender ->
                trashConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            launchFavoriteConsent = { sender ->
                favoriteConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            launchAddToAlbum = { intent -> addToAlbumLauncher.launch(intent) }
        }
        actions = selectionActions

        selectionBar.onShareClick = { selectionActions.share(selectedItems()) }
        selectionBar.onFavoriteClick = { selectionActions.favorite(selectedItems()) }
        selectionBar.onAddToAlbumClick = { selectionActions.addToAlbum(selectedItems()) }
        selectionBar.onDeleteClick = { selectionActions.delete(selectedItems()) }

        mediaGrid.apply {
            columns = COLUMNS
            // 空态文案（宫格内部适配器的一行空态项）
            setEmptyText(getString(R.string.gallery_empty))
            onItemClick = { item -> openViewer(item) }
            onItemLongClick = { item ->
                // 长按进入多选并选中该格（已在多选态时点击由格子自身切换选中）
                if (!selection.isActive) {
                    enterSelectionMode(item)
                }
            }
            onLoadMore = { loadPage(reset = false) }
            onScrollChanged = { scrollY -> onGridScroll?.invoke(scrollY.toFloat()) }
            onSelectionChanged = { state -> handleSelectionChanged(state) }
        }

        val controller = GridPageController(
            context = requireContext(),
            grid = mediaGrid,
            baseUri = GalleryContract.MEDIA_URI,
            pageSize = PAGE_SIZE,
        ).apply {
            // 回顶重载后标题栏恢复静止态（宿主按 scrollY 判断）
            onResetLoaded = { onGridScroll?.invoke(0f) }
        }
        pageController = controller

        applyInsets(view)
        controller.start(viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        pageController?.stop()
        pageController = null
        grid = null
        actionBar = null
        actions = null
        super.onDestroyView()
    }

    // ==================== 对外接口（MainActivity 用） ====================

    /**
     * 进入多选模式（无初始选中项；长按格子时传该格）。
     * 由主界面「多选」菜单或长按格子触发。
     */
    fun enterSelectionMode(initial: MediaItem? = null) {
        grid?.selection?.enter(initial)
    }

    /** 退出多选模式（返回键/关闭按钮触发）。 */
    fun exitSelectionMode() {
        grid?.selection?.exit()
    }

    /** 全选当前已加载的条目（受 selection.maxCount 限制）。 */
    fun selectAll() {
        pageController?.selectAll()
    }

    /**
     * 把宫格**当前**滚动位置同步给宿主（MainActivity 的标题栏三态）。
     *
     * 使用场景：ViewPager2 切页不会把宫格滚回顶部，RecyclerView 会保留原滚动位置；
     * 而切页返回时不会产生滚动事件，导致宿主缓存的「是否已上划」状态与实际不符
     * （真机现象：照片页上划后切到相册再切回，标题变黑、渐变遮罩消失、状态栏不透明）。
     * 宿主在切回照片页 / Fragment resume 时主动调用一次，用真实位置纠正标题栏外观。
     */
    fun syncGridScrollState() {
        val scrollY = grid?.currentScrollY() ?: 0
        onGridScroll?.invoke(scrollY.toFloat())
    }

    // ==================== 数据加载 ====================

    /**
     * 加载数据。reset = true 时从第一页重载并回顶（首次加载 / 内容变化）。
     * 由内容变化、授权后刷新、分页（onLoadMore）三条路径调用。
     */
    fun loadPage(reset: Boolean) {
        pageController?.load(reset)
    }

    private fun openViewer(item: MediaItem) {
        val index = pageController?.indexOf(item.id) ?: -1
        if (index < 0) {
            return
        }
        startActivity(
            ViewerActivity.intent(requireContext(), GalleryContract.MEDIA_URI, index),
        )
    }

    // ==================== 多选 UI ====================

    private fun handleSelectionChanged(state: SelectionState) {
        // 用户取消最后一项时自动退出多选（通过菜单刚进入、还没选任何项时不触发）
        if (state.isActive && state.count == 0 && lastSelectionCount > 0) {
            lastSelectionCount = 0
            grid?.selection?.exit()
            return
        }
        lastSelectionCount = state.count
        actionBar?.isVisible = state.isActive
        // 多选时底部换成动作栏高度，避免最后一行被动作栏遮挡
        updateGridInsets()
        onSelectionStateChanged?.invoke(state.isActive, state.count)
    }

    /** 动作栏动作的目标集合：按当前列表顺序返回已选条目。 */
    private fun selectedItems(): List<MediaItem> =
        pageController?.selectedItems().orEmpty()

    // ==================== insets ====================

    /**
     * 顶部预留 = 状态栏 + 标题栏：标题栏是宿主的透明覆盖层，但第一行缩略图必须落在它下方；
     * 底部预留 = 胶囊导航占位（72dp）+ 系统导航栏，多选时换成动作栏高度 + 导航栏。
     *
     * 必须走 insets 监听：onViewCreated 时视图还没 attach，直接读 root insets 恒为 0。
     */
    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemBarsBottom = bars.bottom
            topInset = bars.top + toolbarHeightPx()
            actionBar?.setPadding(0, 0, 0, bars.bottom)
            updateGridInsets()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateGridInsets() {
        val active = grid?.selection?.isActive == true
        val bottom = if (active) {
            dp2px(ACTION_BAR_HEIGHT_DP) + systemBarsBottom
        } else {
            dp2px(CAPSULE_AREA_DP) + systemBarsBottom
        }
        grid?.setContentInsets(topInset, bottom)
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

    private fun dp2px(dp: Float): Int = DisplayUtils.dp2px(requireContext(), dp).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PAGE_SIZE = 200
        private const val COLUMNS = 4

        /**
         * 底部悬浮胶囊导航「卡片高度 + 下边距」所占的高度（dp）。
         * 实测胶囊卡片高 182px、下边距 16dp≈52px，合计 234px ≈ 72dp（本机 density 3.25）。
         * 注意这里只算胶囊本身，**系统导航栏高度由 inset 另加**，不要重复计入。
         */
        private const val CAPSULE_AREA_DP = 72f

        /** 多选动作栏内容高度（dp），与 view_selection_action_bar.xml 的动作项高度一致；导航栏 inset 另加。 */
        private const val ACTION_BAR_HEIGHT_DP = 64f
    }
}
