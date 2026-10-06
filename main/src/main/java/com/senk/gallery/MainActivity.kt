package com.senk.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.senk.gallery.ui.AlbumsFragment
import com.senk.gallery.ui.BaiduMapSdk
import com.senk.gallery.ui.PhotosFragment
import com.senk.gallery.ui.theme.ThemeUtils
// 标题栏配色定义在 main-ui 模块，必须用库自己的 R 类引用（main 的 R 只含本模块资源）
import com.senk.gallery.ui.R as UiR
import com.senk.gallery.util.SystemBarUtils

class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var toolbar: View
    private lateinit var statusBarBg: View
    private lateinit var scrim: View
    private lateinit var bottomNav: View
    private lateinit var permissionView: View
    private lateinit var navPhotos: View
    private lateinit var navAlbums: View
    private var requestCount = 0
    private var pendingSettingsCheck = false
    private var navBaseMarginBottom = 0

    /** 当前是否在「照片」页（0）。相册页要保持原有的不透明标题栏。 */
    private var onPhotosPage = true

    /** 宫格是否已滚动到「缩略图位于标题栏下方」的状态。 */
    private var gridScrolledPast = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requestCount++
        updatePermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        statusBarBg = findViewById(R.id.status_bar_bg)
        scrim = findViewById(R.id.toolbar_scrim)
        bottomNav = findViewById(R.id.bottom_nav)
        permissionView = findViewById(R.id.permission_view)
        (bottomNav.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            navBaseMarginBottom = it.bottomMargin
        }
        applyOverlayInsets()
        pager = findViewById(R.id.pager)
        navPhotos = findViewById(R.id.nav_photos)
        navAlbums = findViewById(R.id.nav_albums)
        pager.adapter = MainPagerAdapter(this)
        pager.isUserInputEnabled = false
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavSelection(position)
                applyChrome(page = position)
                // 切页后 Fragment 才确保存在，这里兜底补挂滚动回调
                attachGridScrollListener()
            }
        })
        navPhotos.setOnClickListener { selectPage(0) }
        navAlbums.setOnClickListener { selectPage(1) }
        updateNavSelection(0)
        applyChrome(page = 0)
        findViewById<View>(R.id.permission_button).setOnClickListener {
            if (requestCount >= 2 && !hasMediaPermission()) {
                openAppSettings()
            } else {
                requestPermissions()
            }
        }
        updatePermissionState()
        if (!hasMediaPermission()) {
            requestPermissions()
        }
    }

    /**
     * 覆盖层避开系统栏：pager 铺满整个窗口，因此根布局不吃 inset，只给覆盖层加：
     *  · 状态栏底条 = 状态栏高度（白色，让状态栏区域有底色）
     *  · 标题栏用 **marginTop** 让开状态栏（不能用 paddingTop——那会把 toolbar 撑高，
     *    标题在整块里居中后就落到状态栏与内容交界处，看着像被截断）
     *  · 渐变遮罩同样设为「状态栏 + 标题栏」高度
     *  · 胶囊导航用 marginBottom 让开手势条
     */
    private fun applyOverlayInsets() {
        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 注意：改 layoutParams 后必须重新赋值触发 requestLayout，
            // 直接改可变对象不会重新布局（真机实测踩过：遮罩高度不生效）。
            statusBarBg.layoutParams = statusBarBg.layoutParams.apply { height = bars.top }
            (toolbar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                it.topMargin = bars.top
                it.leftMargin = bars.left
                it.rightMargin = bars.right
                toolbar.layoutParams = it
            }
            (scrim.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                it.height = bars.top + toolbarHeightPx()
                scrim.layoutParams = it
            }
            scrim.background = buildScrim(bars.top + toolbarHeightPx())
            (bottomNav.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                it.bottomMargin = navBaseMarginBottom + bars.bottom
                bottomNav.layoutParams = it
            }
            insets
        }
    }

    /**
     * 渐变遮罩：**顶部最深约 55% 黑，向下线性渐隐到全透明**（单段）。
     * 绘制在 `main-ui` 的 `ToolbarScrimDrawable`，与相册详情页共用同一套观感参数。
     */
    private fun buildScrim(totalHeightPx: Int): android.graphics.drawable.Drawable =
        com.senk.gallery.ui.ToolbarScrimDrawable(totalHeightPx)

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
     * 切换两页的顶部样式。
     *
     * - 照片页静止（未滑动）→ **正常显示标题栏**：不透明白底 + 深色标题，
     *   宫格第一行位于标题栏下方（由 PhotosFragment 的 topInset 保证）。
     * - 照片页上划到缩略图位于标题栏下方 → 标题栏变透明，改由渐变遮罩盖住并隐藏标题，
     *   缩略图从遮罩下方滚过。
     * - 相册页 → 始终不透明白底 + 深色标题（保持原样）。
     */
    private fun applyChrome(page: Int) {
        val wasPhotosPage = onPhotosPage
        onPhotosPage = page == 0
        if (!onPhotosPage || onPhotosPage != wasPhotosPage) {
            // 回到照片页时重置滚动态：宫格又停回顶部，此时不该再有遮罩
            gridScrolledPast = false
        }
        applyToolbarAppearance(overGrid = onPhotosPage && gridScrolledPast)
    }

    /**
     * 标题栏背景**始终透明**，静止时靠父容器（`@id/main`）的 chrome 底色显示
     * （`gallery_surface_chrome`，深色模式由 values-night 自动覆盖），
     * 上划时由 `toolbar_scrim` 渐变遮罩接管。
     *
     * 这里只切换文字颜色与遮罩显隐，颜色一律经 [ThemeUtils] 取，
     * 因此深色模式「静止」分支会取到近白色标题，不会近黑字压近黑底。
     */
    private fun applyToolbarAppearance(overGrid: Boolean) {
        val bar = toolbar as? com.google.android.material.appbar.MaterialToolbar
        if (overGrid) {
            // 遮罩是深色：标题转白、状态栏图标转浅色
            bar?.setTitleTextColor(ThemeUtils.textOnScrim(this))
            scrim.isVisible = true
            statusBarBg.visibility = View.INVISIBLE
        } else {
            // 静止：跟随主题的前景色，透出 chrome 底色
            bar?.setTitleTextColor(ThemeUtils.textPrimary(this))
            scrim.isVisible = false
            statusBarBg.visibility = View.VISIBLE
        }
        // 系统栏图标明暗与底色一致：静止为 chrome 底色，上划为深色遮罩
        ThemeUtils.applySystemBarAppearance(
            this,
            if (overGrid) ThemeUtils.mediaScrim(this) else ThemeUtils.surfaceChrome(this),
        )
    }

    /**
     * 宫格滚动到「缩略图确实位于标题栏下方」时，标题栏才切换成半透明渐变遮罩。
     * 有 [GRID_SCROLL_DEAD_ZONE_PX] 的死区，避免刚滑动一点就闪变。
     */
    private fun onGridScrolled(scrollY: Float) {
        if (!onPhotosPage) {
            return
        }
        val scrolledPast = scrollY > GRID_SCROLL_DEAD_ZONE_PX
        if (scrolledPast != gridScrolledPast) {
            gridScrolledPast = scrolledPast
            applyToolbarAppearance(overGrid = scrolledPast)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingSettingsCheck) {
            pendingSettingsCheck = false
            updatePermissionState()
        }
    }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            ),
        )
    }

    private fun selectPage(position: Int) {
        if (pager.currentItem != position) {
            pager.setCurrentItem(position, true)
        } else {
            updateNavSelection(position)
        }
    }

    private fun updateNavSelection(position: Int) {
        navPhotos.isSelected = position == 0
        navAlbums.isSelected = position == 1
    }

    private fun updatePermissionState() {
        val granted = hasMediaPermission()
        permissionView.isVisible = !granted
        pager.isVisible = granted
        bottomNav.isVisible = granted
        if (!granted) {
            toolbar.alpha = 1f
        }
        if (granted) {
            // 权限已由用户授予（即用户已进行过交互），此处提前初始化百度地图 SDK：
            // SDK 鉴权是异步的，首次逆地理编码会因 authtoken 未就绪返回
            // PERMISSION_UNFINISHED（真机实测）。提前预热可让用户真正打开照片详情时鉴权已完成。
            BaiduMapSdk.ensureInitialized(this)
            refreshFragments()
        }
    }

    /**
     * 把宫格滚动回调与刷新指令下发给已存在的 Fragment。
     *
     * 注意：ViewPager2 的 Fragment 是**懒创建**的，`setAdapter` 之后并不会立刻存在，
     * 因此单靠 onCreate 里调用一次会漏掉照片页（真机实测：回调一直是 null，
     * 滚动完全不生效）。这里额外在首帧后补一次，并在 onResume / 切页时兜底。
     */
    private fun refreshFragments() {
        var photosFound = false
        supportFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is PhotosFragment -> {
                    photosFound = true
                    fragment.onGridScroll = { scrollY -> onGridScrolled(scrollY) }
                    fragment.loadPage(reset = true)
                }
                is AlbumsFragment -> fragment.refresh()
            }
        }
        if (!photosFound) {
            // Fragment 还没创建：等首帧布局完成后再试一次
            pager.post { attachGridScrollListener() }
        }
    }

    /** 只挂回调、不触发数据加载，用于懒创建场景下的补挂。 */
    private fun attachGridScrollListener() {
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is PhotosFragment) {
                fragment.onGridScroll = { scrollY -> onGridScrolled(scrollY) }
            }
        }
    }

    private fun openAppSettings() {
        pendingSettingsCheck = true
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private companion object {
        /**
         * 滚动死区：小于它视为「还在顶部」，标题栏保持完全透明（不遮挡宫格顶部），
         * 超过它、缩略图确实位于标题栏下方时，才换成渐变遮罩。
         */
        const val GRID_SCROLL_DEAD_ZONE_PX = 40f
    }
}
