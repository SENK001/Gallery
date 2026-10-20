package com.senk.gallery.ui.addto

import android.content.Context
import com.senk.gallery.ui.R
import com.senk.gallery.ui.dialog.GalleryDialog

/**
 * 相册名称输入对话框（相册页「新建相册」与「添加到」页新建相册共用）。
 *
 * 用应用统一对话框 [GalleryDialog] 的输入型实现，外观与首启隐私页一致
 * （圆角卡片 + 品牌主色/中性胶囊按钮，见 `styles_dialog.xml`）。
 *
 * 交互约定（与旧实现一致）：
 * - 确定按钮**不自动关闭**：回调返回错误文案时把文案显示在输入框下方并保持对话框打开，
 *   返回 null 表示成功、关闭对话框（重名/非法名不会被吞掉）；
 * - 打开即聚焦输入框并弹出键盘；键盘「完成」等同点确定；
 * - 输入框 hint 作为读屏标签。
 */
object AlbumNameDialog {

    /**
     * @param title 对话框标题。
     * @param initialName 初始名称（重命名场景预填）。
     * @param onDismiss 对话框关闭（确定成功 / 取消 / 点外部返回）时回调，用于复位调用方的忙状态。
     * @param onConfirm 用户点确定时回调；返回 null 表示成功（关闭对话框），
     *   返回非空字符串表示错误文案（显示在输入框下方、对话框保持打开）。
     * @return 已显示的对话框（调用方一般不需要操作）。
     */
    fun show(
        context: Context,
        title: String,
        initialName: String? = null,
        onDismiss: (() -> Unit)? = null,
        onConfirm: (String) -> String?,
    ): GalleryDialog {
        val builder = GalleryDialog.builder(context)
            .setTitle(title)
            .setInput(
                hint = context.getString(R.string.album_name_hint),
                initial = initialName,
                validate = onConfirm,
            )
            .setNegativeButton(context.getString(R.string.album_dialog_cancel))
            .setPositiveButton(context.getString(R.string.album_dialog_confirm))
        if (onDismiss != null) {
            builder.setOnDismiss(onDismiss)
        }
        return builder.show()
    }
}
