package com.senk.gallery.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.senk.gallery.util.CoordConverter

object NavigationHelper {

    class NavApp(val label: String, val intent: Intent)

    fun navigationApps(context: Context, latitude: Double, longitude: Double): List<NavApp> {
        val gcj02 = CoordConverter.wgs84ToGcj02(latitude, longitude)
        val bd09 = LocationAddressResolver.toBd09(latitude, longitude)
        val result = ArrayList<NavApp>()
        for (packageName in NAV_PACKAGES) {
            val label = installedLabel(context, packageName) ?: continue
            val intent = when (packageName) {
                GAODE -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "androidamap://navi?sourceApplication=com.senk.gallery" +
                            "&lat=${gcj02.first}&lon=${gcj02.second}&dev=0&style=2",
                    ),
                )
                BAIDU -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "baidumap://map/navi?location=${bd09.latitude},${bd09.longitude}" +
                            "&coord_type=bd09ll&src=android.senk.gallery",
                    ),
                )
                TENCENT -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "qqmap://map/routeplan?type=drive" +
                            "&tocoord=${gcj02.first},${gcj02.second}&referer=com.senk.gallery",
                    ),
                )
                GOOGLE -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=$latitude,$longitude&mode=d"),
                )
                else -> continue
            }
            result.add(NavApp(label, intent.setPackage(packageName)))
        }
        return result
    }

    fun start(context: Context, app: NavApp) {
        try {
            context.startActivity(app.intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.gallery_nav_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun installedLabel(context: Context, packageName: String): String? = try {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private const val GAODE = "com.autonavi.minimap"
    private const val BAIDU = "com.baidu.BaiduMap"
    private const val TENCENT = "com.tencent.map"
    private const val GOOGLE = "com.google.android.apps.maps"

    private val NAV_PACKAGES = listOf(GAODE, BAIDU, TENCENT, GOOGLE)
}
