package com.senk.gallery.ui.transition

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import com.senk.gallery.ui.R

/**
 * 宫格 → 大图 的「缩略图展开 / 收回到缩略图」转场数据（同进程静态传递）。
 *
 * 为什么不用 SharedElementTransition：查看器用 [com.senk.gallery.ui.gl.GlImageViewer]（独立 Surface 的
 * GLSurfaceView）绘制，系统共享元素动画无法直接作用于 SurfaceView。因此采用**覆盖层 ImageView**：
 * 打开时用被点击格子的缩略图 Bitmap 从格子屏幕矩形放大到查看器图片区域，结束再切到 GL 画面；
 * 退出时截取当前 GL 画面，按原矩形缩小回缩略图位置后 finish。
 *
 * 使用方：
 * - 宫格宿主在启动 `ViewerActivity` 前调用 [remember]（传入被点击格子的根 View）；
 * - `ViewerActivity` 用 [takePending] 取一次（避免旋转后重复播放进入动画），并在退出时用 [peek] 取回目标矩形。
 */
object ViewerTransition {

    /** 一次缩略图转场：媒体 id + 缩略图在屏幕上的矩形 + 缩略图 Bitmap（可能为空，查看器可自行加载）。 */
    class Thumb(
        val itemId: Long,
        val rect: Rect,
        val bitmap: Bitmap?,
    )

    @Volatile
    private var pending: Thumb? = null

    /** 宫格宿主在被点击格子尚未离开屏幕时记录转场信息。 */
    fun remember(itemId: Long, cellView: View) {
        val thumbView = cellView.findViewById<ImageView>(R.id.media_cell_thumb) ?: cellView
        if (thumbView.width <= 0 || thumbView.height <= 0 || !thumbView.isShown) {
            pending = null
            return
        }
        val location = IntArray(2)
        thumbView.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + thumbView.width,
            location[1] + thumbView.height,
        )
        val bitmap = (thumbView as? ImageView)?.drawable
            ?.let { it as? BitmapDrawable }?.bitmap
            ?.takeUnless { it.isRecycled }
            ?.copy(Bitmap.Config.ARGB_8888, false)
        pending = Thumb(itemId, rect, bitmap)
    }

    /** 查看器启动时取走一次（进入动画只播一次，旋转不重播）。 */
    fun takePending(): Thumb? {
        val value = pending
        pending = null
        return value
    }

    /** 查看器退出时读取目标矩形（[takePending] 之后仍保留副本，由查看器自己持有）。 */
    fun peek(): Thumb? = pending

    /** 宿主取消/异常路径清掉待用转场。 */
    fun clear() {
        pending = null
    }
}
