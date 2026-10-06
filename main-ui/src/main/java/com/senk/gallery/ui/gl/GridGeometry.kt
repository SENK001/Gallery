package com.senk.gallery.ui.gl

import android.graphics.RectF

class GridGeometry {

    var columns = 4
    var padding = 0f
    var gap = 0f

    /**
     * 顶部内边距：宫格铺满全屏后，第一行会落到状态栏/标题栏底下被永久遮住，
     * 因此内容整体下移这么多，让首行完整可见。
     *
     * 与 [bottomInset] 的区别（很重要）：顶部预留是**内容偏移**，不参与滚动——
     * 缩略图从 topInset 处开始往下排，向上滚动到顶时第一行正好停在 topInset，
     * 不会跑到状态栏里；而 [bottomInset] 参与滚动（只是把内容总高撑长）。
     */
    var topInset = 0f

    /**
     * 底部额外余量：把**内容总高**撑长（`contentHeight` 含它）。
     *
     * 若目的是「滚到底时最后一行与屏幕底边之间留出空白」，**用 [bottomPadding]，不要用这个**：
     * `bottomInset` 只是把内容加长，而滚动范围还受 `maxScroll` 控制，余量不足一行时
     * 最后一行仍会贴着屏幕底边（真机实测踩过）。
     */
    var bottomInset = 0f

    /**
     * 滚动到底时，**最后一行底边与屏幕底边之间要保留的空白高度**（由 [maxScroll] 保证，
     * 与行高、余量取整无关）。
     *
     * 用途：底部被系统导航栏 / 悬浮导航遮挡时，设成它们的高度，最后一行即可完整停在它们上方。
     */
    var bottomPadding = 0f
    var viewportWidth = 0
    var viewportHeight = 0
    var itemCount = 0

    val cellSize: Float
        get() {
            if (viewportWidth <= 0 || columns <= 0) {
                return 0f
            }
            val available = viewportWidth - padding * 2 - gap * (columns - 1)
            return (available / columns).coerceAtLeast(1f)
        }

    val rowCount: Int
        get() = if (itemCount <= 0) 0 else (itemCount + columns - 1) / columns

    /**
     * 所有行的实际占据高度（**不含 [topInset]**，因为它是对内容的整体平移、不参与滚动）。
     * 即「从第一行顶边到最后一行底边」的距离。
     */
    val rowsHeight: Float
        get() {
            if (rowCount <= 0) {
                return 0f
            }
            return padding * 2 + rowCount * cellSize + (rowCount - 1) * gap + bottomInset
        }

    /** 完整内容高度（含 [topInset]），供滚动范围与「是否需要滚动」判断使用。 */
    val contentHeight: Float
        get() = if (rowCount <= 0) 0f else topInset + rowsHeight

    /**
     * 可滚动范围。
     *
     * 目标是「滚到底时最后一行底边 = 屏高 − [bottomPadding]」。
     * 最后一行底边（内容坐标）= `topInset + rowsHeight`（见 [cellY]），
     * 减去 maxScroll 后应等于目标位置：
     *   topInset + rowsHeight - maxScroll = viewportHeight - bottomPadding
     * 即 `maxScroll = rowsHeight + topInset - viewportHeight + bottomPadding`。
     *
     * ⚠️ `topInset` 必须显式加进来：`rowsHeight` 不含它（见该属性的说明），
     * 而 [cellY] 含它。漏掉会让最后一行多溢出整整一个 `topInset`（真机实测踩过）。
     */
    val maxScroll: Float
        get() = (rowsHeight + topInset - viewportHeight + bottomPadding).coerceAtLeast(0f)

    fun cellX(column: Int): Float = padding + column * (cellSize + gap)

    fun cellY(row: Int): Float = topInset + padding + row * (cellSize + gap)

    fun cellRect(index: Int, scrollY: Float, out: RectF) {
        if (index < 0 || index >= itemCount) {
            out.setEmpty()
            return
        }
        val row = index / columns
        val column = index % columns
        val size = cellSize
        val left = cellX(column)
        val top = cellY(row) - scrollY
        out.set(left, top, left + size, top + size)
    }

    fun indexAt(x: Float, y: Float, scrollY: Float): Int {
        if (itemCount <= 0) {
            return -1
        }
        val size = cellSize
        if (size <= 0f) {
            return -1
        }
        val localX = x - padding
        val localY = y + scrollY - topInset - padding
        if (localX < 0f || localY < 0f) {
            return -1
        }
        val column = (localX / (size + gap)).toInt()
        val row = (localY / (size + gap)).toInt()
        if (column < 0 || column >= columns || row < 0) {
            return -1
        }
        if (localX - column * (size + gap) > size || localY - row * (size + gap) > size) {
            return -1
        }
        val index = row * columns + column
        return if (index in 0 until itemCount) index else -1
    }
}
