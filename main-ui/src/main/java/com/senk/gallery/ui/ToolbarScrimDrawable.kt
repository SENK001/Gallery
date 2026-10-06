package com.senk.gallery.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 宫格页上划后标题栏用的黑色线性渐变遮罩（**单段：顶部最深 → 底部全透明**）。
 *
 * 覆盖范围是「状态栏 + 标题栏」，由调用方把 View 高度设为
 * `状态栏高度 + actionBarSize`、宽度铺满。
 *
 * 不透明度：顶部约 55% 黑，向下线性渐隐到全透明。
 * 取 55% 是因为更深的起点（如 80%）会显得是一条生硬的黑带（实测反馈"太生硬"）。
 *
 * 用代码画而不是资源 `shape`：高度依赖设备的状态栏高度，写死会在别的机型错位。
 *
 * **改动本类（或其它含渐变/遮罩的观感）时的验证约定** —— 先把渐变临时屏蔽再验证：
 * 把 [START_ALPHA] 临时改成 `0x00`（或让调用方先不设 background）重建，截图应能
 * **直接看到未压暗的缩略图**。这样能把「底层布局」与「观感」分开判断：
 * - 屏蔽后顶部仍是纯白 → 是布局/边距问题（查 `GridGeometry.topInset`）
 * - 屏蔽后能看到缩略图 → 布局正确，剩下的只是渐变观感问题（调 alpha / 渐隐区间）
 *
 * 不屏蔽时的辅助判据：同一高度取**多个 x**、看取值是否**互不相同**——
 * 互不相同说明有图像内容透出；只取单点或完全一致（如清一色 `(116,116,116)`）
 * 不足以判断是"被压平"还是"真的空白"（此处曾误判过一次，见 docs/development-plan.md §10）。
 *
 * 首页（`MainActivity`）与相册详情页（`AlbumDetailActivity`）共用。
 */
class ToolbarScrimDrawable(totalHeightPx: Int) : Drawable() {

    private val paint = Paint().apply {
        shader = LinearGradient(
            0f,
            0f,
            0f,
            totalHeightPx.coerceAtLeast(1).toFloat(),
            Color.argb(START_ALPHA, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (!b.isEmpty) {
            canvas.drawRect(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                paint,
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** 顶部（最深）的不透明度：约 55% 黑；再高就显得生硬。 */
        const val START_ALPHA = 0x8C
    }
}
