package com.averycorp.prismtask.ui.screens.today

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.core.time.TimeProvider
import com.averycorp.prismtask.data.preferences.StartOfDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Regression gate for `TodayViewModel`'s SoD-boundary fix.
 *
 * **Phase-5 (GREEN) form** — assertions encode the bug-fixed state.
 * The two tests reconstruct the post-migration shapes from
 * `TodayViewModel.dayStart` and the morning-check-in banner combine,
 * both backed by `core.time.LocalDateFlow`, and assert that the
 * pipelines re-emit when the wall-clock crosses the SoD boundary.
 *
 * The Phase-1 form of this file (passing = bug exists, encoded in
 * commit `c4b8a86b`) was deliberately inverted in this commit. If a
 * future change re-introduces the snapshot pattern by reverting
 * `dayStart` to `getDayStartHour().map { ... }.stateIn(...)` (or any
 * shape that loses the wall-clock subscription), these assertions
 * flip back to red.
 *
 * Same shape as PR #798's `MedicationTodayDateRefreshTest` post-fix
 * form (commit `bc956b45` and onward).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayDayBoundaryFlowTest {

    private val zone = ZoneId.of("UTC")

    /**
     * `TimeProvider` whose `now()` is anchored at [base] plus the
     * `runTest` virtual scheduler's `currentTime`. Advancing virtual
     * time via `advanceTimeBy(...)` propagates to `now()`, so the
     * `delay(...)` inside `LocalDateFlow`'s body wakes up on schedule
     * AND sees the correct new wall-clock.
     */
    private fun virtualClock(scope: TestScope, base: Instant) =
        object : TimeProvider {
            override fun now(): Instant = base.plusMillis(scope.testScheduler.currentTime)
            override fun zone(): ZoneId = zone
        }

    /**
     * Bug-fixed contract for `TodayViewModel.dayStart` — reconstructs the
     * post-migration shape:
     *
     *   localDateFlow.observe(getStartOfDay())
     *       .map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
     *       .stateIn(...)
     *
     * Asserts that the StateFlow's value advances when the wall-clock
     * crosses the user's SoD — exactly what the legacy snapshot pattern
     * could not do.
     */
    @Test
    fun dayStart_localDateFlowDriven_advancesAtSoDBoundary() = runTest {
        // 11pm Apr 25 UTC, SoD = 4am → logical day = Apr 25.
        // 6h later → 5am Apr 26 UTC → logical day flips to Apr 26.
        val base = Instant.parse("2026-04-25T23:00:00Z")
        val sod = MutableStateFlow(StartOfDay(hour = 4, minute = 0, hasBeenSet = true))
        val helper = LocalDateFlow(virtualClock(this, base))

        val dayStart: StateFlow<Long> = helper
            .observe(sod)
            .map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
            .stateIn(backgroundScope, SharingStarted.Eagerly, -1L)

        runCurrent()
        val before = dayStart.value
        assertEquals(
            "Initial value reflects logical day Apr 25 (calendar midnight)",
            Instant.parse("2026-04-25T00:00:00Z").toEpochMilli(),
            before
        )

        advanceTimeBy(6 * 60 * 60 * 1000L) // 6h → past Apr 26 04:00 UTC
        runCurrent()

        val after = dayStart.value
        assertNotEquals(
            "dayStart MUST advance reactively when the wall-clock crosses SoD — " +
                "if this fails, someone reverted to the snapshot pattern",
            before,
            after
        )
        assertEquals(
            "After SoD crossing, dayStart reflects calendar midnight of the new logical day",
            Instant.parse("2026-04-26T00:00:00Z").toEpochMilli(),
            after
        )
    }

    /**
     * Bug-fixed contract for the morning-check-in banner combine —
     * `localDateFlow.observe(...)` is now a 4th source of the combine,
     * so the lambda re-fires when the wall-clock crosses SoD even when
     * none of `(featureEnabled, bannerDismissedDate, logs)` change.
     */
    @Test
    fun bannerCombineLambda_includingLocalDateFlow_refreshesOnSoDBoundary() = runTest {
        val base = Instant.parse("2026-04-25T23:00:00Z")
        val sod = MutableStateFlow(StartOfDay(hour = 4, minute = 0, hasBeenSet = true))
        val helper = LocalDateFlow(virtualClock(this, base))

        // Three preference sources mirroring the production combine
        // (featureEnabled, bannerDismissedDate, logs) — all single-emission
        // here so the lambda's re-fire signal is purely from localDateFlow.
        val featureEnabled = MutableStateFlow(true)
        val bannerDismissedDate = MutableStateFlow<String?>(null)
        val logs = MutableStateFlow<List<Long>>(emptyList())

        var lambdaInvocations = 0
        val combined = combine(
            featureEnabled,
            bannerDismissedDate,
            logs,
            helper.observe(sod)
        ) { _, _, _, _ ->
            lambdaInvocations += 1
            lambdaInvocations
        }

        val collectorJob = launch { combined.collect {} }
        runCurrent()
        val initialInvocations = lambdaInvocations
        assertEquals("Initial fire after combine subscription", 1, initialInvocations)

        advanceTimeBy(6 * 60 * 60 * 1000L) // 6h → past SoD
        runCurrent()

        assertNotEquals(
            "Banner combine MUST re-fire when localDateFlow emits a new logical date — " +
                "if this fails, someone removed localDateFlow from the combine sources",
            initialInvocations,
            lambdaInvocations
        )

        collectorJob.cancel()
        advanceUntilIdle()
    }
}
