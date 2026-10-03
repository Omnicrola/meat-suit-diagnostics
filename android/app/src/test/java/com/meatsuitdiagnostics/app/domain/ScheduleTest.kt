package com.meatsuitdiagnostics.app.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTest {
    private val zone = ZoneId.of("America/Chicago")
    private val everyDay = (1..7).toSet()

    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)

    @Test
    fun laterToday() {
        // 2026-10-03 is a Saturday.
        assertEquals(at("2026-10-03T21:00"), Schedule.nextOccurrence("21:00", everyDay, at("2026-10-03T08:00")))
    }

    @Test
    fun alreadyPassedTodayMovesToTomorrow() {
        assertEquals(at("2026-10-04T08:00"), Schedule.nextOccurrence("08:00", everyDay, at("2026-10-03T09:00")))
    }

    @Test
    fun exactlyNowIsNotNext() {
        assertEquals(at("2026-10-04T08:00"), Schedule.nextOccurrence("08:00", everyDay, at("2026-10-03T08:00")))
    }

    @Test
    fun skipsToNextAllowedWeekday() {
        // Saturday morning, weekdays only -> Monday.
        assertEquals(at("2026-10-05T08:00"), Schedule.nextOccurrence("08:00", setOf(1, 2, 3, 4, 5), at("2026-10-03T07:00")))
    }

    @Test
    fun sameDayNextWeek() {
        // Saturday after the time, Saturdays only -> next Saturday.
        assertEquals(at("2026-10-10T08:00"), Schedule.nextOccurrence("08:00", setOf(6), at("2026-10-03T09:00")))
    }

    @Test
    fun springForwardGapMovesLater() {
        // 2027-03-14 02:30 doesn't exist in Chicago (clocks jump 02:00 -> 03:00).
        val next = Schedule.nextOccurrence("02:30", everyDay, at("2027-03-14T00:00"))
        assertEquals(at("2027-03-14T03:30"), next)
    }

    @Test
    fun expiresAfterWindow() {
        val scheduled = at("2026-10-03T08:00")
        val expires = Schedule.expiresAt(scheduled, 120, "08:00", everyDay)
        assertEquals(at("2026-10-03T10:00").toInstant(), expires)
    }

    @Test
    fun expiresAtNextOccurrenceIfSooner() {
        // A 2-day window on a daily check-in is cut short by the next day's occurrence.
        val scheduled = at("2026-10-03T08:00")
        val expires = Schedule.expiresAt(scheduled, 48 * 60, "08:00", everyDay)
        assertEquals(at("2026-10-04T08:00").toInstant(), expires)
    }
}
