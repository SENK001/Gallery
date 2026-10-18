package com.senk.gallery.ui.selection

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.senk.gallery.data.album.FavoriteStore
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.ui.R
import com.senk.gallery.ui.addto.AddToAlbumActivity
import java.util.ArrayList

/**
 * 多选动作的共享实现：分享 / 收藏 / 添加到 / 删除。
 *
 * 为什么单独抽出来：照片页（Fragment）与相册详情页（Activity）都要这套动作，
 * 差别只在「授权弹窗的结果怎么回来」——因此把需要 ActivityResult 的两处
 * （删除授权、添加到）做成可注入的启动器：宿主用自己的 `registerForActivityResult`
 * 注册，再把结果转回本类的 `on*Result`。
 *
 * 收藏策略（2026-10-19 起）：收藏保存在**应用内自建库**（core-data 的 `FavoriteStore`），
 * 通过 Provider 的 `update(media/{id}, is_favorite)` 写入，**不再修改系统媒体库**。
 * 因此不需要 `MediaStore.createFavoriteRequest` 系统授权弹窗，也不会出现批量收藏失败。
 *
 * 删除策略：`MediaStore.createTrashRequest` 生成待确认的 PendingIntent，授权通过后
 * 系统删除并通知 MediaStore，宿主重新加载。
 */
class SelectionActions(
    private val context: Context,
    private val onExitSelection: () -> Unit,
    private val onReload: () -> Unit,
    private val onMessage: (String) -> Unit,
) {

    /** 宿主注入：启动「移到最近删除」的系统确认弹窗。 */
    var launchTrashConsent: ((IntentSender) -> Unit)? = null

    /** 宿主注入：启动「添加到相册」选择页。 */
    var launchAddToAlbum: ((Intent) -> Unit)? = null

    private var pendingAddCount = 0

    /** 正在等待系统「移到最近删除」确认的收藏 id（确认通过后给收藏库打删除标记）。 */
    private var pendingTrashIds: List<Long> = emptyList()

    /** 分享：单张走 ACTION_SEND，多张走 ACTION_SEND_MULTIPLE；均带读 URI 授权与 ClipData。 */
    fun share(items: List<MediaItem>) {
        val uris = items.mapNotNull { it.uri }
        if (uris.isEmpty()) {
            onMessage(context.getString(R.string.selection_share_empty))
            return
        }
        val mimeType = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val multiple = uris.size > 1
        val intent = Intent(
            if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND,
        ).apply {
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (multiple) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            } else {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
            // 多选分享时系统按 ClipData 逐项授权（EXTRA_STREAM 在 Android 16 上仅作兼容）
            clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        startActivity(
            Intent.createChooser(intent, context.getString(R.string.gallery_share_title)),
        )
    }

    /**
     * 收藏/取消收藏：全部已收藏 → 取消收藏；否则全部收藏。
     *
     * 写入应用内自建收藏库（Provider → `FavoriteStore`），**不触碰系统媒体库**，
     * 因此无系统授权弹窗；逐条写回后统一刷新并退出多选。
     */
    fun favorite(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        val target = if (items.all { it.isFavorite }) 0 else 1
        var changed = 0
        items.forEach { item ->
            try {
                val updated = context.contentResolver.update(
                    GalleryContract.mediaUri(item.id),
                    ContentValues().apply { put(GalleryContract.Columns.FAVORITE, target) },
                    null,
                    null,
                )
                if (updated > 0) {
                    item.isFavorite = target == 1
                    changed++
                }
            } catch (e: Exception) {
                Log.e(TAG, "update favorite failed: ${item.id}", e)
            }
        }
        if (changed == 0) {
            onMessage(context.getString(R.string.selection_action_failed))
            return
        }
        val message = if (target == 1) {
            context.getString(R.string.selection_favorite_done, changed)
        } else {
            context.getString(R.string.selection_unfavorite_done, changed)
        }
        onMessage(message)
        onExitSelection()
        onReload()
    }

    /** 「添加到」：启动添加到相册选择页（album-dev 提供）。 */
    fun addToAlbum(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        pendingAddCount = items.size
        val intent = AddToAlbumActivity.intent(context, items.map { it.id }.toLongArray())
        val launcher = launchAddToAlbum
        if (launcher == null) {
            onMessage(context.getString(R.string.selection_action_failed))
            return
        }
        launcher.invoke(intent)
    }

    /** 删除：走 MediaStore 的「移到最近删除」请求，由系统弹确认框。 */
    fun delete(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        // 必须传 **MediaStore 的真实媒体 URI**（content://media/external/images|video/media/{id}）：
        // MediaStore.createTrashRequest 只接受「带具体 id 的媒体集合 URI」，
        // 传本应用 Provider 的 URI（content://com.senk.gallery.data.provider/media/{id}）会抛
        // IllegalArgumentException: All requested items must be referenced by specific ID。
        val uris = items.mapNotNull { it.uri }
        if (uris.isEmpty()) {
            onMessage(context.getString(R.string.selection_action_failed))
            return
        }
        pendingTrashIds = items.map { it.id }
        try {
            val request = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            launchTrashConsent?.invoke(request.intentSender)
                ?: onMessage(context.getString(R.string.selection_action_failed))
        } catch (e: RecoverableSecurityException) {
            val sender = e.userAction?.actionIntent?.intentSender
            if (sender != null) {
                launchTrashConsent?.invoke(sender)
            } else {
                Log.e(TAG, "trash without consent intent", e)
                onMessage(context.getString(R.string.selection_action_failed))
            }
        } catch (e: Exception) {
            Log.e(TAG, "create trash request failed", e)
            onMessage(context.getString(R.string.selection_action_failed))
        }
    }

    /** 宿主收到「移到最近删除」授权结果后调用。 */
    fun onTrashConsentResult(granted: Boolean) {
        val trashedIds = pendingTrashIds
        pendingTrashIds = emptyList()
        if (!granted) {
            return
        }
        // 收藏库给已删除媒体打删除标记：收藏相册不再统计/展示这些条目
        // （封面不会变灰、点进去也不会空白）。恢复时由最近删除页清除标记。
        FavoriteStore.get(context).markTrashed(trashedIds, true)
        onMessage(context.getString(R.string.selection_deleted_done))
        onExitSelection()
        onReload()
    }

    /** 宿主收到「添加到相册」结果后调用。 */
    fun onAddToAlbumResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            return
        }
        val added = data?.getIntExtra(AddToAlbumActivity.EXTRA_ADDED_COUNT, 0) ?: 0
        val count = if (added > 0) added else pendingAddCount
        onMessage(context.getString(R.string.selection_added_done, count))
        onExitSelection()
    }

    private fun startActivity(intent: Intent) {
        val activity = context as? Activity
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        const val TAG = "SelectionActions"
    }
}
