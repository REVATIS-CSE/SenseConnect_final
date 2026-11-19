package com.example.senseconnect

import com.example.senseconnect.core.ui.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class TimeFormatTest {

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 2, 15, 0, 0) }.timeInMillis

    @Test
    fun relativeTimes() {
        assertEquals("Just now", TimeFormat.relative(now - 20_000, now))
        assertEquals("5 min ago", TimeFormat.relative(now - TimeUnit.MINUTES.toMillis(5), now))
        assertEquals("3 h ago", TimeFormat.relative(now - TimeUnit.HOURS.toMillis(3), now))
        assertEquals("Yesterday", TimeFormat.relative(now - TimeUnit.HOURS.toMillis(20), now))
    }

    @Test
    fun futureTimestampsDoNotProduceNegativeValues() {
        assertEquals("Just now", TimeFormat.relative(now + 60_000, now))
    }

    @Test
    fun dayLabels() {
        assertEquals("Today", TimeFormat.dayLabel(now - TimeUnit.HOURS.toMillis(2), now))
        assertEquals("Yesterday", TimeFormat.dayLabel(now - TimeUnit.DAYS.toMillis(1), now))
    }

    @Test
    fun sameDayComparison() {
        assertTrue(TimeFormat.isSameDay(now, now - TimeUnit.HOURS.toMillis(10)))
        assertFalse(TimeFormat.isSameDay(now, now - TimeUnit.HOURS.toMillis(16)))
    }
}
