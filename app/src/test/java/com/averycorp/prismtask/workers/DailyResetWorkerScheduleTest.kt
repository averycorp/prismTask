package com.averycorp.prismtask.workers

import com.averycorp.prismtask.util.DayBoundary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-JVM unit tests for [DailyResetWorker.computeNextDelayMs] — the
 * delay-arithmetic helper extracted in PR-A2 of the automated edge-case
 * testing audit.
 *
 * Pinning timezone to UTC for every test that needs timezone determinism;
 * util.DayBoundary uses `Calendar.getInstance()`, which reads the JVM
 * default TimeZone. CI runs UTC-by-default but local dev does not, so
 * we set + restore around each test.
 *
 * Per memory `feedback_repro_first_for_time_boundary_bugs.md`: every
 * scenario hand-builds the "now" instant from a [Calendar] in UTC so
 * the test reads as a structural repro of the boundary edge it's
 * validating.
 */
class DailyResetWorkerScheduleTest {
    private var savedTz: TimeZone? = null

    @Before
    fun pinTimezone() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimezone() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return cal.timeInMillis
    }

    @Test
    fun preBoundary_sameDay_returnsTimeUntilBoundary() {
        // 2026-04-28 23:00 UTC; SoD = 04:00 → next boundary is 2026-04-29 04:00 = 5h away.
        val now = utcMillis(2026, 4, 28, 23, 0)
        val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = 4, now = now)
        assertEquals(5 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun justPastBoundary_returnsAlmostFullDay() {
        // 2026-04-28 04:00:01 UTC; SoD = 04:00 → next boundary is 2026-04-29 04:00.
        // Delay = 24h - 1s.
        val now = utcMillis(2026, 4, 28, 4, 0, 1)
        val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = 4, now = now)
        assertEquals(24 * 60 * 60 * 1000L - 1_000L, delay)
    }

    @Test
    fun exactlyAtBoundary_returnsFullDay() {
        // 2026-04-28 04:00:00 UTC; SoD = 04:00 → boundary "today" already passed
        // (the comparison in DayBoundary.startOfCurrentDay uses strict `<`), so
        // nextBoundary should be the *next* day's start, exactly 24h later.
        val now = utcMillis(2026, 4, 28, 4, 0, 0)
        val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = 4, now = now)
        assertEquals(24 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun minuteOffset_appliesToBoundary() {
        // 2026-04-28 08:00 UTC; SoD = 04:30 → next boundary 2026-04-29 04:30.
        // Delay = 20h30m.
        val now = utcMillis(2026, 4, 28, 8, 0)
        val delay = DailyResetWorker.computeNextDelayMs(
            dayStartHour = 4,
            dayStartMinute = 30,
            now = now
        )
        assertEquals(20L * 60 * 60 * 1000 + 30L * 60 * 1000, delay)
    }

    @Test
    fun midnightSoD_atNoon_returnsHalfDay() {
        // 2026-04-28 12:00 UTC; SoD = 00:00 → next boundary 2026-04-29 00:00 = 12h.
        val now = utcMillis(2026, 4, 28, 12, 0)
        val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = 0, now = now)
        assertEquals(12 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun yearEndCrossover_resolvesToNextYearBoundary() {
        // 2025-12-31 23:00 UTC; SoD = 04:00 → next boundary 2026-01-01 04:00 = 5h away.
        val now = utcMillis(2025, 12, 31, 23, 0)
        val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = 4, now = now)
        assertEquals(5 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun leapDayBoundary_crossesIntoMarch() {
        // 2024-02-29 23:30 UTC (leap year); SoD = 04:00 → next boundary 2024-03-01 04:00.
        // Delay = 4h30m.
        val now = utcMillis(2024, 2, 29, 23, 30)
        val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = 4, now = now)
        assertEquals(4L * 60 * 60 * 1000 + 30L * 60 * 1000, delay)
    }

    @Test
    fun delay_neverNegative() {
        // Defensive: even contrived inputs (zero hour, zero minute, exactly at SoD)
        // must not produce negative delays — `coerceAtLeast(0L)` guards this and a
        // regression here would silently schedule with `setInitialDelay(< 0)` which
        // WorkManager treats as "run now."
        val now = utcMillis(2026, 4, 28, 0, 0, 0)
        val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = 0, now = now)
        assertTrue("delay must be >= 0; got $delay", delay >= 0L)
    }

    @Test
    fun consecutiveBoundaryMath_doesNotDriftOverFullCycle() {
        // After 5 consecutive boundary crossings starting from 2026-04-28 04:00,
        // we should land exactly on 2026-05-03 04:00 — i.e. the helper produces
        // boundaries at exact 24h intervals when no SoD change interferes.
        val sodHour = 4
        var now = utcMillis(2026, 4, 28, 4, 0, 0)
        repeat(5) {
            val delay = DailyResetWorker.computeNextDelayMs(dayStartHour = sodHour, now = now)
            now += delay
        }
        val expected = utcMillis(2026, 5, 3, 4, 0, 0)
        assertEquals(expected, now)
    }

    @Test
    fun helperReturnsSameAsInlineCalculation() {
        // Regression-gate that the extracted helper matches the math the
        // pre-A2 inlined `schedule()` would have produced. If util.DayBoundary
        // semantics ever drift, this test fails before any behavior changes
        // ship.
        val now = utcMillis(2026, 4, 28, 12, 0)
        val expected = (
            DayBoundary.nextBoundary(dayStartHour = 4, now = now, dayStartMinute = 0) - now
            ).coerceAtLeast(0L)
        val actual = DailyResetWorker.computeNextDelayMs(dayStartHour = 4, now = now)
        assertEquals(expected, actual)
    }
}
