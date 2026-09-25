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
 */
class LocationAddressResolver(context: Context) {

    private val appContext = context.applicationContext
    private var geoCoder: GeoCoder? = null
    private var pending: Pair<String, (String?) -> Unit>? = null
    private val cache = HashMap<String, String>()

    private val listener = object : OnGetGeoCoderResultListener {
        override fun onGetGeoCodeResult(result: GeoCodeResult?) = Unit

        override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
            val (key, callback) = pending ?: return
            pending = null
            val address = if (result != null && result.error == SearchResult.ERRORNO.NO_ERROR) {
                result.formattedPoiAddress?.takeIf { it.isNotBlank() }
                    ?: result.address?.takeIf { it.isNotBlank() }
            } else {
                Log.w(TAG, "逆地理编码失败: error=${result?.error}")
                null
            }
            if (address != null) {
                cache[key] = address
            }
            callback(address)
        }
    }

    fun resolve(latitude: Double, longitude: Double, onResult: (String?) -> Unit) {
        val key = "%.5f,%.5f".format(latitude, longitude)
        cache[key]?.let {
            onResult(it)
            return
        }
        if (!ensureInitialized()) {
            onResult(null)
            return
        }
        val coder = geoCoder ?: GeoCoder.newInstance().also {
            it.setOnGetGeoCodeResultListener(listener)
            geoCoder = it
        }
        val converted = toBd09(latitude, longitude)
        pending = key to onResult
        if (!coder.reverseGeoCode(ReverseGeoCodeOption().location(converted))) {
            pending = null
            onResult(null)
        }
    }

    fun release() {
        pending = null
        cache.clear()
        geoCoder?.destroy()
        geoCoder = null
    }

    /**
     * 委托给 [BaiduMapSdk]（进程内唯一的初始化点，含 `setCoordType(BD09LL)`）。
     */
    private fun ensureInitialized(): Boolean = BaiduMapSdk.ensureInitialized(appContext)

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
