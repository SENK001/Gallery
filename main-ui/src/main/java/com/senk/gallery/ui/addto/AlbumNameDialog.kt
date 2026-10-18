package com.senk.gallery.ui.addto

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.senk.gallery.ui.R

/**
 * 相册名称输入对话框（相册页「新建相册」与「添加到」页新建相册共用）。
 *
 * 交互约定：
 * - 确定按钮**不自动关闭**：回调返回错误文案时把文案显示在输入框下方并保持对话框打开，
 *   返回 null 表示成功、关闭对话框（这样重名/非法名不会被吞掉）；
 * - 打开即聚焦输入框并弹出键盘；键盘「完成」等同点确定；
 * - 输入框 hint 走 TextInputLayout 的 `android:hint`，读屏可朗读。
 *
 * 放在 `ui.addto` 包是为了不新增跨页面的公共文件（两处调用方各在自己的写权限范围内）。
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
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_album_name, null)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.album_name_input_layout)
        val input = view.findViewById<TextInputEditText>(R.id.album_name_input)

        if (!initialName.isNullOrEmpty()) {
            input.setText(initialName)
            input.setSelection(input.text?.length ?: 0)
        }
        // 用户继续输入时清掉上一次的错误提示
        input.doAfterTextChanged { inputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.album_dialog_cancel, null)
            .setPositiveButton(R.string.album_dialog_confirm, null)
            .create()
        if (onDismiss != null) {
            dialog.setOnDismissListener { onDismiss() }
        }
        // 打开即弹出键盘（不依赖已废弃的 InputMethodManager.showSoftInput 重载）
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        dialog.setOnShowListener {
            val confirm: () -> Unit = {
                val error = onConfirm(input.text?.toString().orEmpty())
                if (error == null) {
                    dialog.dismiss()
                } else {
                    inputLayout.error = error
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    confirm()
                    true
                } else {
                    false
                }
            }
            input.requestFocus()
        }
        dialog.show()
        return dialog
    }
}
