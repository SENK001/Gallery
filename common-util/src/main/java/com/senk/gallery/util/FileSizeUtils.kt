package com.senk.gallery.util

import java.util.Locale

object FileSizeUtils {

    fun format(size: Long): String {
        if (size <= 0L) {
            return "0 B"
        }
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            String.format(Locale.getDefault(), "%d %s", value.toLong(), units[index])
        } else {
            String.format(Locale.getDefault(), "%.2f %s", value, units[index])
        }
    }
}
