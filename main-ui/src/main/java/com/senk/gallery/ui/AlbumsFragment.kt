package com.senk.gallery.ui

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.senk.gallery.data.provider.GalleryContract
import kotlinx.coroutines.launch

class AlbumsFragment : Fragment(R.layout.fragment_albums) {

    private var list: RecyclerView? = null
    private var emptyView: TextView? = null
    private var adapter: AlbumListAdapter? = null
    private var observer: ContentObserver? = null
    private var loading = false

    /** 已应用的顶部 padding，避免 insets 每次回调都重设一次。 */
    private var lastPaddingTop = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.album_list)
        emptyView = view.findViewById(R.id.empty_view)
        adapter = AlbumListAdapter { album ->
            AlbumDetailActivity.start(requireContext(), album)
        }
        list?.adapter = adapter
        (list?.layoutManager as? GridLayoutManager)?.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    adapter?.spanSize(position) ?: 1
            }
        applyTopInsetOnInsets(view)
        registerObserver()
        refresh()
    }

    /**
     * 相册页**保持原样**：标题栏是不透明白底，内容不从这里下面穿过。
     * 由于宿主（MainActivity）的 pager 现在铺满全屏、不再给根布局加内边距，
     * 改由列表自己按「状态栏 + 标题栏」预留顶部，等价于改动前的占位式布局。
     *
     * 用 padding（配合布局里的 `clipToPadding=false`）而不是 margin，保留滚动手感。
     * 同样必须走 insets 监听：onViewCreated 时视图还没 attach，读不到真实 inset。
     */
    private fun applyTopInsetOnInsets(root: View) {
        val list = this.list ?: return
        val basePaddingTop = list.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val target = basePaddingTop + statusBar + toolbarHeightPx()
            if (target != lastPaddingTop) {
                lastPaddingTop = target
                list.setPadding(
                    list.paddingLeft,
                    target,
                    list.paddingRight,
                    list.paddingBottom,
                )
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

    override fun onDestroyView() {
        observer?.let {
            requireContext().contentResolver.unregisterContentObserver(it)
        }
        observer = null
        list = null
        emptyView = null
        adapter = null
        super.onDestroyView()
    }

    private fun registerObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        requireContext().contentResolver.registerContentObserver(
            GalleryContract.CONTENT_URI,
            true,
            observer,
        )
        this.observer = observer
    }

    fun refresh() {
        if (loading || view == null || !isAdded) {
            return
        }
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resolver = requireContext().contentResolver
            val common = MediaQuery.albums(resolver, GalleryContract.CATEGORY_COMMON)
            val more = MediaQuery.albums(resolver, GalleryContract.CATEGORY_MORE)
            if (view == null) {
                loading = false
                return@launch
            }
            val rows = ArrayList<AlbumListAdapter.Row>()
            if (common.isNotEmpty()) {
                rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_common)))
                common.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = true)) }
            }
            if (more.isNotEmpty()) {
                rows.add(AlbumListAdapter.Row.Header(getString(R.string.gallery_section_more)))
                more.forEach { rows.add(AlbumListAdapter.Row.Album(it, isCommon = false)) }
            }
            // 最近删除：暂缓功能，详见 docs/development-plan.md
            adapter?.submit(rows)
            emptyView?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            loading = false
        }
    }
}
