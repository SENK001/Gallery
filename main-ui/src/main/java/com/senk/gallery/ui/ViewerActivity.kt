package com.senk.gallery.ui

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.senk.gallery.data.entity.MediaItem
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
    private var exifLocation: Pair<Double, Double>? = null
    private var exifLocationLineIndex = -1
    private var addressResolver: LocationAddressResolver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(Color.WHITE)
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
        applyInsets()
        viewer.onSingleTap = { x, y -> onViewerTap(x, y) }
        viewer.onLongPress = { onViewerLongPress() }
        viewer.onPressEnd = { viewer.stopMotionVideo() }
        viewer.onPageChanged = { index, item ->
            bindPage(index, item)
            maybeLoadMore(index)
        }
        findViewById<ImageButton>(R.id.action_share).setOnClickListener { share(currentItem) }
        favoriteButton.setOnClickListener { toggleFavorite() }
        findViewById<ImageButton>(R.id.action_info).setOnClickListener { showInfo(currentItem) }
        baseUri = intent.getStringExtra(EXTRA_BASE_URI)?.let(Uri::parse) ?: Uri.EMPTY
        initialPosition = intent.getIntExtra(EXTRA_POSITION, 0)
        loadInitial()
    }

    override fun onResume() {
        super.onResume()
        viewer.onResume()
    }

    override fun onPause() {
        viewer.stopMotionVideo()
        viewer.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        addressResolver?.release()
        addressResolver = null
        super.onDestroy()
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
    }

    private fun updatePageUi() {
        val item = currentItem ?: return
        favoriteButton.setImageResource(
            if (item.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        )
    }

    private fun onViewerTap(x: Float, y: Float) {
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
            window.decorView.setBackgroundColor(Color.BLACK)
            rootView.setBackgroundColor(Color.BLACK)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            toolbar.isVisible = true
            bottomBar.isVisible = true
            updatePageUi()
            window.decorView.setBackgroundColor(Color.WHITE)
            rootView.setBackgroundColor(Color.WHITE)
        }
    }

    private fun showInfo(item: MediaItem?) {
        item ?: return
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { buildInfoLines(item) }
            val dialog = MaterialAlertDialogBuilder(this@ViewerActivity)
                .setTitle(R.string.gallery_info_title)
                .setMessage(
                    if (lines.isEmpty()) getString(R.string.gallery_info_no_data)
                    else lines.joinToString("\n"),
                )
                .setPositiveButton(R.string.gallery_info_close, null)
                .show()
            val location = exifLocation
            if (location != null) {
                requestAddress(dialog, lines, location.first, location.second)
            }
        }
    }

    private fun requestAddress(
        dialog: AlertDialog,
        lines: MutableList<String>,
        latitude: Double,
        longitude: Double,
    ) {
        val lineIndex = exifLocationLineIndex
        if (lineIndex !in lines.indices) {
            return
        }
        val messageView = dialog.findViewById<TextView>(android.R.id.message)
        val resolver = addressResolver
            ?: LocationAddressResolver(this).also { addressResolver = it }
        resolver.resolve(latitude, longitude) { address ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                lines[lineIndex] = "位置: ${address ?: "解析失败（请检查百度地图 AK）"}"
                messageView?.text = lines.joinToString("\n")
            }
        }
    }

    private fun buildInfoLines(item: MediaItem): MutableList<String> {
        exifLocation = null
        exifLocationLineIndex = -1
        val lines = ArrayList<String>()
        lines.add("名称: ${item.name ?: "-"}")
        lines.add("类型: ${item.mimeType ?: "-"}")
        lines.add("拍摄时间: ${DateFormats.formatDateTime(item.dateTaken)}")
        lines.add("大小: ${FileSizeUtils.format(item.size)}")
        lines.add("尺寸: ${item.width} x ${item.height}")
        if (item.isVideo && item.duration > 0L) {
            lines.add("时长: ${DateFormats.formatDuration(item.duration)}")
        }
        item.relativePath?.let { lines.add("路径: $it") }
        item.ownerPackage?.let { lines.add("来源应用: $it") }
        if (item.isMotionPhoto) {
            lines.add("动态照片: 是")
        }
        if (item.isPanorama) {
            lines.add("全景: 是")
        }
        readExifLines(item, lines)
        return lines
    }

    private fun readExifLines(item: MediaItem, lines: MutableList<String>) {
        try {
            contentResolver.query(
                GalleryContract.exifUri(item.id),
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return
                }
                val columns = GalleryContract.ExifColumns
                fun value(name: String): String? {
                    val index = cursor.getColumnIndex(name)
                    if (index < 0 || cursor.isNull(index)) {
                        return null
                    }
                    return cursor.getString(index)?.takeIf { it.isNotBlank() }
                }
                value(columns.MAKE)?.let { lines.add("品牌: $it") }
                value(columns.MODEL)?.let { lines.add("型号: $it") }
                value(columns.LENS_MODEL)?.let { lines.add("镜头: $it") }
                formatRational(value(columns.F_NUMBER))?.let { lines.add("光圈: f/$it") }
                value(columns.EXPOSURE_TIME)?.let { lines.add("曝光时间: $it") }
                value(columns.ISO)?.let { lines.add("ISO: $it") }
                formatRational(value(columns.FOCAL_LENGTH))?.let { lines.add("焦距: ${it}mm") }
                formatRational(value(columns.FOCAL_LENGTH_35MM))?.let { lines.add("等效焦距: ${it}mm") }
                value(columns.DATETIME)?.let { lines.add("EXIF 拍摄时间: $it") }
                value(columns.SOFTWARE)?.let { lines.add("软件: $it") }
                val hasLocation = value(columns.HAS_LOCATION) == "1"
                if (hasLocation) {
                    val latitude = cursor.getDouble(cursor.getColumnIndex(columns.LATITUDE))
                    val longitude = cursor.getDouble(cursor.getColumnIndex(columns.LONGITUDE))
                    exifLocation = latitude to longitude
                    exifLocationLineIndex = lines.size
                    lines.add("位置: 解析中…")
                    lines.add("坐标: %.5f, %.5f".format(latitude, longitude))
                }
            }
        } catch (ignored: Exception) {
        }
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

        fun intent(context: Context, baseUri: Uri, position: Int): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_BASE_URI, baseUri.toString())
                .putExtra(EXTRA_POSITION, position)
    }
}
