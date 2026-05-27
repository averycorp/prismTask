package com.averycorp.prismtask.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurringTaskStreakCalculatorTest {

    private val today = LocalDate.of(2026, 5, 27)
    private val threshold = 7 // breakGap = 14

    private fun calc(
        engagements: Set<LocalDate>,
        skips: Set<LocalDate> = emptySet(),
        thresholdDays: Int = threshold
    ) = RecurringTaskStreakCalculator.calculate(engagements, skips, thresholdDays, today)

    @Test
    fun `no engagements yields empty broken streak`() {
        val r = calc(emptySet())
        assertEquals(0, r.currentStreak)
        assertTrue(r.broken)
    }

    @Test
    fun `gap of threshold plus one preserves the streak`() {
        // Last engagement threshold+1 = 8 days ago (< 2×threshold). Preserved.
        val engagements = setOf(today.minusDays(8), today.minusDays(9), today.minusDays(10))
        val r = calc(engagements)
        assertFalse(r.broken)
        assertEquals(3, r.currentStreak)
    }

    @Test
    fun `gap of twice threshold plus one breaks the streak`() {
        // Last engagement 2×threshold+1 = 15 days ago (> 14). Broken.
        val engagements = setOf(today.minusDays(15), today.minusDays(16))
        val r = calc(engagements)
        assertTrue(r.broken)
        assertEquals(0, r.currentStreak)
    }

    @Test
    fun `explicit skip after last engagement breaks the streak`() {
        val engagements = setOf(today.minusDays(3), today.minusDays(4))
        val skips = setOf(today.minusDays(1))
        val r = calc(engagements, skips)
        assertTrue(r.broken)
        assertEquals(0, r.currentStreak)
    }

    @Test
    fun `skip between engagements ends the run at the skip`() {
        // Recent run: today-1, today-2. Skip at today-5 cuts off older engagements.
        val engagements = setOf(
            today.minusDays(1),
            today.minusDays(2),
            today.minusDays(8),
            today.minusDays(9)
        )
        val skips = setOf(today.minusDays(5))
        val r = calc(engagements, skips)
        assertFalse(r.broken)
        assertEquals(2, r.currentStreak)
    }

    @Test
    fun `resume tiny engagement today preserves and extends the streak`() {
        // A Resume Tiny session today is just another engagement date.
        val engagements = setOf(today, today.minusDays(10), today.minusDays(11))
        val r = calc(engagements)
        assertFalse(r.broken)
        assertEquals(3, r.currentStreak)
    }

    @Test
    fun `simple daily completions count each as a streak step`() {
        val engagements = (0L..4L).map { today.minusDays(it) }.toSet()
        val r = calc(engagements)
        assertFalse(r.broken)
        assertEquals(5, r.currentStreak)
        // Contiguous days → resilient day-streak equals the run length.
        assertEquals(5, r.resilientDayStreak)
    }

    @Test
    fun `per task threshold widens the tolerated gap`() {
        // Gap of 20 days: broken at threshold 7 (breakGap 14) ...
        val engagements = setOf(today.minusDays(20), today.minusDays(21))
        assertTrue(calc(engagements).broken)
        // ... but preserved at threshold 15 (breakGap 30).
        assertFalse(calc(engagements, thresholdDays = 15).broken)
    }
}
