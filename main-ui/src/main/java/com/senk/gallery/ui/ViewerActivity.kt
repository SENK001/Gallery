package com.senk.gallery.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.senk.gallery.data.album.FavoriteStore
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.entity.MediaObject
import com.senk.gallery.external.GalleryExternal
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
    private lateinit var deleteButton: ImageButton
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
    private var addressResolver: LocationAddressResolver? = null
    private var sheetMapView: TextureMapView? = null
    private var sheetHeight = 0
    private var sheetProgress = 0f
    private var sheetAnimator: ValueAnimator? = null
    private var infoToken = 0
    private var infoItemId = -1L
    private var currentLocation: Pair<Double, Double>? = null
    private var currentAddress: String? = null

    /** 标题栏「详情」菜单项（底栏的详情按钮已移到这里）。 */
    private var infoMenuItem: MenuItem? = null

    /** 当前页的副标题定位 token：异步解析回来时用它丢弃过期结果。 */
    private var subtitleToken = 0

    /** 媒体 id -> 行政区划副标题（null 表示已解析但无定位）。 */
    private val regionCache = HashMap<Long, String?>()

    /** 正在等待系统「移到最近删除」确认的媒体 id。 */
    private var pendingDeleteId = -1L

    /** 底栏删除：复用多选删除的同一套系统授权（MediaStore.createTrashRequest）。 */
    private val trashConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        onDeleteConsentResult(result.resultCode == RESULT_OK)
    }

    /**
     * 外部大图模式：调用方经 [GalleryExternal.EXTRA_VIEW_URIS] 传入自己的 content/file URI。
     * 这些 URI 不属于本应用媒体库（没有 MediaStore id），因此**不查 Provider**、
     * 不使用收藏/详情（EXIF、地图都依赖本应用媒体库 id），只做浏览与分享。
     */
    private var externalMode = false
    private var externalTitle: String? = null

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
        deleteButton = findViewById(R.id.action_delete)
        bottomBarBaseTop = bottomBar.paddingTop
        bottomBarBaseBottom = bottomBar.paddingBottom
        toolbar.setNavigationOnClickListener { finish() }
        setupToolbarMenu()
        setupSheet()
        applyInsets()
        viewer.onSingleTap = { x, y -> onViewerTap(x, y) }
        viewer.onLongPress = { onViewerLongPress() }
        viewer.onPressEnd = { viewer.stopMotionVideo() }
        viewer.onSheetDragStart = {
            if (!immersive) {
                detailsSheet.visibility = View.VISIBLE
                currentItem?.let { ensureSheetContent(it) }
            }
        }
        viewer.onSheetDrag = { dy -> setSheetProgress(sheetProgress - dy / sheetHeight) }
        viewer.onSheetDragEnd = { velocityY -> settleSheet(velocityY) }
        // 沉浸模式下详情面板不可直接展开（面板此刻可能不可见却仍可交互）：
        // 上划改为「先退出沉浸，再展开面板」，保证用户看到的面板才可点击。
        viewer.onSheetUnavailableSwipeUp = { openSheet() }
        viewer.onPageChanged = { index, item ->
            bindPage(index, item)
            maybeLoadMore(index)
        }
        findViewById<ImageButton>(R.id.action_share).setOnClickListener { share(currentItem) }
        favoriteButton.setOnClickListener { toggleFavorite() }
        deleteButton.setOnClickListener { deleteCurrent() }
        val externalUris = intent.getStringArrayListExtra(GalleryExternal.EXTRA_VIEW_URIS)
        if (externalUris != null) {
            // 外部模式：URI 列表来自调用方，不读本应用媒体库。
            setupExternalMode(externalUris)
        } else {
            baseUri = intent.getStringExtra(EXTRA_BASE_URI)?.let(Uri::parse) ?: Uri.EMPTY
            initialPosition = intent.getIntExtra(EXTRA_POSITION, 0)
            loadInitial()
        }
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
        findViewById<View>(R.id.sheet_map_overlay).setOnClickListener {
            // 双重保险：面板未展开/沉浸模式下不响应地图点击（避免点到看不见的卡片）
            if (!immersive && sheetProgress > 0.01f) {
                openPhotoMap()
            }
        }
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

    /** 标题栏菜单：底栏原来的「详情」按钮移到标题栏右侧（图标随标题栏配色）。 */
    private fun setupToolbarMenu() {
        infoMenuItem = toolbar.menu.add(
            Menu.NONE,
            MENU_INFO,
            0,
            R.string.gallery_info,
        ).apply {
            icon = ContextCompat.getDrawable(this@ViewerActivity, R.drawable.ic_info)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            iconTintList = ColorStateList.valueOf(ThemeUtils.iconOnSurface(this@ViewerActivity))
            setOnMenuItemClickListener {
                openSheet()
                true
            }
        }
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

    /**
     * 外部大图模式初始化：把调用方给的 URI 列表包装成「合成 [MediaItem]」直接交给查看器。
     *
     * 注意：
     * - 合成 item 的 id 用**负数**（`-1 - index`），与媒体库真实 id（正数）区分；
     *   [GlImageViewer] 的纹理缓存按 id 索引，负数同样唯一，不会与内部条目冲突。
     * - 不查本应用 Provider（这些 URI 不属于本应用媒体库，id 也不是 MediaStore id）。
     * - 收藏 / 详情依赖本应用媒体库 id（EXIF、地图），外部模式直接隐藏这两个入口；
     *   分享仍可用（单张 ACTION_SEND + 调用方给本应用的读权限再转授给分享目标）。
     */
    private fun setupExternalMode(rawUris: List<String>) {
        val uris = rawUris.asSequence()
            .mapNotNull { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
            .filter { it.scheme == "content" || it.scheme == "file" }
            .toList()
        if (uris.isEmpty()) {
            // 理论上 ExternalEntryActivity 已过滤并提示；这里兜底防御，避免空查看器。
            Log.w(TAG, "external mode without supported uri, finish")
            finish()
            return
        }
        externalMode = true
        externalTitle = intent.getStringExtra(GalleryExternal.EXTRA_TITLE)
        val externalItems = uris.mapIndexed { index, uri ->
            MediaItem().apply {
                this.id = -1L - index
                this.uri = uri
                mimeType = contentResolver.getType(uri)
                name = uri.lastPathSegment ?: uri.toString()
                mediaType = if (mimeType?.startsWith("video/") == true) {
                    MediaObject.TYPE_VIDEO
                } else {
                    MediaObject.TYPE_IMAGE
                }
                // 外部 URI 没有本应用媒体库的拍摄时间/尺寸信息，留 0，不参与排序展示。
                dateTaken = 0L
                dateModified = 0L
            }
        }
        // items 字段同时驱动副标题「位置 / 总数」；外部模式下数据固定，不再分页加载。
        items.clear()
        items.addAll(externalItems)
        endReached = true
        // 收藏、详情、删除不支持外部 URI：隐藏入口（底栏剩余分享按钮仍可用）。
        favoriteButton.isVisible = false
        deleteButton.isVisible = false
        infoMenuItem?.isVisible = false
        // 禁用上划详情手势，避免进入依赖媒体库 id 的详情查询。
        viewer.onSheetDragStart = null
        viewer.onSheetDrag = null
        viewer.onSheetDragEnd = null
        val position = intent.getIntExtra(GalleryExternal.EXTRA_VIEW_POSITION, 0)
            .coerceIn(externalItems.indices)
        Log.i(
            TAG,
            "external mode: uris=${externalItems.size} position=$position title=$externalTitle",
        )
        viewer.setItems(externalItems, position)
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
        if (externalMode) {
            // 外部模式没有本应用媒体库的拍摄时间：标题用调用方 title 或文件名，副标题为位置/总数。
            toolbar.title = externalTitle ?: item.name.orEmpty()
            toolbar.subtitle = getString(
                R.string.gallery_position_format,
                index + 1,
                items.size,
            )
        } else {
            // 内部模式：大标题只显示日期；副标题 = 时间 [· 一级行政区 二级行政区]
            toolbar.title = DateFormats.formatDate(item.dateTaken)
            applySubtitle(item, regionCache[item.id])
            loadSubtitleRegion(item)
        }
        updatePageUi()
        if (!externalMode && sheetProgress > 0.01f) {
            ensureSheetContent(item)
        }
    }

    /** 副标题 = 拍摄时间 +（有定位时）一级/二级行政区划；外部模式下不调用。 */
    private fun applySubtitle(item: MediaItem, region: String?) {
        val time = DateFormats.formatTime(item.dateTaken)
        toolbar.subtitle = when {
            time.isBlank() -> region.orEmpty()
            region.isNullOrBlank() -> time
            else -> getString(R.string.gallery_viewer_subtitle_time_location, time, region)
        }
    }

    /**
     * 异步补全副标题的定位行政区划。
     *
     * 先查 Provider 的 EXIF 坐标（轻量，只有 has_location/latitude/longitude 三列），
     * 再走百度逆地理编码拿省/市/区；同一条目只解析一次（[regionCache]）。
     * token + currentItem 双重校验，快速翻页时丢弃过期结果。
     */
    private fun loadSubtitleRegion(item: MediaItem) {
        if (externalMode || item.id <= 0L) {
            return
        }
        if (regionCache.containsKey(item.id)) {
            return
        }
        val token = ++subtitleToken
        lifecycleScope.launch {
            val location = withContext(Dispatchers.IO) { queryItemLocation(item.id) }
            if (isFinishing || isDestroyed || token != subtitleToken || currentItem?.id != item.id) {
                return@launch
            }
            if (location == null) {
                regionCache[item.id] = null
                return@launch
            }
            val resolver = addressResolver
                ?: LocationAddressResolver(this@ViewerActivity).also { addressResolver = it }
            resolver.resolveAddress(location.first, location.second) { address ->
                if (isFinishing || isDestroyed || token != subtitleToken ||
                    currentItem?.id != item.id
                ) {
                    return@resolveAddress
                }
                val region = formatRegion(address)
                regionCache[item.id] = region
                applySubtitle(item, region)
            }
        }
    }

    /** Provider EXIF 查询：只读 has_location / latitude / longitude（无定位或异常返回 null）。 */
    private fun queryItemLocation(id: Long): Pair<Double, Double>? = try {
        contentResolver.query(
            GalleryContract.exifUri(id),
            arrayOf(
                GalleryContract.ExifColumns.HAS_LOCATION,
                GalleryContract.ExifColumns.LATITUDE,
                GalleryContract.ExifColumns.LONGITUDE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() &&
                cursor.getString(cursor.getColumnIndexOrThrow(
                    GalleryContract.ExifColumns.HAS_LOCATION,
                )) == "1"
            ) {
                val latitude = cursor.getDouble(
                    cursor.getColumnIndexOrThrow(GalleryContract.ExifColumns.LATITUDE),
                )
                val longitude = cursor.getDouble(
                    cursor.getColumnIndexOrThrow(GalleryContract.ExifColumns.LONGITUDE),
                )
                latitude to longitude
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "query location failed: $id", e)
        null
    }

    /**
     * 行政区划展示：一级（省/直辖市）+ 二级（市/区）。
     *
     * 百度对直辖市会同时给出 province=city（如 重庆市/重庆市/渝北区），去重后取前两段，
     * 显示「重庆市 渝北区」；普通省份显示「四川省 成都市」。组件全空时退回完整地址。
     */
    private fun formatRegion(address: LocationAddressResolver.Address?): String? {
        if (address == null) {
            return null
        }
        val parts = LinkedHashSet<String>()
        listOf(address.province, address.city, address.district).forEach { value ->
            value?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        val ordered = parts.toList()
        return when {
            ordered.isEmpty() -> address.formatted
            ordered.size == 1 -> ordered[0]
            else -> getString(R.string.gallery_viewer_region_format, ordered[0], ordered[1])
        }
    }

    private fun updatePageUi() {
        val item = currentItem ?: return
        favoriteButton.setImageResource(
            if (item.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        )
    }

    // ==================== 删除（移到最近删除） ====================

    /**
     * 删除当前媒体：与多选删除一致，走 `MediaStore.createTrashRequest` 由系统确认；
     * 确认成功后从查看器列表移除并停留在下一张（没有下一张则退出）。
     */
    private fun deleteCurrent() {
        if (externalMode) {
            return
        }
        val item = currentItem ?: return
        val uri = item.uri ?: return
        try {
            val request = MediaStore.createTrashRequest(contentResolver, listOf(uri), true)
            pendingDeleteId = item.id
            trashConsentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            pendingDeleteId = -1L
            Log.e(TAG, "create trash request failed", e)
            Toast.makeText(this, R.string.selection_action_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleteConsentResult(granted: Boolean) {
        val id = pendingDeleteId
        pendingDeleteId = -1L
        if (!granted || id <= 0L) {
            return
        }
        // 收藏联动：媒体进回收站后从「收藏」相册剔除（恢复时由最近删除页清标记）
        FavoriteStore.get(this).markTrashed(listOf(id), true)
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            items.removeAt(index)
        }
        if (items.isEmpty()) {
            finish()
            return
        }
        val nextIndex = index.coerceIn(0, items.size - 1)
        // setItems 内部会触发 onPageChanged -> bindPage，更新标题/副标题/详情内容
        viewer.setItems(ArrayList(items), nextIndex)
        Toast.makeText(this, R.string.selection_deleted_done, Toast.LENGTH_SHORT).show()
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
        // 外部模式不提供详情面板（EXIF / 地图依赖本应用媒体库 id）。
        if (externalMode) {
            return
        }
        // 沉浸模式下先退出沉浸：否则面板打开却不可见，地图等控件仍会接收点击。
        if (immersive) {
            toggleImmersive()
        }
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

    /** 立即收起面板（不播动画）：进入沉浸模式前强制复位，避免残留不可见面板。 */
    private fun closeSheetImmediately() {
        sheetAnimator?.cancel()
        sheetAnimator = null
        setSheetProgress(0f)
        detailsSheet.visibility = View.INVISIBLE
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
        // 外部 URI（合成 id 为负）不属于本应用媒体库：不查 Provider，避免错数据/崩溃。
        if (externalMode || item.id < 0L) {
            return
        }
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
        // 外部 URI 没有本应用媒体库 id，收藏写回无从谈起（入口已隐藏，这里再兜底）。
        if (externalMode || item.id < 0L) {
            return
        }
        val newValue = if (item.isFavorite) 0 else 1
        if (updateFavorite(item.id, newValue)) {
            item.isFavorite = newValue == 1
            updatePageUi()
        }
    }

    /**
     * 写回收藏状态。
     *
     * 收藏保存在**应用内自建库**（Provider → `FavoriteStore`），不再写系统媒体库，
     * 因此不会抛 `RecoverableSecurityException`、也不需要系统授权弹窗。
     */
    private fun updateFavorite(id: Long, newValue: Int): Boolean {
        return try {
            contentResolver.update(
                GalleryContract.mediaUri(id),
                ContentValues().apply {
                    put(GalleryContract.Columns.FAVORITE, newValue)
                },
                null,
                null,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "update favorite failed", e)
            false
        }
    }

    private fun share(item: MediaItem?) {
        val uri = item?.uri ?: return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.gallery_share_title)))
        } catch (e: Exception) {
            // file:// 外部 URI 在 Android 7+ 会被 FileUriExposedException 拦截；外部模式给出的
            // content:// URI 不受影响。分享失败只记日志，不崩溃。
            Log.e(TAG, "share failed for uri=$uri", e)
        }
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
        // 沉浸模式下面板内容可能不参与合成（当前机型实测：可见性/点击判定与绘制不一致），
        // 因此沉浸期间禁止上划开面板；退出沉浸后再按正常路径展开（见 openSheet）。
        viewer.sheetTouchEnabled = !immersive
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = !immersive)
        viewer.setLightBackground(!immersive)
        if (immersive) {
            // 进入沉浸前若面板还开着，先无动画收起，防止留下不可见但可交互的面板
            if (sheetProgress > 0.01f) {
                closeSheetImmediately()
            }
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
        private const val SHEET_HEIGHT_RATIO = 0.52f
        private const val SHEET_SETTLE_THRESHOLD = 0.4f
        private const val SHEET_ANIMATION_DURATION = 200L
        private const val MAP_CARD_ZOOM = 16f

        /** 标题栏「详情」菜单项 id（main-ui 不新增 menu 资源，代码创建）。 */
        private const val MENU_INFO = 0x3001

        fun intent(context: Context, baseUri: Uri, position: Int): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_BASE_URI, baseUri.toString())
                .putExtra(EXTRA_POSITION, position)

        /**
         * 构造**外部大图模式** Intent（仅供 main 的 ExternalEntryActivity 使用）：
         * 直接携带调用方的 URI 列表，不经过本应用媒体库。
         *
         * @param uris content/file URI 列表（至少 1 个）。
         * @param position 初始位置（越界收敛到合法范围）。
         * @param title 顶部标题（可选，缺省用文件名）。
         */
        fun externalIntent(
            context: Context,
            uris: List<Uri>,
            position: Int = 0,
            title: String? = null,
        ): Intent {
            require(uris.isNotEmpty()) { "uris must not be empty" }
            val safePosition = position.coerceIn(uris.indices)
            return Intent(context, ViewerActivity::class.java)
                .putStringArrayListExtra(
                    GalleryExternal.EXTRA_VIEW_URIS,
                    ArrayList(uris.map(Uri::toString)),
                )
                .putExtra(GalleryExternal.EXTRA_VIEW_POSITION, safePosition)
                .apply { title?.let { putExtra(GalleryExternal.EXTRA_TITLE, it) } }
        }
    }
}
