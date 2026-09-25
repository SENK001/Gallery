package com.senk.gallery

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.senk.gallery.ui.PrivacyPrefs
import com.senk.gallery.util.SystemBarUtils

/**
 * 首次启动的隐私政策同意页（**全屏页**，非对话框弹窗）。
 *
 * 结构对齐系统应用（MIUI/HyperOS）首启同意页：
 * 顶部居中应用标识 + 标题 -> 欢迎说明 -> 逐条列出将要申请的权限 ->
 * 底部「隐私」标识与分割线 -> 同意声明（「用户协议与隐私政策」为可点链接）-> 底部「退出 / 同意」。
 *
 * 行为：
 * - 未同意：显示本页；按返回键或点「退出」直接离开应用（不进入主界面、不记录同意）。
 * - 点「同意」：写入同意状态 -> 进入 [MainActivity]。
 * - 已同意（非首次启动）：立即转发到 [MainActivity] 并结束本页，用户无感知。
 * - 点「用户协议与隐私政策」：弹出完整政策正文。
 */
class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PrivacyPrefs.isAgreed(this)) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_privacy)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)

        val root = findViewById<View>(R.id.privacy_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPaddingRelative(
                view.paddingStart,
                bars.top,
                view.paddingEnd,
                bars.bottom,
            )
            insets
        }

        findViewById<TextView>(R.id.privacy_agree).setOnClickListener {
            PrivacyPrefs.setAgreed(this)
            goToMain()
        }
        findViewById<TextView>(R.id.privacy_disagree).setOnClickListener {
            // 不同意：退出应用，不进入主界面、不记录同意。
            finishAffinity()
        }

        bindPolicyLink(findViewById(R.id.privacy_statement))
    }

    /** 把同意声明中的「用户协议与隐私政策」做成可点链接。 */
    private fun bindPolicyLink(statement: TextView) {
        val full = getString(R.string.privacy_statement)
        val link = getString(R.string.privacy_link_text)
        val start = full.indexOf(link)
        if (start < 0) {
            statement.text = full
            return
        }
        val span = SpannableString(full)
        span.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = showPolicy()
            },
            start,
            start + link.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        statement.text = span
        statement.movementMethod = LinkMovementMethod.getInstance()
        statement.highlightColor = android.graphics.Color.TRANSPARENT
    }

    private fun showPolicy() {
        val view = layoutInflater.inflate(R.layout.dialog_privacy_policy, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        view.findViewById<View>(R.id.privacy_policy_close).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            // 弹窗高度受限于屏幕可用高度，正文超出时由 ScrollView 滚动。
            // 注意：不要改 window 背景，否则会丢掉 AlertDialog 默认的圆角白卡。
            dialog.window?.let { w ->
                val metrics = resources.displayMetrics
                w.setLayout(
                    (metrics.widthPixels * 0.9f).toInt(),
                    (metrics.heightPixels * 0.8f).toInt(),
                )
            }
        }
        dialog.show()
    }

    private fun goToMain() {
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
