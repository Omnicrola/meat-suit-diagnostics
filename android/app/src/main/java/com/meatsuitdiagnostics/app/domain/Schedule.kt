package com.meatsuitdiagnostics.app.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** When check-ins happen. Times are wall-clock times in whatever timezone the phone is in. */
object Schedule {

    /**
     * The first time strictly after [after] that falls on one of [daysOfWeek] (ISO, 1 = Monday) at [timeLocal]
     * ("HH:MM"). If that time doesn't exist because of a daylight-saving jump, it moves forward past the gap.
     */
    fun nextOccurrence(timeLocal: String, daysOfWeek: Set<Int>, after: ZonedDateTime): ZonedDateTime {
        require(daysOfWeek.isNotEmpty()) { "a check-in needs at least one day" }
        val time = LocalTime.parse(timeLocal)
        var date = after.toLocalDate()
        repeat(8) {
            if (date.dayOfWeek.value in daysOfWeek) {
                val candidate = date.atTime(time).atZone(after.zone)
                if (candidate.isAfter(after)) return candidate
            }
            date = date.plusDays(1)
        }
        error("unreachable: some day within 8 must match")
    }

    /** A check-in expires after its window, or when the next occurrence of the same check-in starts. */
    fun expiresAt(
        scheduledFor: ZonedDateTime,
        expiresAfterMinutes: Int,
        timeLocal: String,
        daysOfWeek: Set<Int>,
    ): Instant {
        val windowEnd = scheduledFor.plusMinutes(expiresAfterMinutes.toLong()).toInstant()
        val next = nextOccurrence(timeLocal, daysOfWeek, scheduledFor).toInstant()
        return minOf(windowEnd, next)
    }
}
