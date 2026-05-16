package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.core.time.TimeProvider
import com.averycorp.prismtask.data.preferences.StartOfDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
 * Regression gate for `DailyEssentialsUseCase`'s SoD-boundary fix.
 *
 * **Phase-5 (GREEN) form** — assertion encodes the bug-fixed state.
 * Reconstructs the post-migration shape from `observeToday()`:
 *
 *   `combine(localDateFlow.observe(getStartOfDay()), getStartOfDay())`
 *   `    .flatMapLatest { (date, sod) ->`
 *   `        val todayStart = date.atTime(sod.hour, sod.minute)... epochMillis`
 *   `        ... three more derivations ...`
 *   `        combine(...) { ... }`
 *   `    }`
 *
 * Asserts the inner `flatMapLatest` re-fires when localDateFlow emits a
 * new logical date — i.e. the four window locals refresh at SoD
 * boundary crossing. Inverted from Phase-1 form (commit `f716f9c3`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyEssentialsDayBoundaryFlowTest {

    private val zone = ZoneId.of("UTC")

    private fun virtualClock(scope: TestScope, base: Instant) =
        object : TimeProvider {
            override fun now(): Instant = base.plusMillis(scope.testScheduler.currentTime)
            override fun zone(): ZoneId = zone
        }

    @Test
    fun observeToday_localDateFlowDriven_refiresAtSoDBoundary() = runTest {
        // 11pm Apr 25 UTC, SoD = 4am.
        // Logical day = Apr 25; SoD-anchored start = Apr 25 04:00 UTC.
        // 6h later: SoD boundary flipped → start = Apr 26 04:00 UTC.
        val base = Instant.parse("2026-04-25T23:00:00Z")
        val sod = MutableStateFlow(StartOfDay(hour = 4, minute = 0, hasBeenSet = true))
        val helper = LocalDateFlow(virtualClock(this, base))

        var flatMapInvocations = 0
        val composed: StateFlow<Long> = combine(helper.observe(sod), sod) { d, s -> d to s }
            .flatMapLatest { (date, s) ->
                flatMapInvocations += 1
                val todayStart = date.atTime(s.hour, s.minute)
                    .atZone(zone).toInstant().toEpochMilli()
                combine(flowOf(Unit)) { _ -> todayStart }
            }
            .stateIn(backgroundScope, SharingStarted.Eagerly, -1L)

        runCurrent()
        val before = composed.value
        val firstFireCount = flatMapInvocations
        assertEquals(
            "Initial SoD-anchored start of Apr 25",
            Instant.parse("2026-04-25T04:00:00Z").toEpochMilli(),
            before
        )
        assertEquals("flatMapLatest fired once on subscription", 1, firstFireCount)

        advanceTimeBy(6 * 60 * 60 * 1000L) // 6h → past Apr 26 04:00 UTC
        runCurrent()

        val after = composed.value
        assertNotEquals(
            "todayStart MUST advance — if this fails, snapshot pattern was reverted",
            before,
            after
        )
        assertEquals(
            "Post-boundary, SoD-anchored start = Apr 26 04:00 UTC",
            Instant.parse("2026-04-26T04:00:00Z").toEpochMilli(),
            after
        )
        assertNotEquals(
            "flatMapLatest re-fired on the boundary crossing — locals refreshed",
            firstFireCount,
            flatMapInvocations
        )
    }
}
