package com.example.senseconnect

import com.example.senseconnect.core.emergency.EmergencyMessageBuilder
import com.example.senseconnect.core.location.LocationFix
import com.example.senseconnect.core.network.RemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class EmergencyLogicTest {

    private val fix = LocationFix(12.9716, 77.5946, 14.6f, 0L, isFresh = true)
    private val time = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 2, 14, 32, 0) }.timeInMillis

    @Test
    fun mapsUrlUsesSixDecimalsAndDotSeparator() {
        assertEquals("https://maps.google.com/?q=12.971600,77.594600", fix.mapsUrl)
    }

    @Test
    fun mapsUrlIsLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // uses ',' as decimal separator
            assertEquals("https://maps.google.com/?q=12.971600,77.594600", fix.mapsUrl)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun messageContainsTemplateLocationAccuracyAndTime() {
        val message = EmergencyMessageBuilder.build("Help me", fix, time, Locale.UK)
        assertTrue(message.startsWith("Help me\n"))
        assertTrue(message.contains(fix.mapsUrl))
        assertTrue(message.contains("±15 m"))
        assertTrue(message.contains("2 Oct 2026, 14:32"))
        assertFalse(message.contains("last known"))
    }

    @Test
    fun staleLocationIsFlagged() {
        val message = EmergencyMessageBuilder.build("Help", fix.copy(isFresh = false), time, Locale.UK)
        assertTrue(message.contains("[last known location]"))
    }

    @Test
    fun missingLocationIsStatedHonestly() {
        val message = EmergencyMessageBuilder.build("Help", null, time, Locale.UK)
        assertTrue(message.contains("My location could not be determined."))
        assertFalse(message.contains("maps.google.com"))
    }

    @Test
    fun blankTemplateFallsBackToDefaultText() {
        val message = EmergencyMessageBuilder.build("   ", null, time, Locale.UK)
        assertTrue(message.startsWith("EMERGENCY: I need help."))
    }

    @Test
    fun emergencyNumberResolvesByCountryWithFallback() {
        val config = RemoteConfig.DEFAULT
        assertEquals("112", config.emergencyNumberFor("in"))
        assertEquals("911", config.emergencyNumberFor("US"))
        assertEquals("999", config.emergencyNumberFor("gb"))
        assertEquals("112", config.emergencyNumberFor("ZZ"))
        assertEquals("112", config.emergencyNumberFor(null))
    }
}
