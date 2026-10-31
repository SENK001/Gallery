package com.senk.gallery.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.exp

/**
 * 带「越界回弹」的 RecyclerView：滚动到顶部后继续向下拖、或到底后继续向上拖时，
 * 内容按阻尼跟手位移；松手后用带回弹的动画归位。
 *
 * 实现要点：
 * - 关闭系统 overscroll（glow / stretch），否则会与自定义位移叠加；
 * - 只在**边界**且位移方向继续向外时进入回弹，普通滚动/点击完全不受影响；
 * - 位移上限 = RecyclerView 自身高度（一屏），采用指数阻尼：越拉阻力越大，
 *   位移永远不超过一屏；
 * - 中途按下手指会停止归位动画、从当前累计位移继续拖；
 * - 只位移 RecyclerView 自身（`translationY`），因此不会改变真实滚动位置，
 *   松手回弹后布局/滚动状态与普通 RecyclerView 一致。
 *
 * 使用方：照片/相册详情/选图/最近删除宫格（[com.senk.gallery.ui.grid.MediaGridView] 继承本类），
 * 相册页、添加到相册页、选图页相册列表直接使用本类。
 */
open class BounceRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var animator: ValueAnimator? = null
    private var lastY = 0f
    private var edgeAccum = 0f
    private var pulling = false

    /** 已经累计的向外手指位移（像素，≥0；反向拖会减少）。 */
    private var rawPull = 0f

    /** 本次回弹方向：+1 = 顶部下拉，-1 = 底部上拉。 */
    private var pullDirection = 1

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetTouch(e.rawY)

            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    return true
                }
                if (tryStartPull(e)) {
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeAccum = 0f
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!pulling) {
                    resetTouch(e.rawY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    val delta = e.rawY - lastY
                    lastY = e.rawY
                    dragBy(delta)
                    return true
                }
                // 某些场景（子 View 未消费 DOWN）事件只走 onTouchEvent，这里也要能开始回弹
                if (tryStartPull(e)) {
                    return true
                }
                return super.onTouchEvent(e)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    release()
                    return true
                }
                if (abs(translationY) > 0.5f) {
                    release()
                    return true
                }
                edgeAccum = 0f
                return super.onTouchEvent(e)
            }
        }
        return super.onTouchEvent(e)
    }

    /** 更新 lastY 并判断是否越过边界向外拖到可以开始回弹。 */
    private fun tryStartPull(e: MotionEvent): Boolean {
        if (!hasContent()) {
            lastY = e.rawY
            edgeAccum = 0f
            return false
        }
        val delta = e.rawY - lastY
        lastY = e.rawY
        // 只在已经到边界、且继续向外拖时累计越界距离
        if (delta > 0f && !canScrollVertically(-1)) {
            edgeAccum += delta
        } else if (delta < 0f && !canScrollVertically(1)) {
            edgeAccum += delta
        } else {
            edgeAccum = 0f
        }
        if (abs(edgeAccum) > touchSlop) {
            startPull(edgeAccum)
            return true
        }
        return false
    }

    private fun resetTouch(y: Float) {
        stopAnimator()
        lastY = y
        edgeAccum = 0f
        pulling = false
    }

    private fun startPull(accumulated: Float) {
        pulling = true
        parent?.requestDisallowInterceptTouchEvent(true)
        pullDirection = if (accumulated >= 0f) 1 else -1
        // 扣掉触发阈值那一段，避免越过 slop 时突然跳一下
        rawPull = (abs(accumulated) - touchSlop).coerceAtLeast(0f)
        edgeAccum = 0f
        applyPull()
    }

    private fun dragBy(delta: Float) {
        rawPull = (rawPull + delta * pullDirection).coerceAtLeast(0f)
        applyPull()
    }

    /**
     * 把累计手指位移映射为内容位移：指数阻尼，位移上限 = 一屏高度。
     *
     * `offset = max * (1 - e^(-rate * raw / max))`：越拉斜率越小（阻力越大），
     * 且永远不超过 `max`（RecyclerView 自身高度，即一屏）。
     */
    private fun applyPull() {
        val max = maxPullPx()
        if (max <= 0f) {
            translationY = 0f
            return
        }
        val ratio = 1f - exp(-PULL_RATE * rawPull / max)
        translationY = pullDirection * max * ratio.coerceIn(0f, 1f)
    }

    private fun maxPullPx(): Float = (
        height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
    ).toFloat()

    private fun release() {
        pulling = false
        edgeAccum = 0f
        val start = translationY
        if (abs(start) < 0.5f) {
            translationY = 0f
            return
        }
        val distanceRatio = (abs(start) / maxPullPx()).coerceIn(0f, 1f)
        animator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = (RELEASE_MIN_DURATION_MS +
                (RELEASE_EXTRA_DURATION_MS * distanceRatio).toLong())
            interpolator = OvershootInterpolator(1.35f)
            addUpdateListener { animation ->
                translationY = animation.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (!pulling) {
                        translationY = 0f
                    }
                }
            })
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    private fun hasContent(): Boolean = (adapter?.itemCount ?: 0) > 0

    private companion object {
        /** 指数阻尼系数：越大越跟手、同时越快接近一屏上限。 */
        const val PULL_RATE = 1.1f

        const val RELEASE_MIN_DURATION_MS = 220L
        const val RELEASE_EXTRA_DURATION_MS = 140L
    }
}
