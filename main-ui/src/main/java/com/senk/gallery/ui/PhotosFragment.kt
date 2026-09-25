package com.senk.gallery.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.View

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.ui.gl.GlThumbnailGridView
import kotlinx.coroutines.launch

class PhotosFragment : Fragment(R.layout.fragment_photos) {

    private var grid: GlThumbnailGridView? = null
    private var observer: ContentObserver? = null
    private val items = ArrayList<MediaItem>()
    private var loading = false
    private var endReached = false

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        grid = view.findViewById(R.id.photo_grid)
        grid?.apply {
            setColumns(COLUMNS)
            onItemClick = { item -> openViewer(item) }
            onLoadMore = { loadPage(reset = false) }
        }
        registerObserver()
        loadPage(reset = true)
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
    }
}
