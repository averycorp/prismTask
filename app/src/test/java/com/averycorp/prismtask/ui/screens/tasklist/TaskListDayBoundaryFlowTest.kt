package com.averycorp.prismtask.ui.screens.tasklist

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.core.time.TimeProvider
import com.averycorp.prismtask.data.preferences.StartOfDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Regression gate for `TaskListViewModel.startOfToday`'s SoD-boundary
 * fix (covers the "From Earlier shows things due today" follow-up).
 *
 * Reconstructs the production shape from `TaskListViewModel.startOfToday`:
 *
 *   `localDateFlow.observe(getStartOfDay())
 *       .map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
 *       .stateIn(...)`
 *
 * Two properties under test:
 *
 * 1. The StateFlow's value advances when the wall-clock crosses the
 *    user's SoD — what the legacy snapshot pattern could not.
 * 2. The emitted value is *calendar midnight* of the logical day, NOT
 *    SoD-anchored time. Timeless tasks (created via the "Today" date
 *    picker / NLP "today") store dueDate at 00:00 local; if this field
 *    drifts back to SoD-anchored time, every such task would land in
 *    the Overdue bucket once SoD > 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskListDayBoundaryFlowTest {

    private val zone = ZoneId.of("UTC")

    private fun virtualClock(scope: TestScope, base: Instant) =
        object : TimeProvider {
            override fun now(): Instant = base.plusMillis(scope.testScheduler.currentTime)
            override fun zone(): ZoneId = zone
        }

    @Test
    fun startOfToday_localDateFlowDriven_advancesAtSoDBoundary() = runTest {
        // 11pm Apr 25 UTC, SoD = 4am.
        // Logical day = Apr 25; calendar midnight = Apr 25 00:00 UTC.
        // 6h later (5am Apr 26) the SoD boundary has flipped — calendar
        // midnight of the new logical day = Apr 26 00:00 UTC.
        val base = Instant.parse("2026-04-25T23:00:00Z")
        val sod = MutableStateFlow(StartOfDay(hour = 4, minute = 0, hasBeenSet = true))
        val helper = LocalDateFlow(virtualClock(this, base))

        val startOfToday: StateFlow<Long> = helper.observe(sod)
            .map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
            .stateIn(backgroundScope, SharingStarted.Eagerly, -1L)

        runCurrent()
        val before = startOfToday.value
        assertEquals(
            "Initial value: calendar midnight of Apr 25 (the logical day at 11pm UTC " +
                "pre-boundary). MUST be 00:00, not 04:00 — timeless tasks stored at " +
                "00:00 would otherwise show in Overdue.",
            Instant.parse("2026-04-25T00:00:00Z").toEpochMilli(),
            before
        )

        advanceTimeBy(6 * 60 * 60 * 1000L) // 6h → past Apr 26 04:00 UTC SoD
        runCurrent()

        val after = startOfToday.value
        assertNotEquals(
            "startOfToday MUST advance reactively when wall-clock crosses SoD — " +
                "if this fails, someone reverted to the snapshot pattern",
            before,
            after
        )
        assertEquals(
            "After SoD crossing, startOfToday is calendar midnight of the new logical day",
            Instant.parse("2026-04-26T00:00:00Z").toEpochMilli(),
            after
        )
    }
}
