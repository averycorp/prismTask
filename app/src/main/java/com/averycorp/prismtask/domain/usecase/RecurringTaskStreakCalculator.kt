package com.averycorp.prismtask.domain.usecase

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Result of a recurring-task streak computation. */
data class RecurringStreakResult(
    /** Streak length in completions (engagement days in the current run). */
    val currentStreak: Int,
    /** True when the current run is broken (explicit skip or dormancy gap). */
    val broken: Boolean,
    /** Canonical resilient day-length of the run from [DailyForgivenessStreakCore]. */
    val resilientDayStreak: Int
) {
    companion object {
        val EMPTY = RecurringStreakResult(currentStreak = 0, broken = true, resilientDayStreak = 0)
    }
}

/**
 * Dormancy Re-Entry: recurring-task streak under the inverted rule.
 *
 * Recurring tasks had no streak before this feature; this is net-new and,
 * per the locked design decision, reuses [DailyForgivenessStreakCore] for the
 * day-walk rather than introducing a parallel implementation.
 *
 * Rules (Checkpoint 4):
 *  - The streak increments on each completion (engagement day).
 *  - Simple gaps do NOT break the streak — engagements separated by a gap of
 *    up to `2 × effectiveThreshold` days stay in one run (the dormancy window;
 *    "Dismiss for today" alone never breaks it).
 *  - An explicit user **skip** breaks the streak.
 *  - Going dormant for more than `2 × effectiveThreshold` days without any
 *    session breaks the streak.
 *
 * Anti-pattern note (PHILOSOPHY.md Principle 1): this introduces a streak that
 * *can* break on skip / prolonged dormancy. That is an intentional, operator-
 * approved override of the forgiveness-first default (skip = kept day); it is
 * documented in the PR description per the override path. Gaps remaining
 * non-breaking keeps the feature forgiveness-leaning.
 */
object RecurringTaskStreakCalculator {

    /**
     * @param engagementDates Days the task was completed OR resumed (Resume Tiny
     *   counts as engagement). Derived from completion history + lastEngagementAt.
     * @param skipDates Days the user explicitly skipped the task.
     * @param effectiveThresholdDays The task's effective dormancy threshold
     *   (`override ?: global`). The streak breaks past `2 ×` this many dormant days.
     * @param today Reference date.
     */
    fun calculate(
        engagementDates: Set<LocalDate>,
        skipDates: Set<LocalDate>,
        effectiveThresholdDays: Int,
        today: LocalDate = LocalDate.now()
    ): RecurringStreakResult {
        if (engagementDates.isEmpty()) return RecurringStreakResult.EMPTY
        val breakGap = (2L * effectiveThresholdDays).coerceAtLeast(1)

        val last = engagementDates.max()

        // Explicit skip after the most recent engagement breaks the run.
        if (skipDates.any { it.isAfter(last) && !it.isAfter(today) }) {
            return RecurringStreakResult.EMPTY
        }
        // Dormancy break: too long since the last session.
        if (ChronoUnit.DAYS.between(last, today) > breakGap) {
            return RecurringStreakResult.EMPTY
        }

        // Walk engagements newest→oldest, staying in one run while consecutive
        // engagements are within the dormancy window and no skip intervenes.
        val ordered = engagementDates.sortedDescending()
        var runCount = 0
        var prev: LocalDate? = null
        var runStart = last
        for (date in ordered) {
            if (prev != null) {
                val gap = ChronoUnit.DAYS.between(date, prev)
                val skipBetween = skipDates.any { it.isAfter(date) && !it.isAfter(prev) }
                if (gap > breakGap || skipBetween) break
            }
            runCount++
            runStart = date
            prev = date
        }

        // Honour the locked "reuse the forgiveness core" decision: fill the run's
        // gap days and delegate the canonical day-walk to DailyForgivenessStreakCore.
        val filled = buildSet {
            var cursor = runStart
            while (!cursor.isAfter(last)) {
                add(cursor)
                cursor = cursor.plusDays(1)
            }
            // Reach today so the core's mid-day rule keeps the run alive.
            var trailing = last.plusDays(1)
            while (!trailing.isAfter(today)) {
                add(trailing)
                trailing = trailing.plusDays(1)
            }
        }
        val core = DailyForgivenessStreakCore.calculate(
            activityDates = filled,
            today = today,
            config = ForgivenessConfig(enabled = true, gracePeriodDays = 1, allowedMisses = 0)
        )

        return RecurringStreakResult(
            currentStreak = runCount,
            broken = false,
            resilientDayStreak = core.resilientStreak
        )
    }
}
