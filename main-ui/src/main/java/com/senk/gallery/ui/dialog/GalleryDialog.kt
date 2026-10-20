package com.senk.gallery.ui.dialog

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.senk.gallery.ui.R
import com.senk.gallery.ui.theme.ThemeUtils

/**
 * 应用统一的自定义对话框。
 *
 * 为什么不用 [com.google.android.material.dialog.MaterialAlertDialogBuilder]：
 * 系统/Material 默认弹窗的按钮是文字按钮，与本应用首启隐私页的「胶囊按钮」风格不一致；
 * 各页面各写一套布局又会导致圆角、字号、按钮配色漂移。这里用独立 [Dialog] +
 * 自定义圆角卡片布局统一实现，样式见 `styles_dialog.xml`：
 *
 * - 卡片：28dp 圆角、主题 surface 底；
 * - 按钮：左侧「取消」= 中性 tonal 胶囊（同隐私页「退出」），右侧「确认」= 品牌主色实心胶囊
 *   （同隐私页「同意」）；危险操作（删除/清空）确认按钮使用 danger 色；
 * - 支持：仅标题/正文、输入框（带校验错误）、自定义内容视图、列表项、无按钮（进度/内容弹窗）。
 *
 * 使用方式：
 * ```
 * GalleryDialog.builder(context)
 *     .setTitle("删除相册")
 *     .setMessage("确定删除「xxx」吗？")
 *     .setNegativeButton("取消")
 *     .setPositiveButton("删除", destructive = true) { ... }
 *     .show()
 * ```
 *
 * 生命周期约定与普通 Dialog 相同：宿主 Activity/Fragment 销毁前应 dismiss；
 * 需要跟随 View 生命周期时可保存返回的 [GalleryDialog] 并在 `onDestroyView` 里关闭。
 */
class GalleryDialog private constructor(context: Context) : Dialog(context, R.style.Theme_Gallery_Dialog) {

    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var buttonsRow: LinearLayout
    private lateinit var negativeButton: MaterialButton
    private lateinit var positiveButton: MaterialButton
    private lateinit var buttonSpacer: View

    private var positiveAction: ((GalleryDialog) -> Unit)? = null
    private var negativeAction: ((GalleryDialog) -> Unit)? = null
    private var validator: ((String) -> String?)? = null
    private var inputLayout: TextInputLayout? = null
    private var inputEdit: TextInputEditText? = null
    private var focusInputOnShow = false

    init {
        setContentView(LayoutInflater.from(context).inflate(R.layout.dialog_gallery, null))
        titleView = findViewById(R.id.dialog_title)
        messageView = findViewById(R.id.dialog_message)
        contentContainer = findViewById(R.id.dialog_content)
        buttonsRow = findViewById(R.id.dialog_buttons)
        negativeButton = findViewById(R.id.dialog_negative)
        positiveButton = findViewById(R.id.dialog_positive)
        buttonSpacer = findViewById(R.id.dialog_button_spacer)

        positiveButton.setOnClickListener { handlePositiveClick() }
        negativeButton.setOnClickListener {
            negativeAction?.invoke(this)
            dismiss()
        }
        setOnShowListener {
            applyWindowWidth()
            if (focusInputOnShow) {
                inputEdit?.requestFocus()
                // 显式请求 IME：部分机型只设 SOFT_INPUT_STATE_VISIBLE 不会自动弹出
                val edit = inputEdit
                if (edit != null) {
                    // 刚 show 时窗口焦点可能还没就绪，延迟再显式请求一次 IME
                    edit.postDelayed({
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as? InputMethodManager
                        imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                    }, IME_SHOW_DELAY_MS)
                }
            }
        }
    }

    /** 输入型对话框的当前文本；非输入型返回空串。 */
    fun inputText(): String = inputEdit?.text?.toString().orEmpty()

    /** 显示/清除输入框错误提示（输入型对话框）。 */
    fun setInputError(error: CharSequence?) {
        inputLayout?.error = error
    }

    private fun handlePositiveClick() {
        val validator = validator
        if (validator != null) {
            val error = validator(inputText())
            if (error != null) {
                setInputError(error)
                return
            }
        }
        positiveAction?.invoke(this)
        if (isShowing) {
            dismiss()
        }
    }

