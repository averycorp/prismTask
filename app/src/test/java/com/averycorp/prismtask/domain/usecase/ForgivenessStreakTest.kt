package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for v1.4.0 V5 — the forgiveness-first streak system.
 *
 * All tests fix a concrete `today` LocalDate so they're deterministic across
 * time zones and run days. The daily habit target is 1 completion per day.
 */
class ForgivenessStreakTest {
    private val today: LocalDate = LocalDate.of(2026, 4, 11)

    private fun LocalDate.toMillis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dailyHabit(target: Int = 1) = HabitEntity(
        id = 1,
        name = "Test",
        targetFrequency = target,
        frequencyPeriod = "daily"
    )

    private fun completion(date: LocalDate) = HabitCompletionEntity(
        habitId = 1,
        completedDate = date.toMillis(),
        completedAt = date.toMillis()
    )

    @Test
    fun `five on one miss three on produces resilient streak of nine`() {
        // 5 on days, 1 miss, 3 on days ending today.
        val completions = listOf(
            completion(today.minusDays(8)),
            completion(today.minusDays(7)),
            completion(today.minusDays(6)),
            completion(today.minusDays(5)),
            completion(today.minusDays(4)),
            // skip minusDays(3)
            completion(today.minusDays(2)),
            completion(today.minusDays(1)),
            completion(today)
        )
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT
        )
        assertEquals(3, result.strictStreak)
        assertEquals(9, result.resilientStreak)
        assertEquals(1, result.missesInWindow)
        assertEquals(0, result.gracePeriodRemaining)
        assertEquals(1, result.forgivenDates.size)
    }

    @Test
    fun `two consecutive misses today and yesterday cause hard reset`() {
        val completions = listOf(
            completion(today.minusDays(6)),
            completion(today.minusDays(5)),
            completion(today.minusDays(4)),
            completion(today.minusDays(3)),
            completion(today.minusDays(2))
            // missing today and minusDays(1)
        )
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT
        )
        assertEquals(0, result.resilientStreak)
    }

    @Test
    fun `classic strict mode ignores forgiveness`() {
        // Same data as the first test — 5 on, 1 miss, 3 on — should strict-reset.
        val completions = listOf(
            completion(today.minusDays(8)),
            completion(today.minusDays(7)),
            completion(today.minusDays(6)),
            completion(today.minusDays(5)),
            completion(today.minusDays(4)),
            completion(today.minusDays(2)),
            completion(today.minusDays(1)),
            completion(today)
        )
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.STRICT
        )
        assertEquals(3, result.strictStreak)
        assertEquals(3, result.resilientStreak)
        assertEquals(0, result.missesInWindow)
    }

    @Test
    fun `single miss today with yesterday complete continues run`() {
        // 5 completions ending yesterday, today skipped (mid-day).
        val completions = (1..5).map { completion(today.minusDays(it.toLong())) }
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT
        )
        // Today is "not yet done" so we start from yesterday. All 5 met,
        // no absorbed misses, earliestCompletion truncates walk.
        assertEquals(5, result.strictStreak)
        assertEquals(5, result.resilientStreak)
        assertEquals(0, result.missesInWindow)
    }

    @Test
    fun `empty completions returns zero`() {
        val result = StreakCalculator.calculateResilientDailyStreak(
            emptyList(),
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT
        )
        assertEquals(0, result.strictStreak)
        assertEquals(0, result.resilientStreak)
    }

    @Test
    fun `walk stops at earliest completion`() {
        // Only 3 days of history, all met. Walk should stop instead of
        // counting pre-history as misses.
        val completions = listOf(
            completion(today.minusDays(2)),
            completion(today.minusDays(1)),
            completion(today)
        )
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT
        )
        assertEquals(3, result.strictStreak)
        assertEquals(3, result.resilientStreak)
    }

    @Test
    fun `grace period remaining decrements when miss is absorbed`() {
        val completions = listOf(
            completion(today.minusDays(5)),
            completion(today.minusDays(4)),
            completion(today.minusDays(3)),
            // skip minusDays(2)
            completion(today.minusDays(1)),
            completion(today)
        )
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig(enabled = true, gracePeriodDays = 7, allowedMisses = 2)
        )
        // 1 absorbed miss, 2 allowed - 1 used = 1 remaining.
        assertEquals(1, result.missesInWindow)
        assertEquals(1, result.gracePeriodRemaining)
    }

    @Test
    fun `calculateResilientStreak returns strict for weekly habits`() {
        val weeklyHabit = HabitEntity(
            id = 1,
            name = "Test",
            targetFrequency = 3,
            frequencyPeriod = "weekly"
        )
        val completions = listOf(
            completion(today.minusDays(2)),
            completion(today.minusDays(1)),
            completion(today)
        )
        val result = StreakCalculator.calculateResilientStreak(
            completions,
            weeklyHabit,
            today,
            ForgivenessConfig.DEFAULT
        )
        // Non-daily frequencies fall back to strict behavior. Resilient ==
        // strict, forgivenDates empty.
        assertEquals(result.strictStreak, result.resilientStreak)
        assertTrue(result.forgivenDates.isEmpty())
    }

    @Test
    fun `allowed misses of zero matches classic strict mode for consecutive days`() {
        // 5 on days ending today — no gaps, so classic and forgiveness agree.
        val completions = (0..4).map { completion(today.minusDays(it.toLong())) }
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig(enabled = true, gracePeriodDays = 7, allowedMisses = 0)
        )
        assertEquals(5, result.strictStreak)
        assertEquals(5, result.resilientStreak)
    }

    @Test
    fun `single isolated miss is not absorbed when allowed misses is zero`() {
        val completions = listOf(
            completion(today.minusDays(4)),
            completion(today.minusDays(3)),
            // skip minusDays(2)
            completion(today.minusDays(1)),
            completion(today)
        )
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig(enabled = true, gracePeriodDays = 7, allowedMisses = 0)
        )
        // With 0 allowed, any miss breaks the streak.
        assertEquals(2, result.strictStreak)
        assertEquals(2, result.resilientStreak)
    }

    // -------------------------------------------------------------------
    // Rest Day (Mental-Health-First audit § G3)
    // -------------------------------------------------------------------

    @Test
    fun `rest day mid run preserves streak without consuming grace`() {
        // Days -4, -3, -1, 0 completed. Day -2 is a rest day, not a miss.
        // Without rest-day semantics this would burn the single grace
        // slot on day -2 and still count to 5 — but rest-day semantics
        // mean grace is fully intact afterward.
        val completions = listOf(
            completion(today.minusDays(4)),
            completion(today.minusDays(3)),
            completion(today.minusDays(1)),
            completion(today)
        )
        val restDays = setOf(today.minusDays(2))
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT,
            restDays
        )
        assertEquals(5, result.resilientStreak)
        assertEquals(0, result.missesInWindow)
        assertEquals(1, result.gracePeriodRemaining)
    }

    @Test
    fun `long press skip on a midweek day extends streak without using grace`() {
        // Long-press skip on day -3. The repository routes that skip into the
        // streak walk's restDays bucket, so the run reads as 8 consecutive
        // kept days with zero grace burned — the user-visible promise of
        // "skip doesn't break my streak".
        val completions = listOf(
            completion(today.minusDays(7)),
            completion(today.minusDays(6)),
            completion(today.minusDays(5)),
            completion(today.minusDays(4)),
            // day -3 is skipped, not missed
            completion(today.minusDays(2)),
            completion(today.minusDays(1)),
            completion(today)
        )
        val skippedDates = setOf(today.minusDays(3))
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT,
            skippedDates
        )
        assertEquals(8, result.resilientStreak)
        assertEquals(0, result.missesInWindow)
        assertEquals(1, result.gracePeriodRemaining)
        assertTrue(result.forgivenDates.isEmpty())
    }

    @Test
    fun `rest day on today and yesterday avoids hard reset`() {
        // Without rest-day support: today + yesterday both unlogged →
        // resilient hard-reset to 0. With rest-day on yesterday only,
        // mid-day rule drops cursor to yesterday (kept via rest-day) so
        // the run from day -2 survives.
        val completions = listOf(
            completion(today.minusDays(5)),
            completion(today.minusDays(4)),
            completion(today.minusDays(3)),
            completion(today.minusDays(2))
        )
        val restDays = setOf(today.minusDays(1))
        val result = StreakCalculator.calculateResilientDailyStreak(
            completions,
            dailyHabit(),
            today,
            ForgivenessConfig.DEFAULT,
            restDays
        )
        // 5-day run: rest -1 + activity -2 through -5.
        assertEquals(5, result.resilientStreak)
    }
}
