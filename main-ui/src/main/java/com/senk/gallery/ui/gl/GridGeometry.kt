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
    var bottomInset = 0f
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

    val contentHeight: Float
        get() {
            if (rowCount <= 0) {
                return 0f
            }
            return topInset + padding * 2 + rowCount * cellSize + (rowCount - 1) * gap + bottomInset
        }

    /** 可滚动范围要扣掉顶部预留：这段是固定偏移，不属于可滚动内容。 */
    val maxScroll: Float
        get() = (contentHeight - topInset - viewportHeight).coerceAtLeast(0f)

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
