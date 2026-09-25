package com.senk.gallery.ui

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
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
        registerObserver()
        refresh()
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
