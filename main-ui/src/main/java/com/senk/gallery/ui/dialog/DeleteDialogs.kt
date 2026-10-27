package com.senk.gallery.ui.dialog

import android.content.Context
import com.senk.gallery.ui.R

/**
 * 已获得 MANAGE_MEDIA 时的应用内删除确认弹窗（替代 MediaStore 系统确认框）。
 *
 * 未获得权限时不会调用这里，仍走系统 `createTrashRequest` / `createDeleteRequest`。
 */
object DeleteDialogs {

    /** 移到最近删除前的确认（danger 按钮）。 */
    fun confirmMoveToTrash(context: Context, count: Int, onConfirm: () -> Unit) {
        GalleryDialog.builder(context)
            .setTitle(context.getString(R.string.media_delete_title))
            .setMessage(context.getString(R.string.media_delete_move_message, count))
            .setNegativeButton(context.getString(R.string.media_delete_cancel))
            .setPositiveButton(
                context.getString(R.string.media_delete_confirm),
                destructive = true,
            ) { onConfirm() }
            .show()
    }

    /** 彻底删除（不可恢复）前的确认（danger 按钮）。 */
    fun confirmPermanentDelete(context: Context, count: Int, onConfirm: () -> Unit) {
        GalleryDialog.builder(context)
            .setTitle(context.getString(R.string.media_delete_title))
            .setMessage(context.getString(R.string.media_delete_permanent_message, count))
            .setNegativeButton(context.getString(R.string.media_delete_cancel))
            .setPositiveButton(
                context.getString(R.string.media_delete_confirm),
                destructive = true,
            ) { onConfirm() }
            .show()
    }
}
