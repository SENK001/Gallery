package com.senk.gallery.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.View

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

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        grid = view.findViewById(R.id.photo_grid)
        grid?.apply {
            setColumns(COLUMNS)
            setBottomInset(DisplayUtils.dp2px(requireContext(), BOTTOM_INSET_DP).toInt())
            onItemClick = { item -> openViewer(item) }
            onLoadMore = { loadPage(reset = false) }
            onScrollChanged = { scrollY -> onGridScroll?.invoke(scrollY) }
        }
        applyTopInsetOnInsets(view)
        registerObserver()
        loadPage(reset = true)
    }

    /**
     * 顶部预留 = 状态栏 + 标题栏，让**第一行落在标题栏下方**，标题栏在静止时正常显示。
     *
     * 为什么是「内容偏移」而不是给宫格加 margin：宫格是 GL 自绘的 SurfaceView，
     * 缩略图铺满整个 surface（也才能画到系统导航栏区域），位置只能由渲染器算，
     * 所以用 [GridGeometry.topInset] 做固定下移。
     *
     * 必须走 insets 监听：onViewCreated 时视图尚未 attach 到窗口，
     * `getRootWindowInsets()` 返回 null，会得到 0 而完全失效（真机实测）。
     */
    private fun applyTopInsetOnInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val target = statusBar + toolbarHeightPx()
            if (target != lastTopInset) {
                lastTopInset = target
                grid?.setTopInset(target)
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
        private const val BOTTOM_INSET_DP = 92f
    }
}
