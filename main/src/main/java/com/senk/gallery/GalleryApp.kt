package com.senk.gallery

import android.app.Application
import android.util.Log
import com.baidu.mapapi.SDKInitializer

class GalleryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            SDKInitializer.setAgreePrivacy(this, true)
            SDKInitializer.initialize(this)
        } catch (e: Throwable) {
            Log.w(TAG, "百度地图 SDK 初始化失败（请检查 AK 配置）", e)
        }
    }

    companion object {
        private const val TAG = "GalleryApp"
    }
}
