package com.senk.gallery.debug

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.external.GalleryExternal

/**
 * **仅 debug 构建**：模拟第三方应用调用「相册」的外部能力，用于真机验证
 * `ExternalEntryActivity` → picker / viewer → 结果回传 → 代理 URI 可读 的完整链路。
 *
 * 启动方式（debug APK）：
 * ```
 * adb shell am start -n com.senk.gallery/.debug.ExternalClientDebugActivity \
 *   --es mode pick --ez allow_multiple true   # 选图（多选）
 * adb shell am start -n com.senk.gallery/.debug.ExternalClientDebugActivity \
 *   --es mode pick                            # 选图（单选）
 * adb shell am start -n com.senk.gallery/.debug.ExternalClientDebugActivity \
 *   --es mode view                            # 用本应用媒体库第一张图走 VIEW_IMAGE
 * ```
 *
 * 结果同时打印到 logcat（TAG=`ExternalClientDebug`）与页面文本；release 不含本类。
 */
class ExternalClientDebugActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val lines = StringBuilder()

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        append("picker resultCode=${result.resultCode}")
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            append("picker canceled / no data")
            return@registerForActivityResult
        }
        val uris = data.getStringArrayListExtra(GalleryExternal.EXTRA_SELECTED_URIS)
            ?.mapNotNull { runCatching { it.toUri() }.getOrNull() }
            ?: listOfNotNull(data.data)
        val count = data.getIntExtra(
            GalleryExternal.EXTRA_SELECTED_COUNT,
            uris.size,
        )
        append("selectedCount=$count uris=${uris.size}")
        // 真实调用方拿到结果后应立刻读取（临时授权不保证长期有效）。
        uris.forEachIndexed { index, uri -> verifyReadable(index, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 13f
            setPadding(32, 32, 32, 32)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val scroll = ScrollView(this).apply { addView(container) }
        setContentView(scroll)
        append("ExternalClientDebug: mode=${mode()}")
        when (mode()) {
            MODE_VIEW -> startViewImage()
            else -> startPick()
        }
    }

    private fun mode(): String = intent.getStringExtra(EXTRA_MODE) ?: MODE_PICK

    // ==================== 选图 ====================

    private fun startPick() {
        val allowMultiple = intent.getBooleanExtra(GalleryExternal.EXTRA_ALLOW_MULTIPLE, true)
        val maxCount = intent.getIntExtra(GalleryExternal.EXTRA_MAX_COUNT, 5)
        val pickIntent = GalleryExternal.pickImageIntent(
            allowMultiple = allowMultiple,
            maxCount = maxCount,
            title = "外部调用测试",
        ).setPackage(packageName)
        append("start picker allowMultiple=$allowMultiple maxCount=$maxCount")
        try {
            pickLauncher.launch(pickIntent)
        } catch (e: ActivityNotFoundException) {
            append("launch picker failed: $e")
        }
    }

    /** 用代理 URI 验证：query 元数据 + openInputStream 实际读字节。 */
    private fun verifyReadable(index: Int, uri: Uri) {
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Provider 返回的列集合可能多于投影，按列名取值（不要用下标）
                    fun stringOf(name: String): String? {
                        val index = cursor.getColumnIndex(name)
                        return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                    }

                    fun longOf(name: String): Long {
                        val index = cursor.getColumnIndex(name)
                        return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
                    }

                    append(
                        "uri[$index] query ok: name=${stringOf(MediaStore.MediaColumns.DISPLAY_NAME)} " +
                            "mime=${stringOf(MediaStore.MediaColumns.MIME_TYPE)} " +
                            "size=${longOf(MediaStore.MediaColumns.SIZE)}",
                    )
                } else {
                    append("uri[$index] query empty: $uri")
                }
            }
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                input.read(buffer).coerceAtLeast(0)
            } ?: -1
            append("uri[$index] openInputStream ok: firstReadBytes=$bytes uri=$uri")
        } catch (e: Exception) {
            Log.e(TAG, "READ FAILED uri=$uri", e)
            append("uri[$index] READ FAILED: $e uri=$uri")
        }
    }

    // ==================== 看大图 ====================

    private fun startViewImage() {
        val mediaUri = firstMediaUri()
        if (mediaUri == null) {
            append("no media in this app's MediaStore view")
            return
        }
        val viewIntent = GalleryExternal.viewImageIntent(listOf(mediaUri), position = 0)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPackage(packageName)
        append("start VIEW_IMAGE: $mediaUri")
        try {
            startActivity(viewIntent)
        } catch (e: ActivityNotFoundException) {
            append("launch viewer failed: $e")
        }
    }

    private fun firstMediaUri(): Uri? = try {
        contentResolver.query(
            GalleryContract.withPage(GalleryContract.MEDIA_URI, 1, 0),
            null,
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(GalleryContract.Columns.URI)
                cursor.getString(index)?.let(Uri::parse)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        append("query first media failed: $e")
        null
    }

    private fun append(message: String) {
        Log.i(TAG, message)
        lines.append(message).append('\n')
        if (::status.isInitialized) {
            status.text = lines.toString()
        }
    }

    companion object {
        private const val TAG = "ExternalClientDebug"
        private const val EXTRA_MODE = "mode"
        private const val MODE_PICK = "pick"
        private const val MODE_VIEW = "view"
    }
}
