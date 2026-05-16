package com.averycorp.prismtask.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for
 * [AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs] — the
 * delay-arithmetic helper that drives the per-minute self-rescheduling
 * chain (minute-cadence Phase 2, Path C).
 *
 * Mirrors the [DailyResetWorkerScheduleTest] shape: hand-built `now`
 * instants exercise the boundary-equality and end-of-minute edges that
 * would otherwise drive `setInitialDelay(0)` regression (WorkManager
 * treats zero-delay as "run immediately," collapsing the chain into a
 * tight loop).
 *
 * No timezone pinning needed — the computation is pure
 * `now % 60_000` arithmetic with no `Calendar` / timezone dependency.
 */
class AutomationTimeTickWorkerScheduleTest {

    @Test
    fun midMinute_returnsTimeUntilNextBoundary() {
        // 1_000_000_043_500 % 60_000 = 23_500 → 36_500 ms until next minute.
        // (Anchored to 1_000_000_020_000, which is 16_666_667 * 60_000 — a
        // real minute boundary; 1_000_000_000_000 is *not* on a boundary.)
        val now = 1_000_000_043_500L
        assertEquals(23_500L, now % 60_000L) // sanity
        val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
        assertEquals(36_500L, delay)
    }

    @Test
    fun exactlyOnBoundary_returnsFullMinute() {
        // When `now` lands exactly on a minute boundary, the helper
        // returns 60_000 (a full minute) — never 0. A zero delay would
        // make WorkManager treat the next run as "now" and the chain
        // would degenerate into a busy loop.
        val now = 1_000_000_020_000L // multiple of 60_000
        assertEquals(0L, now % 60_000L) // sanity
        val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
        assertEquals(60_000L, delay)
    }

    @Test
    fun oneMillisecondPastBoundary_returnsAlmostFullMinute() {
        val now = 1_000_000_020_001L
        val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
        assertEquals(59_999L, delay)
    }

    @Test
    fun oneMillisecondBeforeBoundary_returnsOneMs() {
        val now = 1_000_000_079_999L // 59_999ms into a minute
        assertEquals(59_999L, now % 60_000L) // sanity
        val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
        assertEquals(1L, delay)
    }

    @Test
    fun delay_alwaysStrictlyPositive() {
        // Sweep the full [0, 60_000) range — every offset must yield a
        // strictly-positive delay. A zero-delay regression would
        // collapse the self-rescheduling chain into a tight loop.
        val baseMinute = 1_000_000_020_000L
        for (offsetMs in 0L until 60_000L step 137L) {
            val now = baseMinute + offsetMs
            val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
            assertTrue(
                "delay must be strictly positive at offset=$offsetMs; got $delay",
                delay > 0L
            )
            assertTrue(
                "delay must be <= 60_000 at offset=$offsetMs; got $delay",
                delay <= 60_000L
            )
        }
    }

    @Test
    fun consecutiveBoundaryMath_doesNotDriftOverFullCycle() {
        // 60 consecutive boundary advances starting mid-minute should
        // land exactly 60 minutes later — i.e. the helper produces
        // boundaries at exact 60_000ms intervals once the first delay
        // catches up to the boundary.
        var now = 1_000_000_043_500L // 23.5s past the boundary at 1_000_000_020_000
        assertEquals(23_500L, now % 60_000L) // sanity

        // First call brings us to the next boundary (36.5s away → 1_000_000_080_000).
        val firstDelay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
        now += firstDelay
        assertEquals(0L, now % 60_000L) // landed on a boundary

        // Subsequent 60 calls each return exactly one minute.
        repeat(60) {
            val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
            assertEquals(60_000L, delay)
            now += delay
        }

        // 60 * 60_000 ms after landing on the boundary at 1_000_000_080_000.
        val expected = 1_000_000_080_000L + 60L * 60_000L
        assertEquals(expected, now)
    }

    @Test
    fun zeroEpoch_returnsFullMinute() {
        // Defensive: some edge code paths default to 0L. Helper should
        // not divide-by-zero or return a negative value.
        val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(0L)
        assertEquals(60_000L, delay)
    }

    @Test
    fun realWorldMillis_returnsValidDelay() {
        // Smoke test with a realistic 2026-era epoch — guards against
        // any latent overflow in a Long-arithmetic refactor.
        val now = 1_777_777_777_777L // ~ 2026-04-30
        val delay = AutomationTimeTickWorker.computeNextMinuteBoundaryDelayMs(now)
        assertTrue("delay must be > 0; got $delay", delay > 0L)
        assertTrue("delay must be <= 60_000; got $delay", delay <= 60_000L)
        assertEquals((60_000L - now % 60_000L), delay)
    }
}
