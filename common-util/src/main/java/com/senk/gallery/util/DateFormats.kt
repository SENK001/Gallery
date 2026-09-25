package com.senk.gallery.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object DateFormats {

    private const val DATE_TIME_PATTERN = "yyyy年M月d日 HH:mm"
    private const val DATE_PATTERN = "yyyy年M月d日"

    // SimpleDateFormat 不是线程安全的，而本对象是进程级单例、会被主线程与解码线程同时调用，
    // 因此按线程各持一份实例（ThreadLocal.withInitial 需要 API 24+，minSdk 36 满足）。
    private val dateTimeFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat(DATE_TIME_PATTERN, Locale.getDefault()) }

    private val dateFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat(DATE_PATTERN, Locale.getDefault()) }

    fun formatDateTime(millis: Long): String =
        if (millis <= 0L) "" else dateTimeFormat.get()!!.format(Date(millis))

    fun formatDate(millis: Long): String =
        if (millis <= 0L) "" else dateFormat.get()!!.format(Date(millis))

    fun formatDuration(millis: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis.coerceAtLeast(0L))
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
