package com.senk.gallery.ui.grid

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.ui.selection.SelectionState
import com.senk.gallery.util.DisplayUtils
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 宫格控件（RecyclerView 子类）—— 照片页、相册详情页、选图页共用的多选宫格。
 *
 * 设计要点：
 * - **一格一个 View**：替换旧的 GL 自绘宫格，天然支持无障碍（TalkBack 能读到每格节点）；
 * - 触摸/滚动/fling 全交给 RecyclerView，行为与系统一致；
 * - 多选状态由 [SelectionState] 持有，本类只负责：
 *   点击语义（普通点击 = onItemClick；多选态 = 内部 toggle）、局部刷新格子、把外部回调转发给宿主；
 * - **滑动选择（刷选）**：仅多选态生效。首个移动方向用于区分手势——
 *   横向拖动 = 刷选（手指划过的格子按起点状态批量选中/取消，可跨行、可在上下边缘自动滚动），
 *   纵向拖动 = 正常滚动列表；手势在一开始锁定，避免边滚边误选；
 * - 分页：接近底部时回调 [onLoadMore]，宿主用 [MediaPager] 拉下一页后 [appendItems]；
 * - 铺满式布局：用 [setContentInsets] 设 padding 并 `clipToPadding = false`，
 *   内容可滚到状态栏/标题栏下面（标题栏渐变由宿主按 [onScrollChanged] 驱动）。
 *
 * 公开 API 即跨模块契约，改动前先与选图页/相册页对齐。
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

    // ==================== 滑动选择（刷选）状态 ====================

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var paintStartPosition = NO_POSITION
    private var gestureMode = GestureMode.UNDECIDED

    /** 刷选模式：true = 本次手势批量选中；false = 批量取消（由起点格子的选中态决定）。 */
    private var paintSelecting = true
    private var lastPaintX = 0f
    private var lastPaintY = 0f

    /** 本次刷选是否已经提示过「已达最大选择数」（避免一次拖动弹多次 Toast）。 */
    private var limitNotifiedInPaint = false

    private enum class GestureMode { UNDECIDED, SCROLL, PAINT }

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

    // ==================== 触摸：区分「滚动」与「刷选」 ====================

    /**
     * 多选态下的手势判定：
     * - 首个超过 touchSlop 的位移若**横向占优** → 锁定为刷选，并拦截后续事件自己处理；
     * - 若纵向占优 → 交给 RecyclerView 正常滚动（本次手势不再可能进入刷选）；
     * - 非多选态完全走父类逻辑。
     */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!selection.isActive) {
            resetPaintGesture()
            return super.onInterceptTouchEvent(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetPaintGesture()
                downX = e.x
                downY = e.y
                paintStartPosition = findAdapterPositionAt(e.x, e.y)
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureMode == GestureMode.PAINT) {
                    return true
                }
                if (gestureMode == GestureMode.UNDECIDED) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (abs(dx) > abs(dy) && beginPaint()) {
                            gestureMode = GestureMode.PAINT
                            lastPaintX = e.x
                            lastPaintY = e.y
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                        // 纵向为主（或起点不是有效格子）：本次手势按滚动处理
                        gestureMode = GestureMode.SCROLL
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetPaintGesture()
        }
        return super.onInterceptTouchEvent(e)
    }

    /**
     * 刷选开始后事件被本控件拦截，后续 MOVE 在这里沿手指轨迹逐格应用选择；
     * 手指靠近上下边缘时自动滚动，方便连续刷选长列表。
     */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!selection.isActive || gestureMode != GestureMode.PAINT) {
            return super.onTouchEvent(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                paintAlong(lastPaintX, lastPaintY, e.x, e.y)
                lastPaintX = e.x
                lastPaintY = e.y
                autoScrollWhilePainting(e.y)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 刷选中出现多指：结束刷选，避免第二根手指继续改选中集合
                resetPaintGesture()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetPaintGesture()
                return true
            }
        }
        return true
    }

    /** 开始刷选：由起点格子当前是否选中决定整段手势是「批量选中」还是「批量取消」。 */
    private fun beginPaint(): Boolean {
        val position = paintStartPosition
        if (position == NO_POSITION) {
            return false
        }
        val item = gridAdapter.itemAt(position) ?: return false
        paintSelecting = !selection.isSelected(item.id)
        paintAt(position)
        // 起点是「唯一已选项」且本次为取消刷选时，取消第一格后宿主可能会自动退出多选：
        // 此时不再进入刷选，交给父类按普通触摸处理。
        if (!selection.isActive) {
            resetPaintGesture()
            return false
        }
        return true
    }

    /**
     * 沿两点之间插值逐点命中格子：快速滑动时 MOVE 事件稀疏，直接取终点会漏格，
     * 因此按半格步长采样，保证划过的每一格都被处理。
     */
    private fun paintAlong(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val distance = hypot((toX - fromX).toDouble(), (toY - fromY).toDouble())
        val step = (cellSizePx() / 2f).coerceAtLeast(1f)
        val steps = (distance / step).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = fromX + (toX - fromX) * t
            val y = fromY + (toY - fromY) * t
            val position = findAdapterPositionAt(x, y)
            if (position != NO_POSITION) {
                paintAt(position)
            }
        }
    }

    private fun paintAt(position: Int) {
        val item = gridAdapter.itemAt(position) ?: return
        if (paintSelecting) {
            val wasSelected = selection.isSelected(item.id)
            val nowSelected = selection.select(item)
            if (!wasSelected && !nowSelected && !limitNotifiedInPaint &&
                selection.count >= currentLimit()
            ) {
                limitNotifiedInPaint = true
                onSelectionLimitReached?.invoke(currentLimit())
            }
        } else {
            selection.deselect(item.id)
        }
    }

    /** 刷选时手指接近上下边缘 → 自动滚动，便于连续选择长列表。 */
    private fun autoScrollWhilePainting(y: Float) {
        val edge = autoScrollEdgePx
        when {
            y < paddingTop + edge -> scrollBy(0, -AUTO_SCROLL_STEP_PX)
            y > height - paddingBottom - edge -> scrollBy(0, AUTO_SCROLL_STEP_PX)
        }
    }

    private fun findAdapterPositionAt(x: Float, y: Float): Int {
        val child = findChildViewUnder(x, y) ?: return NO_POSITION
        val position = getChildAdapterPosition(child)
        if (position == NO_POSITION) {
            return NO_POSITION
        }
        // 空态占位行也占一个 adapter position，用 itemAt 过滤掉
        return if (gridAdapter.itemAt(position) != null) position else NO_POSITION
    }

    private fun resetPaintGesture() {
        gestureMode = GestureMode.UNDECIDED
        paintStartPosition = NO_POSITION
        paintSelecting = true
        limitNotifiedInPaint = false
    }

    // ==================== 数据 ====================

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
        if (!state.isActive) {
            resetPaintGesture()
        }
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

    /**
     * 当前滚动量（像素）。
     *
     * 语义与 [onScrollChanged] 一致：只有第一项在顶部时才有精确值；已滚过首行（或处于空态）
     * 时返回一个大值（`Int.MAX_VALUE`）。宿主可在「没有滚动事件但需要恢复标题栏三态」时
     * 主动调用（如 ViewPager2 切页返回后，宫格保留着滚动位置，但不会触发 onScrolled）。
     */
    fun currentScrollY(): Int {
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

    /** 刷选自动滚动：手指进入上下边缘多少像素内开始滚。 */
    private val autoScrollEdgePx: Int
        get() = DisplayUtils.dp2px(context, AUTO_SCROLL_EDGE_DP).toInt()

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

        /** 刷选时手指距上下边缘多少 dp 内开始自动滚动。 */
        const val AUTO_SCROLL_EDGE_DP = 48f

        /** 刷选自动滚动每次 MOVE 的步长（像素）。 */
        const val AUTO_SCROLL_STEP_PX = 24
    }
}
