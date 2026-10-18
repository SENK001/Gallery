package com.senk.gallery.ui.grid

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 宫格单元格容器：在测量阶段强制为**正方形**（高度 = 实测宽度）。
 *
 * 为什么需要它：宫格用 `GridLayoutManager`，列宽由 span 决定、高度必须自己算。
 * 若在绑定或布局回调里回写 `layoutParams.height`，会引入一次额外的 requestLayout，
 * 快速滚动时容易抖动；让容器自己在 `onMeasure` 里定高则完全跟随列宽，
 * 旋转/列数变化/间隔调整都自动生效。
 */
class GridCellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度的实测值就是单元格边长；用它当高度约束（EXACTLY），子 View 的 match_parent 才有意义
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
