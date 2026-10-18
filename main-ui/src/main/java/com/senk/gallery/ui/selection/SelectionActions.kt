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
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.data.provider.GalleryContract
import com.senk.gallery.ui.R
import com.senk.gallery.ui.addto.AddToAlbumActivity
import java.util.ArrayList

/**
 * 多选动作的共享实现：分享 / 收藏 / 添加到 / 删除。
 *
 * 为什么单独抽出来：照片页（Fragment）与相册详情页（Activity）都要这套动作，
 * 差别只在「授权弹窗的结果怎么回来」——因此把需要 ActivityResult 的三处
 * （收藏授权、删除授权、添加到）做成可注入的启动器：
 * 宿主用自己的 `registerForActivityResult` 注册，再把结果转回本类的 `on*Result`。
 *
 * 批量写回 Provider 的策略（与查看器单张收藏一致，见 ViewerActivity）：
 * - 收藏：直接逐条 `contentResolver.update(mediaUri, is_favorite)`；
 * - 遇到 [RecoverableSecurityException] → 用 `MediaStore.createFavoriteRequest` 申请授权，
 *   授权通过后重试剩余条目；
 * - 删除：`MediaStore.createTrashRequest` 生成待确认的 PendingIntent，授权通过后 Provider
 *   会通知变更、宿主重新加载（同一套流程兼容 Android 16 的「移到最近删除」确认弹窗）。
 */
class SelectionActions(
    private val context: Context,
    private val onExitSelection: () -> Unit,
    private val onReload: () -> Unit,
    private val onMessage: (String) -> Unit,
) {

    /** 宿主注入：启动「移到最近删除」的系统确认弹窗。 */
    var launchTrashConsent: ((IntentSender) -> Unit)? = null

    /** 宿主注入：启动「收藏」的系统授权弹窗。 */
    var launchFavoriteConsent: ((IntentSender) -> Unit)? = null

    /** 宿主注入：启动「添加到相册」选择页。 */
    var launchAddToAlbum: ((Intent) -> Unit)? = null

    private var pendingFavoriteIds: LongArray = LongArray(0)
    private var pendingFavoriteItems: List<MediaItem> = emptyList()
    private var pendingFavoriteValue = 1
    private var pendingAddCount = 0

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
     * 逐条写回 Provider，遇授权异常时转系统授权弹窗。
     */
    fun favorite(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        val target = if (items.all { it.isFavorite }) 0 else 1
        pendingFavoriteItems = items
        pendingFavoriteValue = target
        val ids = items.map { it.id }.toLongArray()
        pendingFavoriteIds = ids
        when (val pending = writeFavorite(ids, target)) {
            null -> finishFavorite(items, target)
            else -> requestFavoriteConsent(pending, target)
        }
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
        if (!granted) {
            return
        }
        onMessage(context.getString(R.string.selection_deleted_done))
        onExitSelection()
        onReload()
    }

    /** 宿主收到「收藏授权」结果后调用：授权通过则重试剩余条目。 */
    fun onFavoriteConsentResult(granted: Boolean) {
        if (!granted) {
            return
        }
        when (val pending = writeFavorite(pendingFavoriteIds, pendingFavoriteValue)) {
            null -> finishFavorite(pendingFavoriteItems, pendingFavoriteValue)
            else -> onMessage(context.getString(R.string.selection_action_failed))
        }
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

    /**
     * 逐条写回收藏状态。
     *
     * @return null = 全部成功；否则返回需要系统授权的剩余 id（空数组表示普通失败）。
     */
    private fun writeFavorite(ids: LongArray, value: Int): LongArray? {
        for (index in ids.indices) {
            try {
                context.contentResolver.update(
                    GalleryContract.mediaUri(ids[index]),
                    ContentValues().apply { put(GalleryContract.Columns.FAVORITE, value) },
                    null,
                    null,
                )
            } catch (e: RecoverableSecurityException) {
                return ids.copyOfRange(index, ids.size)
            } catch (e: Exception) {
                Log.e(TAG, "update favorite failed: ${ids[index]}", e)
                return LongArray(0)
            }
        }
        return null
    }

    private fun requestFavoriteConsent(ids: LongArray, value: Int) {
        val launcher = launchFavoriteConsent
        if (ids.isEmpty() || launcher == null) {
            onMessage(context.getString(R.string.selection_action_failed))
            return
        }
        // 同删除：createFavoriteRequest 只接受 MediaStore 真实媒体 URI（不能用本应用 Provider URI）。
        val uris = ids.toList().mapNotNull { id ->
            pendingFavoriteItems.firstOrNull { it.id == id }?.uri
        }
        if (uris.isEmpty()) {
            onMessage(context.getString(R.string.selection_action_failed))
            return
        }
        try {
            val request = MediaStore.createFavoriteRequest(
                context.contentResolver,
                uris,
                value == 1,
            )
            launcher.invoke(request.intentSender)
        } catch (e: Exception) {
            Log.e(TAG, "create favorite request failed", e)
            onMessage(context.getString(R.string.selection_action_failed))
        }
    }

    private fun finishFavorite(items: List<MediaItem>, value: Int) {
        // 本地对象同步收藏状态，后续再做「取消收藏」时判断才是对的
        items.forEach { it.isFavorite = value == 1 }
        val message = if (value == 1) {
            context.getString(R.string.selection_favorite_done, items.size)
        } else {
            context.getString(R.string.selection_unfavorite_done, items.size)
        }
        onMessage(message)
        onExitSelection()
        onReload()
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
