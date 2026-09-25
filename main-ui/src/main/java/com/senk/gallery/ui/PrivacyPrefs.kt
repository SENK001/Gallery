package com.senk.gallery.ui

import android.content.Context

/**
 * 隐私政策同意状态。放在 main-ui 是为了让 [BaiduMapSdk] 能直接读取——
 * 主界面的同意页（main 的 `PrivacyActivity`）与 SDK 初始化共用同一份状态。
 *
 * 只有用户在本应用的隐私政策同意页点击「同意」后，[isAgreed] 才为 true；
 * 未同意前不得初始化任何第三方 SDK，也不得进入需要媒体权限的界面。
 */
object PrivacyPrefs {

    private const val FILE = "gallery_privacy"
    private const val KEY_AGREED = "privacy_agreed"

    @Volatile
    var agreed: Boolean = false
        private set

    fun isAgreed(context: Context): Boolean {
        if (agreed) {
            return true
        }
        val granted = context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGREED, false)
        if (granted) {
            agreed = true
        }
        return granted
    }

    fun setAgreed(context: Context) {
        agreed = true
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGREED, true)
            .apply()
    }

    /** 仅用于调试：清除同意状态，使下次启动重新走同意页。 */
    fun reset(context: Context) {
        agreed = false
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
