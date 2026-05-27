package com.averycorp.prismtask.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurringStreakRecomputerTest {

    private val today = LocalDate.of(2026, 5, 27)

    @Test
    fun `recompute matches expected values on fixture data`() {
        val histories = listOf(
            // 1: three contiguous recent completions → streak 3, alive.
            RecurringTaskHistory(
                taskId = 1,
                engagementDates = setOf(today, today.minusDays(1), today.minusDays(2)),
                skipDates = emptySet(),
                effectiveThresholdDays = 7
            ),
            // 2: dormant 15 days (> 2×7) → broken, 0.
            RecurringTaskHistory(
                taskId = 2,
                engagementDates = setOf(today.minusDays(15)),
                skipDates = emptySet(),
                effectiveThresholdDays = 7
            ),
            // 3: recent run but an explicit skip after last engagement → broken.
            RecurringTaskHistory(
                taskId = 3,
                engagementDates = setOf(today.minusDays(3)),
                skipDates = setOf(today.minusDays(1)),
                effectiveThresholdDays = 7
            ),
            // 4: never engaged → empty.
            RecurringTaskHistory(
                taskId = 4,
                engagementDates = emptySet(),
                skipDates = emptySet(),
                effectiveThresholdDays = 7
            )
        )

        val result = RecurringStreakRecomputer.recompute(histories, today)

        assertEquals(4, result.size)
        assertEquals(3, result.getValue(1).currentStreak)
        assertFalse(result.getValue(1).broken)
        assertTrue(result.getValue(2).broken)
        assertEquals(0, result.getValue(2).currentStreak)
        assertTrue(result.getValue(3).broken)
        assertTrue(result.getValue(4).broken)
    }
}
