package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LeisureScorerTest {
    private val scorer = LeisureScorer()

    @Test
    fun emptyDay_collapses_to_zero() {
        val score = scorer.scoreDay(
            sessions = emptyList(),
            targetMinutes = 60,
            currentStreakDays = 0
        )
        assertEquals(0, score.total)
        assertEquals(0, score.budgetPoints)
        assertEquals(0, score.varietyPoints)
        assertEquals(0, score.streakPoints)
    }

    @Test
    fun targetHit_with_one_category_logs_60_budget_5_variety() {
        val score = scorer.scoreDay(
            sessions = listOf(session(durationMinutes = 60, category = "PHYSICAL")),
            targetMinutes = 60,
            currentStreakDays = 0
        )
        assertEquals(60, score.budgetPoints)
        assertEquals(5, score.varietyPoints) // 1/4 of 20
        assertEquals(65, score.total)
    }

    @Test
    fun all_four_categories_caps_variety_at_20() {
        val sessions = listOf(
            session(15, "PHYSICAL"),
            session(15, "SOCIAL"),
            session(15, "CREATIVE"),
            session(15, "PASSIVE")
        )
        val score = scorer.scoreDay(
            sessions = sessions,
            targetMinutes = 60,
            currentStreakDays = 0
        )
        assertEquals(20, score.varietyPoints)
    }

    @Test
    fun streak_cap_at_7_days() {
        val score = scorer.scoreDay(
            sessions = emptyList(),
            targetMinutes = 60,
            currentStreakDays = 20
        )
        assertEquals(20, score.streakPoints) // capped at 7-of-7 → 20 pts
    }

    @Test
    fun streak_under_cap_is_proportional() {
        val score = scorer.scoreDay(
            sessions = emptyList(),
            targetMinutes = 60,
            currentStreakDays = 3
        )
        // 3/7 of 20 = 8.57 → 8 (Int truncation)
        assertEquals(8, score.streakPoints)
    }

    @Test
    fun budget_overshoot_clamps_to_60() {
        val score = scorer.scoreDay(
            sessions = listOf(session(durationMinutes = 999, category = "PHYSICAL")),
            targetMinutes = 60,
            currentStreakDays = 0
        )
        assertEquals(60, score.budgetPoints)
    }

    @Test
    fun zero_target_gives_zero_budget_points() {
        // "Tracking only, no goal" mode.
        val score = scorer.scoreDay(
            sessions = listOf(session(durationMinutes = 60, category = "PHYSICAL")),
            targetMinutes = 0,
            currentStreakDays = 0
        )
        assertEquals(0, score.budgetPoints)
    }

    @Test
    fun perfect_day_total_is_100() {
        val sessions = listOf(
            session(15, "PHYSICAL"),
            session(15, "SOCIAL"),
            session(15, "CREATIVE"),
            session(15, "PASSIVE")
        )
        val score = scorer.scoreDay(
            sessions = sessions,
            targetMinutes = 60,
            currentStreakDays = 7
        )
        assertEquals(60, score.budgetPoints)
        assertEquals(20, score.varietyPoints)
        assertEquals(20, score.streakPoints)
        assertEquals(100, score.total)
    }

    @Test
    fun minutesLogged_aggregates_sessions() {
        val sessions = listOf(
            session(20, "PHYSICAL"),
            session(10, "SOCIAL"),
            session(5, "PASSIVE")
        )
        val score = scorer.scoreDay(
            sessions = sessions,
            targetMinutes = 60,
            currentStreakDays = 0
        )
        assertEquals(35, score.minutesLogged)
    }

    private fun session(durationMinutes: Int, category: String): LeisureSessionEntity =
        LeisureSessionEntity(
            category = category,
            durationMinutes = durationMinutes,
            loggedAt = 0L,
            source = "MANUAL"
        )
}
