package com.example.senseconnect.core.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormat {

    /** "Just now", "5 min ago", "3 h ago", "Yesterday", "12 Sep". */
    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - timestamp).coerceAtLeast(0)
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} min ago"
            isSameDay(timestamp, now) -> "${TimeUnit.MILLISECONDS.toHours(diff)} h ago"
            isSameDay(timestamp, now - TimeUnit.DAYS.toMillis(1)) -> "Yesterday"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** Section label for history grouping: "Today", "Yesterday", or "Monday, 12 September". */
    fun dayLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String = when {
        isSameDay(timestamp, now) -> "Today"
        isSameDay(timestamp, now - TimeUnit.DAYS.toMillis(1)) -> "Yesterday"
        else -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(timestamp))
    }

    fun clock(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun full(timestamp: Long): String =
        SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    fun greeting(now: Long = System.currentTimeMillis()): String {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
