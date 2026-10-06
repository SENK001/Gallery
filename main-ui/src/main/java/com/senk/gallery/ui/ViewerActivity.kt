package com.senk.gallery.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.TextureMapView
import com.google.android.material.appbar.MaterialToolbar
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.ui.theme.ThemeUtils
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.ui.gl.GlImageViewer
import com.senk.gallery.util.DateFormats
import com.senk.gallery.util.FileSizeUtils
import com.senk.gallery.util.SystemBarUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewerActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private lateinit var viewer: GlImageViewer
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomBar: View
    private lateinit var favoriteButton: ImageButton
    private lateinit var detailsSheet: View
    private lateinit var sheetScroll: NestedScrollView
    private lateinit var sheetBasicInfo: TextView
    private lateinit var sheetMapCard: View
    private lateinit var sheetMapContainer: FrameLayout
    private lateinit var sheetMapAddress: TextView
    private lateinit var sheetExifInfo: TextView
    private var bottomBarBaseTop = 0
    private var bottomBarBaseBottom = 0
    private val items = ArrayList<MediaItem>()
    private var baseUri: Uri = Uri.EMPTY
    private var initialPosition = 0
    private var loading = false
    private var endReached = false
    private var immersive = false
    private var currentItem: MediaItem? = null
    private var pendingFavoriteItem: MediaItem? = null
    private var pendingFavoriteValue = 0
    private var addressResolver: LocationAddressResolver? = null
    private var sheetMapView: TextureMapView? = null
    private var sheetHeight = 0
    private var sheetProgress = 0f
    private var sheetAnimator: ValueAnimator? = null
    private var infoToken = 0
    private var infoItemId = -1L
    private var currentLocation: Pair<Double, Double>? = null
    private var currentAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(ThemeUtils.surfaceContent(this))
        setContentView(R.layout.activity_viewer)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
        rootView = findViewById(R.id.viewer_root)
        viewer = findViewById(R.id.image_viewer)
        viewer.setLightBackground(true)
        toolbar = findViewById(R.id.toolbar)
        bottomBar = findViewById(R.id.bottom_bar)
        favoriteButton = findViewById(R.id.action_favorite)
        bottomBarBaseTop = bottomBar.paddingTop
        bottomBarBaseBottom = bottomBar.paddingBottom
        toolbar.setNavigationOnClickListener { finish() }
        setupSheet()
        applyInsets()
        viewer.onSingleTap = { x, y -> onViewerTap(x, y) }
        viewer.onLongPress = { onViewerLongPress() }
        viewer.onPressEnd = { viewer.stopMotionVideo() }
        viewer.onSheetDragStart = {
            detailsSheet.visibility = View.VISIBLE
            currentItem?.let { ensureSheetContent(it) }
        }
        viewer.onSheetDrag = { dy -> setSheetProgress(sheetProgress - dy / sheetHeight) }
        viewer.onSheetDragEnd = { velocityY -> settleSheet(velocityY) }
        viewer.onPageChanged = { index, item ->
            bindPage(index, item)
            maybeLoadMore(index)
        }
        findViewById<ImageButton>(R.id.action_share).setOnClickListener { share(currentItem) }
        favoriteButton.setOnClickListener { toggleFavorite() }
        findViewById<ImageButton>(R.id.action_info).setOnClickListener { openSheet() }
        baseUri = intent.getStringExtra(EXTRA_BASE_URI)?.let(Uri::parse) ?: Uri.EMPTY
        initialPosition = intent.getIntExtra(EXTRA_POSITION, 0)
        loadInitial()
    }

    override fun onResume() {
        super.onResume()
        viewer.onResume()
        sheetMapView?.onResume()
    }

    override fun onPause() {
        viewer.stopMotionVideo()
        viewer.onPause()
        sheetMapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        addressResolver?.release()
        addressResolver = null
        sheetAnimator?.cancel()
        sheetMapView?.onDestroy()
        sheetMapView = null
        super.onDestroy()
    }

    private fun setupSheet() {
        detailsSheet = findViewById(R.id.details_sheet)
        sheetScroll = findViewById(R.id.sheet_scroll)
        sheetBasicInfo = findViewById(R.id.sheet_basic_info)
        sheetMapCard = findViewById(R.id.sheet_map_card)
        sheetMapContainer = findViewById(R.id.sheet_map_container)
        sheetMapAddress = findViewById(R.id.sheet_map_address)
        sheetExifInfo = findViewById(R.id.sheet_exif_info)
        findViewById<View>(R.id.sheet_map_overlay).setOnClickListener { openPhotoMap() }
        sheetHeight = (resources.displayMetrics.heightPixels * SHEET_HEIGHT_RATIO).toInt()
        detailsSheet.layoutParams.height = sheetHeight
        detailsSheet.translationY = sheetHeight.toFloat()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (sheetProgress > 0.01f) {
                        closeSheet()
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(0, bars.top, 0, 0)
            bottomBar.setPadding(
                0,
                bottomBarBaseTop,
                0,
                bottomBarBaseBottom + bars.bottom,
            )
            sheetScroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun loadInitial() {
        if (loading || baseUri == Uri.EMPTY) {
            return
        }
        loading = true
        lifecycleScope.launch {
            val limit = initialPosition + PAGE_SIZE
            val result = MediaQuery.media(
                contentResolver,
                GalleryContract.withPage(baseUri, limit, 0),
            )
            if (isFinishing || isDestroyed) {
                loading = false
                return@launch
            }
            items.clear()
            items.addAll(result)
            endReached = result.size < limit
            val index = initialPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            viewer.setItems(ArrayList(items), index)
            updatePageUi()
            loading = false
        }
    }

    private fun maybeLoadMore(index: Int) {
        if (index >= items.size - LOAD_MORE_THRESHOLD) {
            loadMore()
        }
    }

    private fun loadMore() {
        if (loading || endReached || baseUri == Uri.EMPTY) {
            return
        }
        loading = true
        lifecycleScope.launch {
            val offset = items.size
            val result = MediaQuery.media(
                contentResolver,
                GalleryContract.withPage(baseUri, PAGE_SIZE, offset),
            )
            if (isFinishing || isDestroyed) {
                loading = false
                return@launch
            }
            items.addAll(result)
            if (result.size < PAGE_SIZE) {
                endReached = true
            }
            viewer.setItems(ArrayList(items), viewer.currentIndex())
            loading = false
        }
    }

    private fun bindPage(index: Int, item: MediaItem) {
        currentItem = item
        toolbar.title = DateFormats.formatDateTime(item.dateTaken)
        toolbar.subtitle = getString(
            R.string.gallery_position_format,
            index + 1,
            items.size,
        )
        updatePageUi()
        if (sheetProgress > 0.01f) {
            ensureSheetContent(item)
        }
    }

    private fun updatePageUi() {
        val item = currentItem ?: return
        favoriteButton.setImageResource(
            if (item.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        )
    }

    private fun onViewerTap(x: Float, y: Float) {
        if (sheetProgress > 0.01f) {
            closeSheet()
            return
        }
        val item = currentItem
        if (item != null && item.isVideo && viewer.isPlayIconHit(x, y)) {
            openVideo()
        } else {
            toggleImmersive()
        }
    }

    private fun onViewerLongPress() {
        val item = currentItem ?: return
        if (item.isMotionPhoto && item.motionVideoLength > 0L) {
            viewer.playMotionVideo(item)
        }
    }

    private fun openSheet() {
        val item = currentItem ?: return
        detailsSheet.visibility = View.VISIBLE
        if (sheetProgress < 0.99f) {
            animateSheet(1f)
        }
        ensureSheetContent(item)
    }

    private fun closeSheet() {
        animateSheet(0f)
    }

    private fun settleSheet(velocityY: Float) {
        val minimumVelocity = ViewConfiguration.get(this).scaledMinimumFlingVelocity
        val open = sheetProgress > SHEET_SETTLE_THRESHOLD || velocityY < -minimumVelocity
        animateSheet(if (open) 1f else 0f)
    }

    private fun animateSheet(target: Float) {
        sheetAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(sheetProgress, target)
        animator.duration = SHEET_ANIMATION_DURATION
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { setSheetProgress(it.animatedValue as Float) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (target == 0f) {
                    detailsSheet.visibility = View.INVISIBLE
                }
            }
        })
        sheetAnimator = animator
        animator.start()
    }

    private fun setSheetProgress(progress: Float) {
        sheetProgress = progress.coerceIn(0f, 1f)
        detailsSheet.translationY = sheetHeight * (1f - sheetProgress)
        if (!immersive) {
            val barsAlpha = 1f - sheetProgress
            toolbar.alpha = barsAlpha
            bottomBar.alpha = barsAlpha
            val barsVisible = barsAlpha > 0.05f
            toolbar.isVisible = barsVisible
            bottomBar.isVisible = barsVisible
        }
    }

    private fun ensureSheetContent(item: MediaItem) {
        if (item.id == infoItemId && sheetBasicInfo.text.isNotEmpty()) {
            return
        }
        refreshSheetContent(item)
    }

    private fun refreshSheetContent(item: MediaItem) {
        val token = ++infoToken
        infoItemId = item.id
        currentLocation = null
        currentAddress = null
        sheetScroll.scrollTo(0, 0)
        sheetBasicInfo.text = getString(R.string.gallery_info_loading)
        sheetExifInfo.text = ""
        sheetMapCard.isVisible = false
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { loadViewerInfo(item) }
            if (token != infoToken || isFinishing || isDestroyed || currentItem?.id != item.id) {
                return@launch
            }
            sheetBasicInfo.text = info.basic
            sheetExifInfo.text = info.exif
            val location = info.location
            if (location == null) {
                sheetMapCard.isVisible = false
            } else {
                showMapCard(item, location.first, location.second, token)
            }
        }
    }

    private fun showMapCard(item: MediaItem, latitude: Double, longitude: Double, token: Int) {
        currentLocation = latitude to longitude
        val mapView = ensureSheetMap()
        if (mapView == null) {
            // SDK 不可用（如未配置 AK）：不显示地图卡片，仅隐藏即可，避免崩溃。
            sheetMapCard.isVisible = false
            return
        }
        sheetMapCard.isVisible = true
        sheetMapAddress.text = getString(R.string.gallery_info_address_loading)
        val bd09 = LocationAddressResolver.toBd09(latitude, longitude)
        mapView.map.apply {
            clear()
            addOverlay(MarkerOptions().position(bd09).icon(MapMarkers.dot(this@ViewerActivity)))
            setMapStatus(MapStatusUpdateFactory.newLatLngZoom(bd09, MAP_CARD_ZOOM))
        }
        val resolver = addressResolver
            ?: LocationAddressResolver(this).also { addressResolver = it }
        resolver.resolve(latitude, longitude) { address ->
            runOnUiThread {
                if (token != infoToken || isFinishing || isDestroyed || currentItem?.id != item.id) {
                    return@runOnUiThread
                }
                currentAddress = address
                sheetMapAddress.text =
                    address ?: getString(R.string.gallery_info_address_failed)
            }
        }
    }

    private fun ensureSheetMap(): TextureMapView? {
        sheetMapView?.let { return it }
        // TextureMapView 构造前必须已完成 SDK 初始化，否则抛 NPE 崩溃（真机实测）。
        // AK 未配置等情况下返回 null，由调用方降级为「地址获取失败」，不崩溃。
        if (!BaiduMapSdk.ensureInitialized(this)) {
            return null
        }
        val mapView = TextureMapView(this)
        mapView.showZoomControls(false)
        mapView.showScaleControl(false)
        mapView.map.uiSettings.setAllGesturesEnabled(false)
        sheetMapContainer.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        sheetMapView = mapView
        return mapView
    }

    private fun openPhotoMap() {
        val item = currentItem ?: return
        val location = currentLocation ?: return
        startActivity(
            PhotoMapActivity.intent(
                this,
                location.first,
                location.second,
                currentAddress,
                item.name,
            ),
        )
    }

    private fun toggleFavorite() {
        val item = currentItem ?: return
        val newValue = if (item.isFavorite) 0 else 1
        if (updateFavorite(item, newValue)) {
            item.isFavorite = newValue == 1
            updatePageUi()
        }
    }

    private fun updateFavorite(item: MediaItem, newValue: Int): Boolean {
        return try {
            contentResolver.update(
                GalleryContract.mediaUri(item.id),
                ContentValues().apply {
                    put(GalleryContract.Columns.FAVORITE, newValue)
                },
                null,
                null,
            )
            true
        } catch (e: RecoverableSecurityException) {
            val sender = e.userAction?.actionIntent?.intentSender
            if (sender == null) {
                Log.e(TAG, "no consent intent for favorite", e)
                return false
            }
            pendingFavoriteItem = item
            pendingFavoriteValue = newValue
            startIntentSenderForResult(sender, REQUEST_FAVORITE, null, 0, 0, 0)
            false
        } catch (e: Exception) {
            Log.e(TAG, "update favorite failed", e)
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FAVORITE) {
            return
        }
        val item = pendingFavoriteItem ?: return
        pendingFavoriteItem = null
        if (resultCode == RESULT_OK && updateFavorite(item, pendingFavoriteValue)) {
            item.isFavorite = pendingFavoriteValue == 1
            updatePageUi()
        }
    }

    private fun share(item: MediaItem?) {
        item?.uri ?: return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.gallery_share_title)))
    }

    private fun openVideo() {
        val item = currentItem ?: return
        if (!item.isVideo) {
            return
        }
        startActivity(
            VideoPlayerActivity.intent(
                this,
                item.uri ?: return,
                item.name,
            ),
        )
    }

    private fun toggleImmersive() {
        immersive = !immersive
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = !immersive)
        viewer.setLightBackground(!immersive)
        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            toolbar.isVisible = false
            bottomBar.isVisible = false
            window.decorView.setBackgroundColor(ThemeUtils.mediaScrim(this))
            rootView.setBackgroundColor(ThemeUtils.mediaScrim(this))
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            toolbar.isVisible = true
            bottomBar.isVisible = true
            updatePageUi()
            window.decorView.setBackgroundColor(ThemeUtils.surfaceContent(this))
            rootView.setBackgroundColor(Color.WHITE)
        }
    }

    private class ViewerInfo(
        val basic: String,
        val exif: String,
        val location: Pair<Double, Double>?,
    )

    private fun loadViewerInfo(item: MediaItem): ViewerInfo {
        val basic = ArrayList<String>()
        basic.add("名称: ${item.name ?: "-"}")
        basic.add("类型: ${item.mimeType ?: "-"}")
        basic.add("拍摄时间: ${DateFormats.formatDateTime(item.dateTaken)}")
        basic.add("大小: ${FileSizeUtils.format(item.size)}")
        basic.add("尺寸: ${item.width} x ${item.height}")
        if (item.isVideo && item.duration > 0L) {
            basic.add("时长: ${DateFormats.formatDuration(item.duration)}")
        }
        item.relativePath?.let { basic.add("路径: $it") }
        item.ownerPackage?.let { basic.add("来源应用: $it") }
        if (item.isMotionPhoto) {
            basic.add("动态照片: 是")
        }
        if (item.isPanorama) {
            basic.add("全景: 是")
        }

        val exif = ArrayList<String>()
        var location: Pair<Double, Double>? = null
        try {
            contentResolver.query(
                GalleryContract.exifUri(item.id),
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columns = GalleryContract.ExifColumns
                    fun value(name: String): String? {
                        val index = cursor.getColumnIndex(name)
                        if (index < 0 || cursor.isNull(index)) {
                            return null
                        }
                        return cursor.getString(index)?.takeIf { it.isNotBlank() }
                    }
                    value(columns.MAKE)?.let { exif.add("品牌: $it") }
                    value(columns.MODEL)?.let { exif.add("型号: $it") }
                    value(columns.LENS_MODEL)?.let { exif.add("镜头: $it") }
                    formatRational(value(columns.F_NUMBER))?.let { exif.add("光圈: f/$it") }
                    value(columns.EXPOSURE_TIME)?.let { exif.add("曝光时间: $it") }
                    value(columns.ISO)?.let { exif.add("ISO: $it") }
                    formatRational(value(columns.FOCAL_LENGTH))?.let { exif.add("焦距: ${it}mm") }
                    formatRational(value(columns.FOCAL_LENGTH_35MM))?.let {
                        exif.add("等效焦距: ${it}mm")
                    }
                    value(columns.DATETIME)?.let { exif.add("EXIF 拍摄时间: $it") }
                    value(columns.SOFTWARE)?.let { exif.add("软件: $it") }
                    if (value(columns.HAS_LOCATION) == "1") {
                        val latitude = cursor.getDouble(cursor.getColumnIndex(columns.LATITUDE))
                        val longitude = cursor.getDouble(cursor.getColumnIndex(columns.LONGITUDE))
                        location = latitude to longitude
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return ViewerInfo(
            basic.joinToString("\n"),
            exif.joinToString("\n"),
            location,
        )
    }

    private fun formatRational(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }
        return try {
            if (value.contains("/")) {
                val parts = value.split("/")
                val result = parts[0].toDouble() / parts[1].toDouble()
                String.format("%.1f", result)
            } else {
                val result = value.toDouble()
                String.format("%.1f", result)
            }
        } catch (e: Exception) {
            value
        }
    }

    companion object {

        private const val TAG = "ViewerActivity"
        private const val EXTRA_BASE_URI = "base_uri"
        private const val EXTRA_POSITION = "position"
        private const val PAGE_SIZE = 200
        private const val LOAD_MORE_THRESHOLD = 20
        private const val REQUEST_FAVORITE = 1001
        private const val SHEET_HEIGHT_RATIO = 0.52f
        private const val SHEET_SETTLE_THRESHOLD = 0.4f
        private const val SHEET_ANIMATION_DURATION = 200L
        private const val MAP_CARD_ZOOM = 16f

        fun intent(context: Context, baseUri: Uri, position: Int): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_BASE_URI, baseUri.toString())
                .putExtra(EXTRA_POSITION, position)
    }
}
