package com.senk.gallery.ui.util

import android.app.AppOpsManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * MANAGE_MEDIA（免系统确认修改/删除媒体）能力检查与直接操作。
 *
 * 为什么需要它：普通应用删除 MediaStore 里不属于自己的媒体时，只能用
 * `createTrashRequest` / `createDeleteRequest` 拉起系统确认框。持有 MANAGE_MEDIA 后
 * 可以直接 update `is_trashed` / delete，从而由应用自己的 [GalleryDialog] 确认后静默完成。
 *
 * 注意：MANAGE_MEDIA 是 **signature/privileged** 受限权限，普通安装无法通过运行时弹窗或
 * `pm grant` 授予（真机验证会报 "not a changeable permission type"）。但 Android 的
 * MANAGE_MEDIA **appop** 单独 allow（`adb shell cmd appops set <pkg> MANAGE_MEDIA allow`）时，
 * MediaProvider 的系统确认界面会直接放行——此时也认为具备「免确认」能力：
 * 先弹应用自定义确认弹窗，直接 update 若被 MediaProvider 拒绝则回退系统 request（不再显示确认框）。
 */
object MediaManageAccess {

    private const val TAG = "MediaManageAccess"

    /** 最近删除保留 30 天（与系统回收站一致）。 */
    private const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 是否具备「删除不弹系统确认框」的能力：
     * 真正持有 MANAGE_MEDIA 权限，或 MANAGE_MEDIA appop 为 allow。
     */
    fun isGranted(context: Context): Boolean {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.MANAGE_MEDIA,
        ) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) {
            return true
        }
        return try {
            // OPSTR_MANAGE_MEDIA 在 SDK 里是 @hide，只能用实际 appop 字符串
            val appOps = context.getSystemService(AppOpsManager::class.java)
            appOps?.unsafeCheckOpNoThrow(
                "android:manage_media",
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "check manage media appop failed", e)
            false
        }
    }

    /** 直接移到最近删除（失败返回 false，调用方应回退系统确认框或提示失败）。 */
    fun moveToTrash(context: Context, uris: List<Uri>): Boolean =
        updateTrashed(context, uris, trashed = true)

    /** 直接从最近删除恢复（失败返回 false）。 */
    fun restoreFromTrash(context: Context, uris: List<Uri>): Boolean =
        updateTrashed(context, uris, trashed = false)

    /** 直接彻底删除（失败返回 false）。 */
    fun deletePermanently(context: Context, uris: List<Uri>): Boolean {
        if (uris.isEmpty()) {
            return false
        }
        return try {
            uris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "delete permanently failed", e)
            false
        }
    }

    private fun updateTrashed(context: Context, uris: List<Uri>, trashed: Boolean): Boolean {
        if (uris.isEmpty()) {
            return false
        }
        val expires = if (trashed) {
            System.currentTimeMillis() + TRASH_RETENTION_MS
        } else {
            0L
        }
        return try {
            uris.forEach { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
                    put(MediaStore.MediaColumns.DATE_EXPIRES, expires)
                }
                context.contentResolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "update is_trashed failed (trashed=$trashed)", e)
            false
        }
    }
}
