package com.senk.gallery.ui.gl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Size
import android.view.GestureDetector
import android.view.SurfaceHolder
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.ui.theme.ThemeUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

class GlImageViewer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val videoManager = VideoPlayManager(context)
    private val renderer = GlImagePagerRenderer(videoManager).apply {
        // 颜色一律来自主题色板，不在这里写死字面量
        setMediaColors(
            clearLight = ThemeUtils.surfaceContent(context),
            clearDark = ThemeUtils.mediaScrim(context),
            placeholderLight = ThemeUtils.mediaPlaceholderLight(context),
            placeholderDark = ThemeUtils.mediaPlaceholderDark(context),
        )
    }
    private val executor = Executors.newFixedThreadPool(3)
    private val inFlight = ConcurrentHashMap<Long, Boolean>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var zooming = false
    private var sheetDragging = false
    private var animator: ValueAnimator? = null

    var onPageChanged: ((Int, MediaItem) -> Unit)? = null
    var onSingleTap: ((Float, Float) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onPressEnd: (() -> Unit)? = null
    var onSheetDragStart: (() -> Unit)? = null
    var onSheetDrag: ((Float) -> Unit)? = null
    var onSheetDragEnd: ((Float) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setZOrderOnTop(false)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.onRequestImage = { item, width, height -> loadImage(item, width, height) }
        renderer.onRequestRender = { requestRender() }
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongPress?.invoke()
            }
        })
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(renderer.scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })
    }

    fun setLightBackground(light: Boolean) {
        renderer.lightBackground = light
        requestRender()
    }

    fun setItems(items: List<MediaItem>, position: Int) {
        renderer.items = items
        renderer.currentIndex = position.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        renderer.offsetPx = 0f
        dragging = false
        sheetDragging = false
        resetZoom()
        requestRender()
        notifyPageChanged()
    }

    fun currentItem(): MediaItem? =
        renderer.items.getOrNull(renderer.currentIndex)

    fun currentIndex(): Int = renderer.currentIndex

    fun isPlayIconHit(x: Float, y: Float): Boolean = renderer.isPlayIconHit(x, y)

    fun playMotionVideo(item: MediaItem) {
        renderer.startMotionVideo(item)
    }

    fun stopMotionVideo() {
        renderer.stopMotionVideo()
    }

    fun indexOf(item: MediaItem): Int = renderer.items.indexOfFirst { it.id == item.id }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)
        gestureDetector?.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimation()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                lastTouchX = event.x
                lastTouchY = event.y
                dragging = false
                zooming = false
                sheetDragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                zooming = true
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (scaleDetector?.isInProgress == true) {
                    zooming = true
                }
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                if (!zooming && scaleDetector?.isInProgress != true) {
                    if (renderer.scale > 1.01f) {
                        renderer.panX += dx
                        renderer.panY += dy
                        clampPan()
                        requestRender()
                    } else {
                        if (!dragging && !sheetDragging &&
                            (abs(dx) > touchSlop || abs(dy) > touchSlop)
                        ) {
                            if (abs(dx) >= abs(dy)) {
                                dragging = true
                            } else {
                                sheetDragging = true
                                onSheetDragStart?.invoke()
                            }
                            onPressEnd?.invoke()
                        }
                        if (dragging) {
                            renderer.offsetPx = (renderer.offsetPx + dx)
                                .coerceIn(-width.toFloat(), width.toFloat())
                            requestRender()
                        } else if (sheetDragging) {
                            onSheetDrag?.invoke(dy)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                zooming = false
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                val wasSheetDragging = sheetDragging
                dragging = false
                sheetDragging = false
                onPressEnd?.invoke()
                if (wasSheetDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    onSheetDragEnd?.invoke(velocityTracker?.yVelocity ?: 0f)
                } else if (!zooming && renderer.scale <= 1.01f) {
                    settlePage()
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasSheetDragging = sheetDragging
                dragging = false
                sheetDragging = false
                onPressEnd?.invoke()
                if (wasSheetDragging) {
                    onSheetDragEnd?.invoke(0f)
                } else {
                    settlePage()
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        renderer.stopMotionVideo()
        videoManager.release()
        executor.shutdownNow()
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    private fun settlePage() {
        val pageWidth = width.toFloat()
        if (pageWidth <= 0f) {
            return
        }
        val offset = renderer.offsetPx
        val tracker = velocityTracker
        var velocityX = 0f
        if (tracker != null) {
            tracker.computeCurrentVelocity(1000)
            velocityX = tracker.xVelocity
        }
        val target = when {
            offset > pageWidth * 0.25f || velocityX > minimumVelocity -> pageWidth
            offset < -pageWidth * 0.25f || velocityX < -minimumVelocity -> -pageWidth
            else -> 0f
        }
        animateOffset(target)
    }

    private fun animateOffset(target: Float) {
        cancelAnimation()
        val start = renderer.offsetPx
        if (start == target) {
            if (target != 0f) {
                applyPageShift(target)
            }
            return
        }
        val animator = ValueAnimator.ofFloat(start, target)
        animator.duration = 220L
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            renderer.offsetPx = it.animatedValue as Float
            requestRender()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (target != 0f) {
                    applyPageShift(target)
                } else {
                    renderer.offsetPx = 0f
                    requestRender()
                }
            }
        })
        this.animator = animator
        animator.start()
    }

    private fun applyPageShift(target: Float) {
        stopMotionVideo()
        val delta = if (target > 0f) -1 else 1
        val newIndex = (renderer.currentIndex + delta)
            .coerceIn(0, (renderer.items.size - 1).coerceAtLeast(0))
        renderer.currentIndex = newIndex
        renderer.offsetPx = 0f
        dragging = false
        resetZoom()
        requestRender()
        notifyPageChanged()
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun notifyPageChanged() {
        val item = currentItem() ?: return
        onPageChanged?.invoke(renderer.currentIndex, item)
    }

    private fun toggleZoom(focusX: Float, focusY: Float) {
        if (renderer.scale > 1.05f) {
            resetZoom()
            requestRender()
        } else {
            zoomTo(2.5f, focusX, focusY)
        }
    }

    private fun zoomTo(newScale: Float, focusX: Float, focusY: Float) {
        val oldScale = renderer.scale
        if (oldScale <= 0f) {
            return
        }
        val clamped = newScale.coerceIn(1f, 5f)
        val cx = width / 2f
        val cy = height / 2f
        val contentX = (focusX - cx - renderer.panX) / oldScale + cx
        val contentY = (focusY - cy - renderer.panY) / oldScale + cy
        renderer.panX = focusX - cx - (contentX - cx) * clamped
        renderer.panY = focusY - cy - (contentY - cy) * clamped
        renderer.scale = clamped
        if (clamped <= 1f) {
            renderer.scale = 1f
            renderer.panX = 0f
            renderer.panY = 0f
        }
        clampPan()
        requestRender()
    }

    private fun resetZoom() {
        renderer.scale = 1f
        renderer.panX = 0f
        renderer.panY = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        requestRender()
    }

    private fun clampPan() {
        val limitX = (renderer.scale - 1f) * width / 2f
        val limitY = (renderer.scale - 1f) * height / 2f
        renderer.panX = renderer.panX.coerceIn(-limitX, limitX)
        renderer.panY = renderer.panY.coerceIn(-limitY, limitY)
    }

    private fun loadImage(item: MediaItem, viewportWidth: Int, viewportHeight: Int) {
        val uri = item.uri ?: return
        if (renderer.textureStore.textureOf(item.id) != null ||
            renderer.textureStore.isFailed(item.id)
        ) {
            return
        }
        if (inFlight.putIfAbsent(item.id, true) != null) {
            return
        }
        executor.execute {
            try {
                val bitmap = if (item.isVideo) {
                    context.contentResolver.loadThumbnail(
                        uri,
                        Size(
                            (viewportWidth / 2).coerceAtLeast(160),
                            (viewportHeight / 2).coerceAtLeast(160),
                        ),
                        null,
                    )
                } else {
                    val maxSize = minOf(
                        renderer.maxTextureSize,
                        maxOf(viewportWidth, viewportHeight),
                    )
                    decodeImage(item, maxSize)
                }
                if (bitmap != null) {
                    renderer.textureStore.enqueue(item.id, bitmap)
                    post { requestRender() }
                } else {
                    renderer.textureStore.markFailed(item.id)
                }
            } catch (e: Exception) {
                android.util.Log.e("GlImage", "decode failed id=${item.id}", e)
                renderer.textureStore.markFailed(item.id)
            } finally {
                inFlight.remove(item.id)
            }
        }
    }

    private fun decodeImage(item: MediaItem, maxSize: Int): Bitmap? {
        val uri = item.uri ?: return null
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val imageWidth = info.size.width
            val imageHeight = info.size.height
            val longest = maxOf(imageWidth, imageHeight)
            if (longest > maxSize && longest > 0) {
                val ratio = maxSize.toFloat() / longest
                decoder.setTargetSize(
                    (imageWidth * ratio).toInt().coerceAtLeast(1),
                    (imageHeight * ratio).toInt().coerceAtLeast(1),
                )
            }
        }
    }
}
