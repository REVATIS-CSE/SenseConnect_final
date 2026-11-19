package com.example.senseconnect.core.emergency

import com.example.senseconnect.core.location.LocationFix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Builds the SOS text shared by SMS, the share sheet and the on-screen preview. */
object EmergencyMessageBuilder {

    fun build(template: String, location: LocationFix?, timestamp: Long, locale: Locale = Locale.getDefault()): String {
        val time = SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(timestamp))
        val base = template.trim().ifEmpty { "EMERGENCY: I need help." }
        val locationLine = if (location != null) {
            val accuracy = location.accuracyMeters?.let { " (accuracy ±${it.roundToInt()} m)" }.orEmpty()
            val stale = if (location.isFresh) "" else " [last known location]"
            "My location: ${location.mapsUrl}$accuracy$stale"
        } else {
            "My location could not be determined."
        }
        return "$base\n$locationLine\nTime: $time"
    }
}
