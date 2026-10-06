package com.senk.gallery.ui.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.ui.R
import com.senk.gallery.util.DisplayUtils
import kotlin.math.abs
import kotlin.math.sign

class GlThumbnailGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer =
        GlGridRenderer(context.resources.displayMetrics.density).apply {
            emptyText = context.getString(R.string.gallery_empty)
        }
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var flinging = false
    private var loader: ThumbnailLoader? = null

    var onItemClick: ((MediaItem) -> Unit)? = null
    var onLoadMore: (() -> Unit)? = null

    /**
     * 滚动回调（**已切到主线程**）。宿主用它驱动标题栏渐变透明度。
     * 参数为当前 scrollY（0 = 已到顶部）。
     */
    var onScrollChanged: ((Float) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        // 默认 z-order：GL 面位于窗口后方，窗口层控件（如底部悬浮胶囊导航）可覆盖其上；
        // 底色由渲染器 glClearColor 绘制（给 GLSurfaceView 设 View 背景会盖住 surface），
        // 默认白色，宿主可用 setSurfaceBackgroundColor 按主题改成深色
        // 与屏幕边缘无间距，仅缩略图之间保留间隔
        renderer.geometry.padding = 0f
        renderer.geometry.gap = DisplayUtils.dp2px(context, 1.5f)
        renderer.onRequestThumbnail = { item, sizePx ->
            loader?.request(item, sizePx)
        }
        renderer.onNearEnd = {
            post { onLoadMore?.invoke() }
        }
        // 渲染回调在 GL 线程，必须切到主线程再交给宿主改 View
        renderer.onScrollChanged = { scrollY ->
            post { onScrollChanged?.invoke(scrollY) }
        }
    }

    fun setColumns(columns: Int) {
        renderer.geometry.columns = columns.coerceAtLeast(1)
        requestRender()
    }

    /**
     * 设置宫格的清屏底色（`glClearColor`）。
     *
     * 宫格铺满全屏后，标题栏「透出」的其实是**本 surface 的底色**（透明标题栏之下就是它），
     * 因此深色模式下必须一起变深，否则标题栏区域会突兀地保持白色。
     * 宿主应传入按主题解析后的颜色（如 `R.color.gallery_chrome_surface`）。
     */
    fun setSurfaceBackgroundColor(color: Int) {
        renderer.setClearColor(color)
        requestRender()
    }

    /**
     * 顶部预留：宫格铺满全屏（含状态栏区域）后，第一行会被标题栏永久遮住，
     * 因此内容整体下移这么多，让首行完整可见。见 [GridGeometry.topInset]。
     */
    fun setTopInset(insetPx: Int) {
        renderer.geometry.topInset = insetPx.coerceAtLeast(0).toFloat()
        clampScroll()
        requestRender()
    }

    fun setBottomInset(insetPx: Int) {
        renderer.geometry.bottomInset = insetPx.coerceAtLeast(0).toFloat()
        clampScroll()
        requestRender()
    }

    /**
     * 滚动到底时，**最后一行底边与屏幕底边之间保留的空白高度**。
     * 用于让最后一行完整停在系统导航栏 / 悬浮导航上方（见 [GridGeometry.bottomPadding]）。
     */
    fun setBottomPadding(paddingPx: Int) {
        renderer.geometry.bottomPadding = paddingPx.coerceAtLeast(0).toFloat()
        clampScroll()
        requestRender()
    }

    fun submitItems(items: List<MediaItem>) {
        renderer.items = items
        renderer.geometry.itemCount = items.size
        renderer.resetNearEnd()
        clampScroll()
        requestRender()
    }

    fun appendItems(items: List<MediaItem>) {
        submitItems(items)
    }

    fun scrollToTop() {
        renderer.scrollY = 0f
        requestRender()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loader = ThumbnailLoader(context, renderer.textureStore) { requestRender() }
    }

    override fun onDetachedFromWindow() {
        loader?.shutdown()
        loader = null
        recycleTracker()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.geometry.viewportWidth = w
        renderer.geometry.viewportHeight = h
        clampScroll()
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!dragging &&
                    (abs(event.y - downY) > touchSlop || abs(event.x - downX) > touchSlop)
                ) {
                    dragging = true
                    // 起手时吃掉 touchSlop，避免第一次滚动跳变，保证跟手
                    if (abs(event.y - downY) > touchSlop) {
                        lastY = downY + if (event.y >= downY) touchSlop else -touchSlop
                    }
                    if (abs(event.x - downX) > touchSlop) {
                        lastX = downX + if (event.x >= downX) touchSlop else -touchSlop
                    }
                }
                if (dragging) {
                    val dy = lastY - event.y
                    lastY = event.y
                    lastX = event.x
                    if (dy != 0f) {
                        renderer.scrollY = (renderer.scrollY + dy)
                            .coerceIn(0f, renderer.geometry.maxScroll)
                        requestRender()
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                if (!dragging) {
                    val index = renderer.geometry.indexAt(event.x, event.y, renderer.scrollY)
                    val item = renderer.items.getOrNull(index)
                    if (item != null) {
                        performClick()
                        onItemClick?.invoke(item)
                    }
                } else {
                    startFling()
                }
                recycleTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                startFling()
                recycleTracker()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun clampScroll() {
        renderer.scrollY = renderer.scrollY
            .coerceIn(0f, renderer.geometry.maxScroll)
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun startFling() {
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000)
        val velocityY = tracker.yVelocity
        if (abs(velocityY) < minimumVelocity) {
            return
        }
        val maxScroll = renderer.geometry.maxScroll.toInt()
        // VelocityTracker 返回的是手指速度（向下为正），OverScroller 需要滚动速度，方向相反需取反
        scroller.fling(0, renderer.scrollY.toInt(), 0, -velocityY.toInt(), 0, 0, 0, maxScroll)
        flinging = true
        postOnAnimation(flingRunnable)
    }

    private fun stopFling() {
        if (flinging) {
            flinging = false
            scroller.forceFinished(true)
            removeCallbacks(flingRunnable)
        }
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                renderer.scrollY = scroller.currY.toFloat()
                requestRender()
                postOnAnimation(this)
            } else {
                flinging = false
            }
        }
    }
}
