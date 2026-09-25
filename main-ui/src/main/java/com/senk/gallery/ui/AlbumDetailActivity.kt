package com.senk.gallery.ui

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.entity.MediaSet
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.data.provider.GalleryCursorReader
import com.senk.gallery.ui.gl.GlThumbnailGridView
import com.senk.gallery.util.SystemBarUtils
import kotlinx.coroutines.launch

class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var grid: GlThumbnailGridView
    private lateinit var toolbar: MaterialToolbar
    private var observer: ContentObserver? = null
    private val items = ArrayList<MediaItem>()
    private var baseUri: Uri = Uri.EMPTY
    private var loading = false
    private var endReached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_album_detail)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
        grid = findViewById(R.id.album_grid)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "")
        toolbar.setNavigationOnClickListener { finish() }
        applyInsets()
        baseUri = intent.getStringExtra(EXTRA_BASE_URI)?.let(Uri::parse) ?: Uri.EMPTY
        grid.setColumns(COLUMNS)
        grid.onItemClick = { item -> openViewer(item) }
        grid.onLoadMore = { loadPage(reset = false) }
        registerObserver()
        loadPage(reset = true)
    }

    override fun onResume() {
        super.onResume()
        grid.onResume()
    }

    override fun onPause() {
        grid.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.album_detail_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun registerObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                loadPage(reset = true)
            }
        }
        contentResolver.registerContentObserver(GalleryContract.CONTENT_URI, true, observer)
        this.observer = observer
    }

    private fun loadPage(reset: Boolean) {
        if (loading || baseUri == Uri.EMPTY || (endReached && !reset) || isFinishing) {
            return
        }
        loading = true
        lifecycleScope.launch {
            val offset = if (reset) 0 else items.size
            val result = MediaQuery.media(
                contentResolver,
                GalleryContract.withPage(baseUri, PAGE_SIZE, offset),
            )
            if (isFinishing || isDestroyed) {
                loading = false
                return@launch
            }
            if (reset) {
                items.clear()
                endReached = false
                grid.scrollToTop()
            }
            items.addAll(result)
            if (result.size < PAGE_SIZE) {
                endReached = true
            }
            grid.submitItems(ArrayList(items))
            loading = false
        }
    }

    private fun openViewer(item: MediaItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) {
            return
        }
        startActivity(ViewerActivity.intent(this, baseUri, index))
    }

    companion object {

        private const val EXTRA_BASE_URI = "base_uri"
        private const val EXTRA_TITLE = "title"
        private const val PAGE_SIZE = 200
        private const val COLUMNS = 4

        fun start(context: Context, album: GalleryCursorReader.AlbumInfo) {
            val baseUri = when (album.albumType) {
                MediaSet.TYPE_FOLDER ->
                    GalleryContract.folderAlbumItemsUri(album.bucketId.orEmpty())
                MediaSet.TYPE_THIRD_PARTY ->
                    GalleryContract.appAlbumItemsUri(album.ownerPackage.orEmpty())
                else -> GalleryContract.albumItemsUri(album.albumType)
            }
            context.startActivity(
                Intent(context, AlbumDetailActivity::class.java)
                    .putExtra(EXTRA_BASE_URI, baseUri.toString())
                    .putExtra(EXTRA_TITLE, album.name),
            )
        }
    }
}
