package com.senk.gallery.util

import android.content.Context
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.WindowManager

object DisplayUtils {

    fun dp2px(context: Context, dp: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )

    fun px2dp(context: Context, px: Float): Float {
        val density = context.resources.displayMetrics.density
        return if (density == 0f) 0f else px / density
    }

    fun screenWidth(context: Context): Int = metrics(context).widthPixels

    fun screenHeight(context: Context): Int = metrics(context).heightPixels

    fun density(context: Context): Float = metrics(context).density

    @Suppress("DEPRECATION")
    private fun metrics(context: Context): DisplayMetrics {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return context.resources.displayMetrics
        }
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        manager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
