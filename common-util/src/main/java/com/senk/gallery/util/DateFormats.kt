package com.senk.gallery.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object DateFormats {

    private val dateTimeFormat = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

    fun formatDateTime(millis: Long): String =
        if (millis <= 0L) "" else dateTimeFormat.format(Date(millis))

    fun formatDate(millis: Long): String =
        if (millis <= 0L) "" else dateFormat.format(Date(millis))

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
