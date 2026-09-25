package com.senk.gallery.util

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemBarUtils {

    /**
     * Forces the status/navigation bar icon appearance so that icons stay visible on the
     * activity background. [lightBackground] = true means dark icons for a light background.
     */
    fun applyLightBackgroundAppearance(activity: Activity, lightBackground: Boolean) {
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = lightBackground
        controller.isAppearanceLightNavigationBars = lightBackground
    }
}
