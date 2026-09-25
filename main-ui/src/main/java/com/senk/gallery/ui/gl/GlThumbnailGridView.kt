package com.senk.gallery.ui.gl

import android.content.Context
import android.graphics.Color
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

    init {
        setEGLContextClientVersion(2)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setBackgroundColor(Color.WHITE)
        // 与屏幕边缘无间距，仅缩略图之间保留间隔
        renderer.geometry.padding = 0f
        renderer.geometry.gap = DisplayUtils.dp2px(context, 1.5f)
        renderer.onRequestThumbnail = { item, sizePx ->
            loader?.request(item, sizePx)
        }
        renderer.onNearEnd = {
            post { onLoadMore?.invoke() }
        }
    }

    fun setColumns(columns: Int) {
        renderer.geometry.columns = columns.coerceAtLeast(1)
        requestRender()
    }

    fun setBottomInset(insetPx: Int) {
        renderer.geometry.bottomInset = insetPx.coerceAtLeast(0).toFloat()
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
