package com.senk.gallery.ui

import android.content.Context
import android.util.Log
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

/**
 * 百度地图 SDK 唯一的初始化入口。
 *
 * 任何会创建 `TextureMapView` / `MapView` 或调用逆地理编码的代码路径，
 * **都必须先调用 [ensureInitialized]**，否则 `TextureMapView` 的构造会在
 * `JNIInitializer$InitOptions` 上抛 NullPointerException 直接崩溃
 * （真机实测栈：TextureMapView.<init> → BaiduMap.<init> → BaseMapInner.<init> →
 * MapController.initMapResources → JNIInitializer.isUserTest）。
 *
 * 两点必须在 `initialize` **之前**设置，之后设置无效：
 * - `setAgreePrivacy`：SDK 的使用前提，且必须是用户真实同意后的声明
 *   （见 [PrivacyPrefs]，未同意时 [ensureInitialized] 直接返回 false）；
 * - `setCoordType(BD09LL)`：调用方（`LocationAddressResolver.toBd09()`）送入的是
 *   BD09LL 坐标，而 SDK 默认坐标系是 GCJ-02，不设置会让地图与逆地理编码整体偏移。
 *
 * 初始化点保持**唯一**是刻意的：`SDKInitializer.isInitialized()` 一旦为 true，
 * 后续再调用 `setCoordType` 不会生效，因此坐标类型只能在首次初始化时设定。
 */
object BaiduMapSdk {

    private const val TAG = "BaiduMapSdk"

    /**
     * 幂等。返回 SDK 是否可用。
     *
     * **未同意隐私政策时直接返回 false 且不做任何初始化**——`setAgreePrivacy(true)`
     * 是「代表用户同意」的声明，必须在用户于同意页点击「同意」之后才能调用。
     * 失败只记录日志不抛异常（地图功能降级，应用不崩）。
     */
    fun ensureInitialized(context: Context): Boolean {
        if (!PrivacyPrefs.isAgreed(context)) {
            Log.i(TAG, "用户尚未同意隐私政策，跳过百度地图 SDK 初始化")
            return false
        }
        if (SDKInitializer.isInitialized()) {
            return true
        }
        val app = context.applicationContext
        return try {
            SDKInitializer.setAgreePrivacy(app, true)
            SDKInitializer.setCoordType(CoordType.BD09LL)
            SDKInitializer.initialize(app)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "百度地图 SDK 初始化失败（请检查 AK：local.properties 的 BAIDU_MAP_API_KEY）", e)
            false
        }
    }
}
