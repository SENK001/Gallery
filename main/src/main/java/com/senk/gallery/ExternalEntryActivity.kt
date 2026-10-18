package com.senk.gallery

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.senk.gallery.data.provider.ExternalMediaProvider
import com.senk.gallery.external.GalleryExternal
import com.senk.gallery.ui.PrivacyPrefs
import com.senk.gallery.ui.ViewerActivity
import com.senk.gallery.ui.picker.ImagePickerActivity

/**
 * 外部调用的**唯一入口**（`exported=true`，透明窗口，见 main Manifest）。
 *
 * 第三方应用只能经这里使用「选图 / 看大图」两项能力；内部页面（选图页、查看器）全部
 * `exported=false`，因此本 Activity 是隐私与权限的唯一闸门：
 *
 * 1. 校验 action：只接受 [GalleryExternal.ACTION_PICK_IMAGE] / [GalleryExternal.ACTION_VIEW_IMAGE]；
 * 2. 隐私合规：未同意隐私政策 → Toast + `RESULT_CANCELED`，**不读媒体、不初始化三方 SDK**；
 * 3. 选图需要媒体权限（图片/视频/部分选择任一即可），未授权则现场申请，拒绝即取消；
 *    看大图不强制本应用媒体权限——调用方通过 Intent 的 URI 临时授权把待查看 URI 交给我们，
 *    本应用在查看期间保持本 Activity 存活，授权因此持续有效（见 [GalleryExternal] 与 docs/external-api.md）；
 * 4. 路由：
 *    - PICK_IMAGE → [ImagePickerActivity]，把选中的媒体 id 映射为**代理 URI**
 *      （[ExternalMediaProvider.proxyUri]）后，对调用方逐个 `grantUriPermission`，再回传结果；
 *    - VIEW_IMAGE → [ViewerActivity] 的外部大图模式，查看器返回后再结束本页
 *      （不立即 finish，避免调用方授予的 URI 临时授权提前失效）。
 *
 * 关键节点均打 `Log.i(TAG, ...)`，便于 `adb logcat -s GalleryExternal` 验证外部调用链路。
 */
class ExternalEntryActivity : AppCompatActivity() {

