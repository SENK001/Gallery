package com.senk.gallery.ui.selection

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.senk.gallery.ui.R

/**
 * 多选模式下的底部动作栏（分享 / 收藏 / 添加到 / 删除）。
 *
 * 外观：**悬浮胶囊**（居中、底部留 16dp + 系统导航栏外边距），圆角 = 高度一半；
 * 进入多选时从底部中央的 **64dp 圆**横向展开成 **304dp 胶囊**（宽度动画 + 内容延迟淡入），
 * 退出时反向收缩，符合「菜单从中间由一个圆展开成胶囊」的要求。
 *
 * 只做「按钮容器 + 点击回调 + 动画」，具体动作逻辑在 [SelectionActions] 里 ——
 * 这样照片页（Fragment）与相册详情页（Activity）可以共用同一套按钮与动作实现。
 *
 * 多选态但**未选择任何项**时，宿主调用 [setActionsEnabled] 把四个动作置灰禁用
 * （38% 透明度、点击无响应、不弹 Toast）；选择至少 1 项后恢复。
 *
 * 无障碍：每个动作项的 contentDescription 用可见文字（布局里已设置），可聚焦、可点击，
 * TalkBack 逐个读出动作名称。
 */
class SelectionActionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onShareClick: (() -> Unit)? = null
    var onFavoriteClick: (() -> Unit)? = null
    var onAddToAlbumClick: (() -> Unit)? = null
    var onDeleteClick: (() -> Unit)? = null

    private val shareItem: View
    private val favoriteItem: View
    private val addToItem: View
    private val deleteItem: View

    private var widthAnimator: ValueAnimator? = null
    private var contentAnimator: ValueAnimator? = null
    private var shown = false
    private var bottomInsetPx = 0

    /** 动作项是否可用：多选态但未选任何项时置灰禁用（点击无响应、无 Toast）。 */
    private var actionsEnabled = true

    /** 展开/收起动画驱动的内容透明度（与禁用态的 0.38 叠乘）。 */
    private var contentAlpha = 1f

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_selection_action_bar, this)
        shareItem = findViewById(R.id.selection_action_share)
        favoriteItem = findViewById(R.id.selection_action_favorite)
        addToItem = findViewById(R.id.selection_action_add_to)
        deleteItem = findViewById(R.id.selection_action_delete)
        shareItem.setOnClickListener { onShareClick?.invoke() }
        favoriteItem.setOnClickListener { onFavoriteClick?.invoke() }
        addToItem.setOnClickListener { onAddToAlbumClick?.invoke() }
        deleteItem.setOnClickListener { onDeleteClick?.invoke() }
    }

    /** 系统导航栏 inset：作为胶囊底部外边距的一部分（胶囊不贴边）。 */
    fun setBottomInset(insetPx: Int) {
        if (bottomInsetPx == insetPx) {
            return
        }
        bottomInsetPx = insetPx
        updateBottomMargin()
    }

    /**
     * 设置动作项是否可用。
     *
     * 多选态下「未选择任何项」时由宿主传入 false：四个动作置灰（38% 透明度）、
     * 禁用点击，避免用户点分享/删除却只看到 Toast 或完全无响应。
     */
    fun setActionsEnabled(enabled: Boolean) {
        if (actionsEnabled == enabled) {
            return
        }
        actionsEnabled = enabled
        for (i in 0 until childCount) {
            // 保持 clickable=true：禁用时仍消费触摸，避免点到下面的宫格误选
            getChildAt(i).isEnabled = enabled
        }
        applyChildrenAlpha()
    }

    private fun updateBottomMargin() {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        lp.bottomMargin = bottomInsetPx + dp(BOTTOM_MARGIN_DP)
        layoutParams = lp
    }

    /** 进入多选：从圆展开成胶囊。重复调用幂等。 */
    fun showAnimated() {
        if (shown) {
            return
        }
        shown = true
        cancelAnimators()
        visibility = VISIBLE
        setCapsuleWidth(dp(CIRCLE_DIAMETER_DP))
        setChildrenAlpha(0f)
        // post 一帧：先把这个圆画出来，再开始宽度动画
        post {
            if (!shown) {
                return@post
            }
            animateWidthTo(dp(CAPSULE_WIDTH_DP), EXPAND_DURATION_MS)
            contentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CONTENT_FADE_MS
                startDelay = CONTENT_FADE_DELAY_MS
                addUpdateListener { setChildrenAlpha(it.animatedValue as Float) }
                start()
            }
        }
    }

    /** 退出多选：胶囊收缩回圆后隐藏；若本来就没显示则立即回调 [onEnd]。 */
    fun hideAnimated(onEnd: (() -> Unit)? = null) {
        if (!shown) {
            onEnd?.invoke()
            return
        }
        shown = false
        cancelAnimators()
        val startWidth = width.takeIf { it > 0 } ?: dp(CAPSULE_WIDTH_DP)
        animateWidthTo(dp(CIRCLE_DIAMETER_DP), COLLAPSE_DURATION_MS, startWidth)
        contentAnimator = ValueAnimator.ofFloat(childrenAlphaMax(), 0f).apply {
            duration = CONTENT_FADE_MS
            addUpdateListener { setChildrenAlpha(it.animatedValue as Float) }
            start()
        }
        postDelayed({
            // 收起期间若又进入多选（shown 重新为 true），保留胶囊并跳过结束回调
            if (!shown) {
                visibility = GONE
                resetToWrapContent()
                onEnd?.invoke()
            }
        }, COLLAPSE_DURATION_MS + 40L)
    }

    private fun animateWidthTo(
        targetPx: Int,
        durationMs: Long,
        startPx: Int = width.coerceAtLeast(dp(CIRCLE_DIAMETER_DP)),
    ) {
        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofInt(startPx, targetPx).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation -> setCapsuleWidth(animation.animatedValue as Int) }
            start()
        }
    }

    private fun setCapsuleWidth(widthPx: Int) {
        val lp = layoutParams ?: return
        if (lp.width == widthPx) {
            return
        }
        lp.width = widthPx
        layoutParams = lp
    }

    /** 隐藏后恢复 wrap_content，保证下一次测量/无障碍边界正确。 */
    private fun resetToWrapContent() {
        val lp = layoutParams ?: return
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams = lp
        setChildrenAlpha(1f)
    }

    private fun setChildrenAlpha(alpha: Float) {
        contentAlpha = alpha.coerceIn(0f, 1f)
        applyChildrenAlpha()
    }

    /** 动画透明度 × 禁用态透明度：展开/收起过程中保持「未选择时置灰」的观感。 */
    private fun applyChildrenAlpha() {
        val alpha = contentAlpha * (if (actionsEnabled) 1f else DISABLED_ALPHA)
        for (i in 0 until childCount) {
            getChildAt(i).alpha = alpha.coerceIn(0f, 1f)
        }
    }

    private fun childrenAlphaMax(): Float =
        if (childCount == 0) 1f else (0 until childCount).maxOf { getChildAt(it).alpha }

    private fun cancelAnimators() {
        widthAnimator?.cancel()
        widthAnimator = null
        contentAnimator?.cancel()
        contentAnimator = null
    }

    override fun onDetachedFromWindow() {
        cancelAnimators()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 胶囊最终宽度：左右 8dp 内边距 + 4 个 72dp 动作项。 */
        const val CAPSULE_WIDTH_DP = 304

        /** 收起时圆形的直径（= 胶囊高度）。 */
        const val CIRCLE_DIAMETER_DP = 64

        /** 胶囊底部与系统导航栏之间的间距。 */
        const val BOTTOM_MARGIN_DP = 16

        /** 未选择任何项时动作项的透明度（Material 禁用态常用 38%）。 */
        const val DISABLED_ALPHA = 0.38f

        const val EXPAND_DURATION_MS = 260L
        const val COLLAPSE_DURATION_MS = 160L
        const val CONTENT_FADE_MS = 160L
        const val CONTENT_FADE_DELAY_MS = 80L
    }
}
