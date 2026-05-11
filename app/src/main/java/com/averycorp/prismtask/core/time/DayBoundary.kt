package com.averycorp.prismtask.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * SoD = Start of Day. A user-configured time (hour/minute) that defines when
 * the "logical day" rolls over. Calendar dates still use midnight.
 *
 * Example: SoD = 4:00 AM
 *   - Instant at 2025-11-15 02:00 local -> calendarDate = Nov 15, logicalDate = Nov 14
 *   - Instant at 2025-11-15 05:00 local -> calendarDate = Nov 15, logicalDate = Nov 15
 *
 * This is the canonical day-boundary API for v1.4+ features (habits, streaks,
 * Today filter, Pomodoro stats, widgets, NLP). The legacy millis-based utility
 * in [com.averycorp.prismtask.util.DayBoundary] is kept for back-compat with
 * hour-only callers and will be migrated incrementally.
 */
object DayBoundary {

    /** Standard calendar date at the user's local zone. Used for due dates, calendar UI. */
    fun calendarDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        instant.atZone(zone).toLocalDate()

    /**
     * Logical date: if current local time is before SoD, treat as the previous calendar
     * date. Used for habit resets, streaks, Today filter, stats, widgets.
     */
    fun logicalDate(
        instant: Instant,
        sodHour: Int,
        sodMinute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): LocalDate {
        val local = instant.atZone(zone)
        val sodToday = local.toLocalDate().atTime(sodHour, sodMinute).atZone(zone)
        return if (local.isBefore(sodToday)) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
    }

    /** The exact [Instant] when the current logical day began. */
    fun logicalDayStart(
        instant: Instant,
        sodHour: Int,
        sodMinute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Instant {
        val logical = logicalDate(instant, sodHour, sodMinute, zone)
        return logical.atTime(sodHour, sodMinute).atZone(zone).toInstant()
    }

    /** The exact [Instant] when the next logical day begins. */
    fun nextLogicalDayStart(
        instant: Instant,
        sodHour: Int,
        sodMinute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Instant =
        logicalDayStart(instant, sodHour, sodMinute, zone)
            .atZone(zone).plusDays(1).toInstant()

    /**
     * For Haiku NLP "remind me at 2 AM" style inputs with no explicit date.
     * Resolves the ambiguous time to the next occurrence that falls inside the
     * current *logical* day (which may span past midnight); if the target
     * already passed in the current logical day, pushes it to the next.
     *
     * Example: now = Nov 15 01:00, SoD = 4 AM, input = "2 AM"
     *   -> logical today = Nov 14, so "2 AM" = Nov 15 02:00 (still inside logical Nov 14)
     *
     * Example: now = Nov 15 10:00, SoD = 4 AM, input = "2 AM"
     *   -> logical today = Nov 15, so "2 AM" = Nov 16 02:00 (next logical day)
     */
    fun resolveAmbiguousTime(
        now: Instant,
        targetHour: Int,
        targetMinute: Int,
        sodHour: Int,
        sodMinute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Instant {
        val logicalStart = logicalDayStart(now, sodHour, sodMinute, zone)
        val logicalEnd = nextLogicalDayStart(now, sodHour, sodMinute, zone)

        val currentLogicalDate = logicalDate(now, sodHour, sodMinute, zone)
        val candidateA = currentLogicalDate
            .atTime(targetHour, targetMinute).atZone(zone).toInstant()
        val candidateB = currentLogicalDate.plusDays(1)
            .atTime(targetHour, targetMinute).atZone(zone).toInstant()

        val withinA = !candidateA.isBefore(logicalStart) && candidateA.isBefore(logicalEnd)
        val withinB = !candidateB.isBefore(logicalStart) && candidateB.isBefore(logicalEnd)

        return when {
            withinA && !candidateA.isBefore(now) -> candidateA
            withinB && !candidateB.isBefore(now) -> candidateB
            else -> {
                // Target has already passed in current logical day -> push to next logical day.
                val nextLogicalStart = logicalEnd
                val nextDate = logicalDate(nextLogicalStart, sodHour, sodMinute, zone)
                val nextCandidate = nextDate
                    .atTime(targetHour, targetMinute).atZone(zone).toInstant()
                val nextEnd = nextLogicalDayStart(nextLogicalStart, sodHour, sodMinute, zone)
                if (!nextCandidate.isBefore(nextLogicalStart) &&
                    nextCandidate.isBefore(nextEnd) &&
                    !nextCandidate.isBefore(now)
                ) {
                    nextCandidate
                } else {
                    nextDate.plusDays(1)
                        .atTime(targetHour, targetMinute).atZone(zone).toInstant()
                }
            }
        }
    }
}