    /** 等待媒体权限期间记住的 action（权限回调后继续路由；Activity 重建时随状态恢复）。 */
    private var pendingAction: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it } || hasMediaPermission()
        Log.i(TAG, "media permission result=$result granted=$granted")
        if (granted) {
            route(pendingAction)
        } else {
            Toast.makeText(
                this,
                R.string.external_permission_required,
                Toast.LENGTH_SHORT,
            ).show()
            cancel("media permission denied")
        }
    }

    /** 选图页结果：把「本应用媒体 id」转成「对外代理 URI」并授权给调用方。 */
    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        onPickerResult(result.resultCode, result.data)
    }

    /** 外部查看器：进入时保持本页存活以维持调用方的 URI 临时授权；返回后结束。 */
    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        Log.i(TAG, "viewer returned resultCode=${result.resultCode}")
        setResult(result.resultCode)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        Log.i(
            TAG,
            "onCreate action=$action caller=${callingPackage()} " +
                "launchedFromPackage=$launchedFromPackage",
        )
        // Activity 重建（旋转/系统回收）时不再重复路由：结果会由系统投递回重建后的实例。
        // 只恢复「等待权限中的 action」，等权限回调到达后继续。
        if (savedInstanceState != null) {
            pendingAction = savedInstanceState.getString(KEY_PENDING_ACTION)
            Log.i(TAG, "restored after recreation pendingAction=$pendingAction")
            return
        }
        if (action != GalleryExternal.ACTION_PICK_IMAGE &&
            action != GalleryExternal.ACTION_VIEW_IMAGE
        ) {
            Log.w(TAG, "unsupported action: $action")
            Toast.makeText(this, R.string.external_unknown_action, Toast.LENGTH_SHORT).show()
            cancel("unsupported action: $action")
            return
        }
        if (!PrivacyPrefs.isAgreed(this)) {
            // 未同意隐私政策：不得读取媒体、不得初始化三方 SDK，直接取消。
            Log.w(TAG, "privacy not agreed, cancel $action")
            Toast.makeText(this, R.string.external_privacy_required, Toast.LENGTH_SHORT).show()
            cancel("privacy not agreed")
            return
        }
        route(action)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_ACTION, pendingAction)
    }

    private fun route(action: String?) {
        when (action) {
            GalleryExternal.ACTION_PICK_IMAGE -> routePickImage()
            GalleryExternal.ACTION_VIEW_IMAGE -> routeViewImage()
            else -> cancel("unsupported action: $action")
        }
    }

    // ==================== 选图 ====================

    private fun routePickImage() {
        if (!hasMediaPermission()) {
            // 选图要从系统媒体库读取照片，必须先拿到媒体权限；拒绝则在回调里取消。
            pendingAction = GalleryExternal.ACTION_PICK_IMAGE
            Log.i(TAG, "request media permission for pick")
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ),
            )
            return
        }
        val allowMultiple = intent.getBooleanExtra(GalleryExternal.EXTRA_ALLOW_MULTIPLE, false)
        val rawMax = intent.getIntExtra(GalleryExternal.EXTRA_MAX_COUNT, GalleryExternal.DEFAULT_MAX_COUNT)
        val maxCount = if (rawMax <= 0) GalleryExternal.DEFAULT_MAX_COUNT else rawMax
        val title = intent.getStringExtra(GalleryExternal.EXTRA_TITLE)
        Log.i(
            TAG,
            "start picker allowMultiple=$allowMultiple maxCount=$maxCount title=$title " +
                "caller=${callingPackage()}",
        )
        val pickerIntent = ImagePickerActivity.intent(this, allowMultiple, maxCount, title)
        try {
            pickerLauncher.launch(pickerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "cannot launch picker", e)
            Toast.makeText(this, R.string.external_picker_failed, Toast.LENGTH_SHORT).show()
            cancel("cannot launch picker")
        }
    }

    private fun onPickerResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) {
            cancel("picker canceled resultCode=$resultCode")
            return
        }
        // picker 内部契约：EXTRA_SELECTED_IDS 是权威数据；原始 URI 仅作为兜底。
        val ids = data.getLongArrayExtra(ImagePickerActivity.EXTRA_SELECTED_IDS)
            ?.filter { it > 0L }
            ?.distinct()
            .orEmpty()
        val proxyUris = if (ids.isNotEmpty()) {
            ids.map { ExternalMediaProvider.proxyUri(it) }
        } else {
            // 兜底：picker 只有原始 URI 时，从 content://media/.../{id} 的最后一段解析 id。
            data.getStringArrayListExtra(GalleryExternal.EXTRA_SELECTED_URIS)
                .orEmpty()
                .mapNotNull { raw -> parseMediaId(raw) }
                .distinct()
                .map { ExternalMediaProvider.proxyUri(it) }
        }
        if (proxyUris.isEmpty()) {
            Log.w(TAG, "picker returned no usable media id")
            cancel("picker returned empty selection")
            return
        }
        val caller = callingPackage()
        Log.i(
            TAG,
            "picker result ids=${ids.size} proxies=${proxyUris.size} caller=$caller",
        )
        grantProxyUris(caller, proxyUris)
        val resultIntent = buildPickResult(proxyUris)
        setResult(RESULT_OK, resultIntent)
        Log.i(TAG, "pick forwarded: count=${proxyUris.size} caller=$caller")
        finish()
    }

    /** 对调用方逐个授予代理 URI 的读权限；拿不到调用包名时只记录日志（系统仍会按结果 Intent 授权）。 */
    private fun grantProxyUris(caller: String?, proxyUris: List<Uri>) {
        if (caller.isNullOrEmpty()) {
            Log.w(TAG, "no calling package, skip explicit grantUriPermission")
            return
        }
        proxyUris.forEach { uri ->
            try {
                grantUriPermission(caller, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w(TAG, "grantUriPermission failed caller=$caller uri=$uri", e)
            }
        }
        Log.i(TAG, "granted ${proxyUris.size} proxy uri(s) to $caller")
    }

    /**
     * 构造选图返回 Intent：
     * - 单选：`data` = 代理 URI；
     * - 多选：`ClipData` = 全部代理 URI（与系统选图器一致）；
     * - 均为 EXTRA_SELECTED_URIS（代理 URI 字符串）、EXTRA_SELECTED_COUNT、FLAG_GRANT_READ_URI_PERMISSION。
     */
    private fun buildPickResult(proxyUris: List<Uri>): Intent {
        val result = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putStringArrayListExtra(
                GalleryExternal.EXTRA_SELECTED_URIS,
                ArrayList(proxyUris.map(Uri::toString)),
            )
            putExtra(GalleryExternal.EXTRA_SELECTED_COUNT, proxyUris.size)
        }
        if (proxyUris.size == 1) {
            result.setData(proxyUris[0])
        } else {
            val clip = ClipData.newRawUri(
                getString(R.string.external_selected_uri_label),
                proxyUris[0],
            )
            for (index in 1 until proxyUris.size) {
                clip.addItem(ClipData.Item(proxyUris[index]))
            }
            result.clipData = clip
        }
        return result
    }

    private fun parseMediaId(raw: String): Long? {
        if (raw.isBlank()) {
            return null
        }
        return try {
            val uri = raw.toUri()
            if (uri.scheme != "content") {
                return null
            }
            val parsed = ContentUris.parseId(uri)
            parsed.takeIf { it > 0L }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 看大图 ====================

    private fun routeViewImage() {
        val requested = LinkedHashSet<Uri>()
        intent.data?.let { requested.add(it) }
        intent.getStringArrayListExtra(GalleryExternal.EXTRA_VIEW_URIS)
            .orEmpty()
            .forEach { raw ->
                if (raw.isNotBlank()) {
                    runCatching { raw.toUri() }.getOrNull()?.let { requested.add(it) }
                }
            }
        // 只支持 content:// 与 file://（http(s) 等网络图片不在本功能范围）。
        val uris = requested.filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty()) {
            Log.w(TAG, "no supported view uri, requested=$requested")
            Toast.makeText(this, R.string.external_no_supported_uri, Toast.LENGTH_SHORT).show()
            cancel("no supported view uri")
            return
        }
        val dataUri = intent.data?.takeIf { it in uris }
        val requestedPosition = intent.getIntExtra(GalleryExternal.EXTRA_VIEW_POSITION, 0)
        val position = if (dataUri != null) {
            // 调用方同时给了 data 与列表时，以 data 所在位置为准（与 viewImageIntent 的构造一致）。
            uris.indexOfFirst { it == dataUri }.coerceAtLeast(0)
        } else {
            requestedPosition.coerceIn(uris.indices)
        }
        val title = intent.getStringExtra(GalleryExternal.EXTRA_TITLE)
        val caller = callingPackage()
        Log.i(
            TAG,
            "start viewer in external mode: uris=${uris.size} position=$position " +
                "title=$title caller=$caller",
        )
        val viewerIntent = ViewerActivity.externalIntent(this, ArrayList(uris), position, title)
        try {
            viewerLauncher.launch(viewerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "cannot launch viewer", e)
            Toast.makeText(this, R.string.external_viewer_failed, Toast.LENGTH_SHORT).show()
            cancel("cannot launch viewer")
        }
    }

    // ==================== 公共 ====================

    /**
     * 调用方包名：优先 [getCallingPackage]（第三方 `startActivityForResult` 场景可用），
     * 拿不到时回退到 [launchedFromPackage]（如 `adb shell am start` 时是 `com.android.shell`）。
     */
    private fun callingPackage(): String? =
        getCallingPackage() ?: launchedFromPackage?.takeIf { it.isNotEmpty() }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED

    /** 安全取消：RESULT_CANCELED + finish，调用方按「取消」处理。 */
    private fun cancel(reason: String) {
        Log.i(TAG, "cancel: $reason")
        setResult(RESULT_CANCELED)
        finish()
    }

    private companion object {
        private const val TAG = "GalleryExternal"

        /** Activity 重建时保存「等待媒体权限中」的 action。 */
        private const val KEY_PENDING_ACTION = "pending_action"
    }
}
