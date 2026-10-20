package com.senk.gallery.ui

import android.content.Context
import android.util.Log
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.GeoCodeResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.baidu.mapapi.utils.CoordinateConverter

/**
 * EXIF 的 WGS-84 经纬度 -> 文字地址（百度地图 SDK 逆地理编码）。
 *
 * AK（API_KEY）配置在 AndroidManifest.xml 的 `com.baidu.lbsapi.API_KEY` meta-data
 * （由 `main/build.gradle` 从 local.properties 的 `BAIDU_MAP_API_KEY` 注入）；
 * SDK 初始化统一走 [BaiduMapSdk.ensureInitialized]。结果在主线程回调，
 * 未配置 AK/无网络/无结果时回调 null。
 *
 * 2026-10-21：除完整地址字符串外，还返回一级/二级行政区划（省 / 市 / 区），
 * 供查看器标题栏副标题「时间 · 省 市」使用；同一坐标的并发请求（如标题栏与详情地图卡片）
 * 会合并为一次 SDK 请求并依次回调，避免后一次请求覆盖前一次的 pending。
 */
class LocationAddressResolver(context: Context) {

    /** 逆地理编码结果：完整地址 + 行政区划组件（可能部分为空）。 */
    data class Address(
        val formatted: String?,
        val province: String?,
        val city: String?,
        val district: String?,
    )

    private val appContext = context.applicationContext
    private var geoCoder: GeoCoder? = null

    /** 坐标 key -> 已解析结果（只缓存成功结果，失败允许下次重试）。 */
    private val cache = HashMap<String, Address>()

    /** 正在等待同一坐标结果的回调。 */
    private val pendingCallbacks = HashMap<String, MutableList<(Address?) -> Unit>>()
    private val pendingCoords = HashMap<String, Pair<Double, Double>>()

    /** 当前正在请求的坐标 key（GeoCoder 同一时刻只处理一个请求）。 */
    private var activeKey: String? = null

    private val listener = object : OnGetGeoCoderResultListener {
        override fun onGetGeoCodeResult(result: GeoCodeResult?) = Unit

        override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
            val key = activeKey ?: return
            activeKey = null
            val address = if (result != null && result.error == SearchResult.ERRORNO.NO_ERROR) {
                val detail = result.addressDetail
                Address(
                    formatted = result.formattedPoiAddress?.takeIf { it.isNotBlank() }
                        ?: result.address?.takeIf { it.isNotBlank() },
                    province = detail?.province?.takeIf { it.isNotBlank() },
                    city = detail?.city?.takeIf { it.isNotBlank() },
                    district = detail?.district?.takeIf { it.isNotBlank() },
                )
            } else {
                Log.w(TAG, "逆地理编码失败: error=${result?.error}")
                null
            }
            if (address != null && address.hasRegionOrAddress()) {
                cache[key] = address
            }
            pendingCoords.remove(key)
            val callbacks = pendingCallbacks.remove(key).orEmpty()
            callbacks.forEach { callback -> callback(address) }
            startNextIfNeeded()
        }
    }

    private fun Address.hasRegionOrAddress(): Boolean =
        !formatted.isNullOrBlank() || !province.isNullOrBlank() ||
            !city.isNullOrBlank() || !district.isNullOrBlank()

    /** 只要完整地址字符串的便捷入口（详情面板地图卡片等）。 */
    fun resolve(latitude: Double, longitude: Double, onResult: (String?) -> Unit) {
        resolveAddress(latitude, longitude) { address -> onResult(address?.formatted) }
    }

    /** 需要行政区划组件时使用（查看器标题栏副标题）。 */
    fun resolveAddress(
        latitude: Double,
        longitude: Double,
        onResult: (Address?) -> Unit,
    ) {
        val key = keyOf(latitude, longitude)
        cache[key]?.let {
            onResult(it)
            return
        }
        if (!ensureInitialized()) {
            onResult(null)
            return
        }
        pendingCoords[key] = latitude to longitude
        pendingCallbacks.getOrPut(key) { ArrayList() }.add(onResult)
        // 同一坐标已有请求在飞行中：回调已入队，等结果统一派发
        if (activeKey == null) {
            startNextIfNeeded()
        }
    }

    private fun startNextIfNeeded() {
        if (activeKey != null) {
            return
        }
        val key = pendingCallbacks.keys.firstOrNull() ?: return
        val coords = pendingCoords[key] ?: return
        val coder = geoCoder ?: GeoCoder.newInstance().also {
            it.setOnGetGeoCodeResultListener(listener)
            geoCoder = it
        }
        activeKey = key
        val converted = toBd09(coords.first, coords.second)
        val accepted = try {
            coder.reverseGeoCode(ReverseGeoCodeOption().location(converted))
        } catch (e: Exception) {
            Log.w(TAG, "reverseGeoCode 调用失败", e)
            false
        }
        if (!accepted) {
            activeKey = null
            val callbacks = pendingCallbacks.remove(key).orEmpty()
            pendingCoords.remove(key)
            callbacks.forEach { callback -> callback(null) }
            startNextIfNeeded()
        }
    }

    fun release() {
        activeKey = null
        pendingCallbacks.clear()
        pendingCoords.clear()
        cache.clear()
        geoCoder?.destroy()
        geoCoder = null
    }

    /**
     * 委托给 [BaiduMapSdk]（进程内唯一的初始化点，含 `setCoordType(BD09LL)`）。
     */
    private fun ensureInitialized(): Boolean = BaiduMapSdk.ensureInitialized(appContext)

    private fun keyOf(latitude: Double, longitude: Double): String =
        "%.5f,%.5f".format(latitude, longitude)

    companion object {
        private const val TAG = "LocationAddressResolver"

        fun toBd09(latitude: Double, longitude: Double): LatLng {
            val source = LatLng(latitude, longitude)
            return try {
                CoordinateConverter()
                    .from(CoordinateConverter.CoordType.GPS)
                    .coord(source)
                    .convert() ?: source
            } catch (e: Throwable) {
                source
            }
        }
    }
}
