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

/**
 * 带「越界回弹」的 RecyclerView：滚动到顶部后继续向下拖、或到底后继续向上拖时，
 * 内容按阻尼跟手位移；松手后用带回弹的动画归位。
 *
 * 实现要点：
 * - 关闭系统 overscroll（glow / stretch），否则会与自定义位移叠加；
 * - 只在**边界**且位移方向继续向外时进入回弹，普通滚动/点击完全不受影响；
 * - 中途按下手指会停止归位动画并从当前位置继续拖；
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
    private val maxPullPx = dp(88f)
    private var animator: ValueAnimator? = null
    private var lastY = 0f
    private var edgeAccum = 0f
    private var pulling = false

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetTouch(e.y)

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
                    resetTouch(e.y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    val delta = e.y - lastY
                    lastY = e.y
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
            lastY = e.y
            edgeAccum = 0f
            return false
        }
        val delta = e.y - lastY
        lastY = e.y
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
        // 扣掉触发阈值那一段，避免越过 slop 时突然跳一下
        val trigger = if (accumulated > 0f) touchSlop else -touchSlop
        edgeAccum = 0f
        dragBy(accumulated - trigger)
    }

    private fun dragBy(delta: Float) {
        val next = (translationY + delta * PULL_RESISTANCE)
            .coerceIn(-maxPullPx, maxPullPx)
        translationY = next
    }

    private fun release() {
        pulling = false
        edgeAccum = 0f
        val start = translationY
        if (abs(start) < 0.5f) {
            translationY = 0f
            return
        }
        val distanceRatio = (abs(start) / maxPullPx).coerceIn(0f, 1f)
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** 手指位移映射到内容位移的阻尼系数（越小越「拉不动」）。 */
        const val PULL_RESISTANCE = 0.42f

        const val RELEASE_MIN_DURATION_MS = 220L
        const val RELEASE_EXTRA_DURATION_MS = 140L
    }
}