    private fun applyWindowWidth() {
        val metrics = context.resources.displayMetrics
        val widthPx = ((metrics.widthPixels * WIDTH_RATIO).toInt())
            .coerceAtMost((MAX_WIDTH_DP * metrics.density).toInt())
        window?.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun apply(builder: Builder) {
        builder.title?.let {
            titleView.text = it
            titleView.isVisible = true
        }
        builder.message?.let {
            messageView.text = it
            messageView.isVisible = true
        }
        buildContent(builder)?.let { view ->
            contentContainer.addView(view, contentLayoutParams(view))
            contentContainer.isVisible = true
        }
        applyButtons(builder)
        if (builder.inputHint != null) {
            focusInputOnShow = true
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        positiveAction = builder.positiveAction
        negativeAction = builder.negativeAction
        validator = builder.validator
        setCancelable(builder.cancelable)
        builder.onDismiss?.let { callback ->
            setOnDismissListener { callback() }
        }
    }

    private fun buildContent(builder: Builder): View? = when {
        builder.inputHint != null -> createInputContent(builder)
        builder.contentView != null -> builder.contentView
        builder.items != null -> createListContent(builder)
        else -> null
    }

    /**
     * 内容区布局参数：尊重调用方在自定义内容视图上设置的宽高（如隐私政策正文固定屏高比例、
     * 交给内部 ScrollView 滚动）；未设置时默认占满宽度、高度自适应。
     */
    private fun contentLayoutParams(view: View): FrameLayout.LayoutParams {
        val existing = view.layoutParams
        return if (existing != null) {
            FrameLayout.LayoutParams(existing.width, existing.height)
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun createInputContent(builder: Builder): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_gallery_input, null)
        val layout = view.findViewById<TextInputLayout>(R.id.dialog_input_layout)
        val edit = view.findViewById<TextInputEditText>(R.id.dialog_input)
        layout.hint = builder.inputHint
        val initial = builder.initialInput
        if (!initial.isNullOrEmpty()) {
            edit.setText(initial)
            edit.setSelection(initial.length)
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handlePositiveClick()
                true
            } else {
                false
            }
        }
        inputLayout = layout
        inputEdit = edit
        return view
    }

    private fun createListContent(builder: Builder): View {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val items = builder.items.orEmpty()
        items.forEachIndexed { index, label ->
            val item = LayoutInflater.from(context)
                .inflate(R.layout.dialog_gallery_list_item, list, false) as TextView
            item.text = label
            item.setOnClickListener {
                dismiss()
                builder.onItemClick?.invoke(index)
            }
            list.addView(item)
        }
        return list
    }

    private fun applyButtons(builder: Builder) {
        val positiveText = builder.positiveText
        val negativeText = builder.negativeText
        positiveButton.isVisible = positiveText != null
        negativeButton.isVisible = negativeText != null
        buttonSpacer.isVisible = positiveText != null && negativeText != null
        buttonsRow.isVisible = positiveText != null || negativeText != null
        positiveText?.let { positiveButton.text = it }
        negativeText?.let { negativeButton.text = it }
        if (builder.destructive) {
            positiveButton.backgroundTintList =
                ColorStateList.valueOf(ThemeUtils.brandDanger(context))
            positiveButton.setTextColor(ThemeUtils.brandOnDanger(context))
        }
    }

    class Builder internal constructor(private val context: Context) {

        internal var title: CharSequence? = null
        internal var message: CharSequence? = null
        internal var positiveText: CharSequence? = null
        internal var negativeText: CharSequence? = null
        internal var destructive = false
        internal var cancelable = true
        internal var inputHint: CharSequence? = null
        internal var initialInput: CharSequence? = null
        internal var validator: ((String) -> String?)? = null
        internal var contentView: View? = null
        internal var items: List<CharSequence>? = null
        internal var onItemClick: ((Int) -> Unit)? = null
        internal var positiveAction: ((GalleryDialog) -> Unit)? = null
        internal var negativeAction: ((GalleryDialog) -> Unit)? = null
        internal var onDismiss: (() -> Unit)? = null

        fun setTitle(text: CharSequence?): Builder = apply {
            title = text
        }

        fun setMessage(text: CharSequence?): Builder = apply {
            message = text
        }

        /**
         * 输入型对话框。
         *
         * @param hint 输入框 hint（同时作为读屏标签）。
         * @param initial 初始文本（重命名场景预填）。
         * @param validate 点确认前的校验：返回错误文案则显示在输入框下方并保持弹窗打开；
         *   返回 null 表示有效，继续执行 [setPositiveButton] 的回调并关闭。
         */
        fun setInput(
            hint: CharSequence,
            initial: CharSequence? = null,
            validate: ((String) -> String?)? = null,
        ): Builder = apply {
            inputHint = hint
            initialInput = initial
            validator = validate
        }

        /**
         * 自定义内容区（如进度条、协议正文）。
         *
         * 内容视图由调用方负责尺寸（长文本请自行限制高度并放进 ScrollView）。
         */
        fun setContent(view: View): Builder = apply {
            contentView = view
        }

        /** 列表型对话框（如地图导航应用选择）。点击任意项后自动关闭并回调下标。 */
        fun setItems(items: List<CharSequence>, onItemClick: (Int) -> Unit): Builder = apply {
            this.items = items
            this.onItemClick = onItemClick
        }

        /** 确认按钮（品牌主色实心胶囊；[destructive] = true 时用 danger 色）。 */
        fun setPositiveButton(
            text: CharSequence,
            destructive: Boolean = false,
            onClick: ((GalleryDialog) -> Unit)? = null,
        ): Builder = apply {
            positiveText = text
            this.destructive = destructive
            positiveAction = onClick
        }

        /** 取消按钮（中性 tonal 胶囊），默认只关闭弹窗。 */
        fun setNegativeButton(
            text: CharSequence,
            onClick: ((GalleryDialog) -> Unit)? = null,
        ): Builder = apply {
            negativeText = text
            negativeAction = onClick
        }

        fun setCancelable(cancelable: Boolean): Builder = apply {
            this.cancelable = cancelable
        }

        /** 弹窗关闭（确认/取消/点外部/返回键）后的回调。 */
        fun setOnDismiss(listener: () -> Unit): Builder = apply {
            onDismiss = listener
        }

        fun show(): GalleryDialog {
            val dialog = GalleryDialog(context)
            dialog.apply(this)
            dialog.show()
            return dialog
        }
    }

    companion object {

        /** 弹窗宽度占屏幕比例（与隐私页卡通栏量级一致，宽屏上不超过 [MAX_WIDTH_DP]）。 */
        private const val WIDTH_RATIO = 0.88f
        private const val MAX_WIDTH_DP = 560

        /** 输入型对话框 show 后延迟请求 IME 的时间（等窗口焦点就绪）。 */
        private const val IME_SHOW_DELAY_MS = 120L

        fun builder(context: Context): Builder = Builder(context)
    }
}
