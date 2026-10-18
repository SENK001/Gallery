package com.senk.gallery.ui.grid

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.ui.selection.SelectionState
import com.senk.gallery.util.DisplayUtils

/**
 * 宫格控件（RecyclerView 子类）—— 照片页、相册详情页、选图页共用的多选宫格。
 *
 * 设计要点：
 * - **一格一个 View**：替换旧的 GL 自绘宫格，天然支持无障碍（TalkBack 能读到每格节点）；
 * - 触摸/滚动/fling 全交给 RecyclerView，行为与系统一致；
 * - 多选状态由 [SelectionState] 持有，本类只负责：
 *   点击语义（普通点击 = onItemClick；多选态 = 内部 toggle）、局部刷新格子、把外部回调转发给宿主；
 * - 分页：接近底部时回调 [onLoadMore]，宿主用 [MediaPager] 拉下一页后 [appendItems]；
 * - 铺满式布局：用 [setContentInsets] 设 padding 并 `clipToPadding = false`，
 *   内容可滚到状态栏/标题栏下面（标题栏渐变由宿主按 [onScrollChanged] 驱动）。
 *
 * 公开 API 即跨组契约，改动前先与选图页/相册页对齐。
 */
class MediaGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    /** 多选状态（宿主可设置 maxCount、监听变化；宫格内部监听并刷新格子）。 */
    val selection = SelectionState()

    private val gridLayoutManager = GridLayoutManager(context, DEFAULT_COLUMNS)
    private val gapPx = DisplayUtils.dp2px(context, CELL_GAP_DP).toInt().coerceAtLeast(1)
    private val gridAdapter = MediaGridAdapter(
        context = context,
        selection = selection,
        cellSizeProvider = ::computeCellSize,
        emptyHeightProvider = { (height - paddingTop - paddingBottom).coerceAtLeast(0) },
    )

    /** 普通点击（非多选态）回调。 */
    var onItemClick: ((MediaItem) -> Unit)? = null

    /** 长按回调：照片页/相册详情页用它进入多选；多选态下的长按也走这里（宿主自行决定）。 */
    var onItemLongClick: ((MediaItem) -> Unit)? = null

    /** 接近底部、需要加载下一页时回调（宿主负责防重入；[setItems]/[appendItems] 会重置触发状态）。 */
    var onLoadMore: (() -> Unit)? = null

    /** 滚动量回调（像素；0 = 已到顶部）。宿主用 40px 死区驱动标题栏渐变。 */
    var onScrollChanged: ((scrollY: Int) -> Unit)? = null

    /** 多选状态变化回调（宿主更新标题栏、动作栏等）。 */
    var onSelectionChanged: ((SelectionState) -> Unit)? = null

    /** 点击未选格但已达 [SelectionState.maxCount] 上限时回调，参数为上限值。 */
    var onSelectionLimitReached: ((maxCount: Int) -> Unit)? = null

    private var loadMoreNotified = false
    private var lastSelectedIds: Set<Long> = emptySet()
    private var lastSelectionActive = false

    /** 列数（默认 4）。 */
    var columns: Int
        get() = gridLayoutManager.spanCount
        set(value) {
            val target = value.coerceAtLeast(1)
            if (target == gridLayoutManager.spanCount) {
                return
            }
            gridLayoutManager.spanCount = target
            gridLayoutManager.spanSizeLookup.invalidateSpanIndexCache()
            gridAdapter.notifyDataSetChanged()
        }

    /**
     * 是否处于多选模式。本质是 [selection] 的 `isActive` 的便捷映射：
     * 置 true = `selection.enter()`，置 false = `selection.exit()`。
     */
    var selectionMode: Boolean
        get() = selection.isActive
        set(value) {
            if (value) selection.enter() else selection.exit()
        }

    init {
        layoutManager = gridLayoutManager
        adapter = gridAdapter
        setHasFixedSize(true)
        clipToPadding = false
        // 选中/取消是局部刷新，关动画避免选择框与角标闪烁
        itemAnimator = null
        addItemDecoration(GridGapDecoration())
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (gridAdapter.isShowingEmpty() && position == 0) {
                    gridLayoutManager.spanCount
                } else {
                    1
                }
        }
        selection.addListener(::onSelectionStateChanged)
        gridAdapter.onCellClick = ::handleCellClick
        gridAdapter.onCellLongClick = ::handleCellLongClick
        addOnScrollListener(
            object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    onScrollChanged?.invoke(currentScrollY())
                    maybeLoadMore()
                }
            },
        )
    }

    /**
     * 全量替换数据（内容变化/刷新/切数据源）。
     *
     * 不会清空 [selection]：多选集合与列表独立、按 id 匹配，跨数据源切换可保留已选。
     */
    fun setItems(items: List<MediaItem>) {
        gridAdapter.submitItems(items)
        loadMoreNotified = false
        gridLayoutManager.spanSizeLookup.invalidateSpanIndexCache()
    }

    /** 追加一页数据（参数只放本页新增条目；分页加载用）。不会影响 [selection]。 */
    fun appendItems(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        gridAdapter.appendItems(items)
        loadMoreNotified = false
    }

    /** 回到顶部（内容变化刷新时宿主调用）。 */
    fun scrollToTop() {
        stopScroll()
        scrollToPosition(0)
    }

    /**
     * 内容内边距（铺满式布局用）：top = 状态栏 + 标题栏，bottom = 胶囊导航/系统导航栏。
     * 内部转成 RecyclerView 的 padding 并保证 `clipToPadding = false`（内容可滚入 padding 区域）。
     */
    fun setContentInsets(top: Int, bottom: Int) {
        val topPx = top.coerceAtLeast(0)
        val bottomPx = bottom.coerceAtLeast(0)
        if (paddingTop == topPx && paddingBottom == bottomPx) {
            return
        }
        setPadding(paddingLeft, topPx, paddingRight, bottomPx)
    }

    /** 空态文案；传 null 关闭空态展示。 */
    fun setEmptyText(text: String?) {
        if (gridAdapter.emptyText == text) {
            return
        }
        gridAdapter.emptyText = text
        gridLayoutManager.spanSizeLookup.invalidateSpanIndexCache()
        gridAdapter.notifyDataSetChanged()
    }

    /** 当前格子的缩略图解码边长（像素），供需要自行加载的场景参考。 */
    fun cellSizePx(): Int = computeCellSize()

    private fun handleCellClick(position: Int) {
        val item = gridAdapter.itemAt(position) ?: return
        if (!selection.isActive) {
            onItemClick?.invoke(item)
            return
        }
        val wasSelected = selection.isSelected(item.id)
        val nowSelected = selection.toggle(item)
        val limit = currentLimit()
        if (!wasSelected && !nowSelected && selection.count >= limit) {
            onSelectionLimitReached?.invoke(limit)
        }
    }

    private fun handleCellLongClick(position: Int) {
        val item = gridAdapter.itemAt(position) ?: return
        onItemLongClick?.invoke(item)
    }

    /** 选择状态变化：进入/退出多选整体刷新；普通勾选只刷新变化的那几格。 */
    private fun onSelectionStateChanged(state: SelectionState) {
        if (state.isActive != lastSelectionActive) {
            lastSelectionActive = state.isActive
            lastSelectedIds = HashSet(state.selectedIds)
            gridAdapter.notifyDataSetChanged()
        } else {
            val changed = HashSet<Long>(lastSelectedIds)
            for (id in state.selectedIds) {
                if (!changed.remove(id)) {
                    // 原集合里没有 → 新选中
                    changed.add(id)
                }
            }
            lastSelectedIds = HashSet(state.selectedIds)
            for (id in changed) {
                val position = gridAdapter.positionOf(id)
                if (position >= 0) {
                    gridAdapter.notifyItemChanged(position)
                }
            }
        }
        onSelectionChanged?.invoke(state)
    }

    private fun currentLimit(): Int = if (selection.maxCount <= 0) Int.MAX_VALUE else selection.maxCount

    /** 当前滚动量（像素）。只有第一项在顶部时才有精确值，滚过首行后给一个大值即可。 */
    private fun currentScrollY(): Int {
        if (gridAdapter.itemCount == 0) {
            return 0
        }
        val first = gridLayoutManager.findFirstVisibleItemPosition()
        if (first == NO_POSITION) {
            return 0
        }
        if (first > 0 || gridAdapter.isShowingEmpty()) {
            return Int.MAX_VALUE
        }
        val firstView = gridLayoutManager.findViewByPosition(first) ?: return 0
        return (paddingTop - firstView.top).coerceAtLeast(0)
    }

    /** 距底部不足 [LOAD_MORE_THRESHOLD_ROWS] 行时请求下一页；每次数据变更只触发一次。 */
    private fun maybeLoadMore() {
        val callback = onLoadMore ?: return
        if (loadMoreNotified) {
            return
        }
        val count = gridAdapter.itemCount
        if (count == 0) {
            return
        }
        val last = gridLayoutManager.findLastVisibleItemPosition()
        if (last == NO_POSITION) {
            return
        }
        if (last >= count - columns * LOAD_MORE_THRESHOLD_ROWS) {
            loadMoreNotified = true
            callback.invoke()
        }
    }

    private fun computeCellSize(): Int {
        val available = width - paddingLeft - paddingRight
        val span = if (available > 0) available / columns else DisplayUtils.screenWidth(context)
        return (span - gapPx).coerceAtLeast(1)
    }

    /**
     * 格子间隔：每格四周各留半个 gap，合并后行/列间距恰为一个 gap；
     * 每格分到的左右/上下偏移一致，因此宽度相同、高度 = 宽度的正方形不会被拉变。
     */
    private inner class GridGapDecoration : ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: State,
        ) {
            if (gridAdapter.isShowingEmpty()) {
                outRect.set(0, 0, 0, 0)
                return
            }
            val half = gapPx / 2
            outRect.set(half, half, gapPx - half, gapPx - half)
        }
    }

    private companion object {
        const val DEFAULT_COLUMNS = 4

        /** 缩略图间隔（dp），与旧 GL 宫格的 gap 保持一致。 */
        const val CELL_GAP_DP = 1.5f

        /** 距底部还剩几行时触发加载下一页。 */
        const val LOAD_MORE_THRESHOLD_ROWS = 2
    }
}
