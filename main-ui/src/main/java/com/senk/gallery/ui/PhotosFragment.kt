package com.senk.gallery.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.View

import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.ui.gl.GlThumbnailGridView
import com.senk.gallery.util.DisplayUtils
import kotlinx.coroutines.launch

class PhotosFragment : Fragment(R.layout.fragment_photos) {

    private var grid: GlThumbnailGridView? = null
    private var observer: ContentObserver? = null
    private val items = ArrayList<MediaItem>()
    private var loading = false
    private var endReached = false

    /** 宫格滚动回调，由宿主（MainActivity）设置，用于驱动标题栏渐变。 */
    var onGridScroll: ((Float) -> Unit)? = null

    /** 已应用的顶部预留，避免 insets 每次回调都重设。 */
    private var lastTopInset = -1

    /** 已应用的底部预留，避免 insets 每次回调都重设。 */
    private var lastBottomPadding = -1

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        grid = view.findViewById(R.id.photo_grid)
        grid?.apply {
            setColumns(COLUMNS)
            // 标题栏是透明的，透出的其实是宫格 surface 的底色——必须跟随主题，
            // 否则深色模式下标题栏区域会保持白色
            setSurfaceBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gallery_chrome_surface))
            onItemClick = { item -> openViewer(item) }
            onLoadMore = { loadPage(reset = false) }
            onScrollChanged = { scrollY -> onGridScroll?.invoke(scrollY) }
        }
        applyInsets(view)
        registerObserver()
        loadPage(reset = true)
    }

    /**
     * 顶部预留 = 状态栏 + 标题栏：标题栏**自身透明**、靠父容器主题底色显示，
     * 但仍要为它留出位置，让缩略图第一行落在它下方（否则首行会被标题压住）。
     *
     * 为什么是「内容偏移」而不是给宫格加 margin：宫格是 GL 自绘的 SurfaceView，
     * 缩略图铺满整个 surface（也才能画到系统导航栏区域），位置只能由渲染器算，
     * 所以用 [GridGeometry.topInset] 做固定下移。
     *
     * 底部预留 = **胶囊导航占位 + 系统导航栏高度**，让滚到底时最后一行完整停在两者上方
     * （本页有悬浮胶囊导航，与相册详情页不同）。用 [GlThumbnailGridView.setBottomPadding]
     * 而不是 `setBottomInset`：后者只把内容撑长，余量不足一行时最后一行仍会贴着屏幕底边。
     *
     * 必须走 insets 监听：onViewCreated 时视图尚未 attach 到窗口，
     * `getRootWindowInsets()` 返回 null，会得到 0 而完全失效（真机实测）。
     */
    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val topTarget = bars.top + toolbarHeightPx()
            if (topTarget != lastTopInset) {
                lastTopInset = topTarget
                grid?.setTopInset(topTarget)
            }

            // 胶囊导航占位（含其下边距）由布局决定，系统导航栏高度由 inset 决定，两者相加
            val capsulePx = DisplayUtils.dp2px(requireContext(), CAPSULE_AREA_DP).toInt()
            val bottomTarget = capsulePx + bars.bottom
            if (bottomTarget != lastBottomPadding) {
                lastBottomPadding = bottomTarget
                grid?.setBottomPadding(bottomTarget)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun toolbarHeightPx(): Int {
        val tv = android.util.TypedValue()
        val ok = requireContext().theme.resolveAttribute(
            androidx.appcompat.R.attr.actionBarSize,
            tv,
            true,
        )
        return if (ok) {
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            0
        }
    }

    override fun onResume() {
        super.onResume()
        grid?.onResume()
    }

    override fun onPause() {
        grid?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        observer?.let {
            requireContext().contentResolver.unregisterContentObserver(it)
        }
        observer = null
        grid = null
        super.onDestroyView()
    }

    private fun registerObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                loadPage(reset = true)
            }
        }
        requireContext().contentResolver.registerContentObserver(
            GalleryContract.CONTENT_URI,
            true,
            observer,
        )
        this.observer = observer
    }

    fun loadPage(reset: Boolean) {
        if (loading || (endReached && !reset) || view == null || !isAdded) {
            return
        }
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resolver = requireContext().contentResolver
            val offset = if (reset) 0 else items.size
            val result = MediaQuery.media(
                resolver,
                GalleryContract.withPage(GalleryContract.MEDIA_URI, PAGE_SIZE, offset),
            )
            if (view == null) {
                loading = false
                return@launch
            }
            if (reset) {
                items.clear()
                endReached = false
                grid?.scrollToTop()
            }
            items.addAll(result)
            if (result.size < PAGE_SIZE) {
                endReached = true
            }
            grid?.submitItems(ArrayList(items))
            loading = false
        }
    }

    private fun openViewer(item: MediaItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) {
            return
        }
        startActivity(
            ViewerActivity.intent(requireContext(), GalleryContract.MEDIA_URI, index),
        )
    }

    companion object {
        const val PAGE_SIZE = 200
        private const val COLUMNS = 4

        /**
         * 底部悬浮胶囊导航「卡片高度 + 下边距」所占的高度（dp）。
         * 实测胶囊卡片高 182px、下边距 16dp≈52px，合计 234px ≈ 72dp（本机 density 3.25）。
         * 注意这里只算胶囊本身，**系统导航栏高度由 inset 另加**，不要重复计入。
         */
        private const val CAPSULE_AREA_DP = 72f
    }
}
