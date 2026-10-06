package com.senk.gallery.ui

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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
    private lateinit var statusBarBg: View
    private lateinit var scrim: View
    private var observer: ContentObserver? = null
    private val items = ArrayList<MediaItem>()
    private var baseUri: Uri = Uri.EMPTY
    private var loading = false
    private var endReached = false

    /** 已进入「缩略图位于标题栏下方」的状态。 */
    private var scrolledPast = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_album_detail)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
        grid = findViewById(R.id.album_grid)
        toolbar = findViewById(R.id.toolbar)
        statusBarBg = findViewById(R.id.album_status_bar_bg)
        scrim = findViewById(R.id.album_toolbar_scrim)
        toolbar.setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "")
        toolbar.setNavigationOnClickListener { finish() }
        applyInsets()
        grid.setColumns(COLUMNS)
        grid.onItemClick = { item -> openViewer(item) }
        grid.onLoadMore = { loadPage(reset = false) }
        grid.onScrollChanged = { scrollY -> onGridScrolled(scrollY) }
        baseUri = intent.getStringExtra(EXTRA_BASE_URI)?.let(Uri::parse) ?: Uri.EMPTY
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

    /**
     * 宫格铺满整个窗口，因此根布局不吃 inset，只把 inset 分给覆盖层：
     * 状态栏底条高度、标题栏的 marginTop、渐变遮罩高度；
     * 另外把宫格的 `topInset` 设为「状态栏 + 标题栏」，
     * 让**静止时第一行缩略图落在标题栏下方**（与首页「照片」页同一套算法）。
     *
     * 注意区分两种状态：
     * - 静止：白底标题栏 + 首行的位置由 topInset 留出（本方法负责）
     * - 上划：缩略图滚到状态栏区域，标题栏让位给遮罩（由 [applyChrome] 负责）
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.album_detail_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 改 layoutParams 后必须重新赋值才会重新布局
            statusBarBg.layoutParams = statusBarBg.layoutParams.apply { height = bars.top }
            (toolbar.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = bars.top
                it.leftMargin = bars.left
                it.rightMargin = bars.right
                toolbar.layoutParams = it
            }
            val scrimHeight = bars.top + toolbarHeightPx()
            (scrim.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.height = scrimHeight
                scrim.layoutParams = it
            }
            scrim.background = ToolbarScrimDrawable(scrimHeight)
            grid.setTopInset(scrimHeight)
            insets
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.album_detail_root))
    }

    private fun toolbarHeightPx(): Int {
        val tv = android.util.TypedValue()
        val ok = theme.resolveAttribute(
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

    /**
     * 与首页照片页同样的判定：有 40px 死区，避免刚滑动一帧就闪变。
     *
     * 静止 → **不透明白底标题栏** + 深色标题/返回键（宫格第一行在其下方）；
     * 上划 → 标题栏让位给渐变遮罩，标题与返回键转白色，缩略图滚到状态栏区域。
     */
    private fun onGridScrolled(scrollY: Float) {
        val past = scrollY > SCROLL_DEAD_ZONE_PX
        if (past == scrolledPast) {
            return
        }
        scrolledPast = past
        applyChrome(past)
    }

    private fun applyChrome(overGrid: Boolean) {
        if (overGrid) {
            // 遮罩接管背景，标题栏自身透明 + 白字白图标
            toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            toolbar.setTitleTextColor(android.graphics.Color.WHITE)
            toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)
            scrim.isVisible = true
            statusBarBg.isVisible = false
            SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = false)
        } else {
            // 静止：不透明白底 + 深色标题/返回键
            toolbar.setBackgroundColor(
                ContextCompat.getColor(this, R.color.gallery_toolbar_surface),
            )
            toolbar.setTitleTextColor(
                ContextCompat.getColor(this, R.color.gallery_text_primary),
            )
            toolbar.navigationIcon?.setTint(
                ContextCompat.getColor(this, R.color.gallery_text_primary),
            )
            scrim.isVisible = false
            statusBarBg.isVisible = true
            SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
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

        /** 滚动死区：小于它视为「还在顶部」，标题栏保持透明、不显示遮罩。 */
        private const val SCROLL_DEAD_ZONE_PX = 40f

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
