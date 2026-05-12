package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Leisure Budget v2.0 — Item 12.
 *
 * Daily score 0–100 = 60 (budget hit) + 20 (variety) + 20 (streak).
 *
 * Component breakdown:
 * * **60 pts — budget**: linear over `minutesLogged / targetMinutes`, clamped to 60.
 *   target == 0 collapses to 0 points (no target means no points to earn);
 *   the UI clamps target ≥ 0 but a configured target of 0 is allowed for
 *   the "tracking only, no goal" mode.
 * * **20 pts — variety**: `distinctCategoriesLogged / 4` of 20. With four
 *   spec-locked categories, a user logging all four on the same day caps
 *   the bonus.
 * * **20 pts — streak**: current streak (in target-hit days) capped at 7
 *   for scoring purposes — `min(streak, 7) / 7` of 20. The full streak
 *   number is still surfaced in the UI; this cap is just to prevent the
 *   score from being dominated by an old streak.
 *
 * Standalone score; does NOT interact with the productivity score
 * mathematically. The spec explicitly carves these as parallel
 * dimensions of user experience.
 */
@Singleton
class LeisureScorer
@Inject
constructor() {

    data class LeisureScore(
        val total: Int,
        val budgetPoints: Int,
        val varietyPoints: Int,
        val streakPoints: Int,
        val minutesLogged: Int,
        val targetMinutes: Int,
        val distinctCategoriesLogged: Int,
        val currentStreakDays: Int
    )

    /**
     * Compute the score for one day from its session rows + the user's
     * target + their current streak (computed elsewhere — typically by
     * [com.averycorp.prismtask.domain.usecase.DailyForgivenessStreakCore]).
     */
    fun scoreDay(
        sessions: List<LeisureSessionEntity>,
        targetMinutes: Int,
        currentStreakDays: Int
    ): LeisureScore {
        val minutesLogged = sessions.sumOf { it.durationMinutes }.coerceAtLeast(0)
        val distinctCategories = sessions.map { it.category.uppercase() }.toSet().size

        val budgetPoints = if (targetMinutes <= 0) {
            0
        } else {
            (minutesLogged.toDouble() / targetMinutes.toDouble() * MAX_BUDGET)
                .coerceAtMost(MAX_BUDGET.toDouble())
                .toInt()
        }

        val varietyPoints = (distinctCategories.toDouble() / TOTAL_CATEGORIES *
            MAX_VARIETY)
            .coerceAtMost(MAX_VARIETY.toDouble())
            .toInt()

        val streakPoints =
            (min(currentStreakDays, STREAK_CAP_DAYS).toDouble() / STREAK_CAP_DAYS *
                MAX_STREAK)
                .coerceAtMost(MAX_STREAK.toDouble())
                .toInt()

        return LeisureScore(
            total = budgetPoints + varietyPoints + streakPoints,
            budgetPoints = budgetPoints,
            varietyPoints = varietyPoints,
            streakPoints = streakPoints,
            minutesLogged = minutesLogged,
            targetMinutes = targetMinutes,
            distinctCategoriesLogged = distinctCategories,
            currentStreakDays = currentStreakDays
        )
    }

    companion object {
        const val MAX_BUDGET = 60
        const val MAX_VARIETY = 20
        const val MAX_STREAK = 20
        const val STREAK_CAP_DAYS = 7
        const val TOTAL_CATEGORIES = 4
    }
}
