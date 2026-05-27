package com.averycorp.prismtask.domain.usecase

import java.time.LocalDate

/** Per-task history fed to the one-shot streak recompute. */
data class RecurringTaskHistory(
    val taskId: Long,
    val engagementDates: Set<LocalDate>,
    val skipDates: Set<LocalDate>,
    val effectiveThresholdDays: Int
)

/**
 * Dormancy Re-Entry: one-shot recompute of recurring-task streaks under the
 * new dormancy rule (feature flag `STREAK_RECOMPUTE_V2`).
 *
 * Recurring-task streaks are computed-on-read (there is no materialized streak
 * column — see the audit), so "recompute" means: re-derive every recurring
 * task's streak under [RecurringTaskStreakCalculator] from its session/completion
 * history. The result map lets the caller verify the migration and seed any
 * read-time caches. Pure + deterministic so it can be fixture-tested.
 */
object RecurringStreakRecomputer {

    fun recompute(
        histories: List<RecurringTaskHistory>,
        today: LocalDate = LocalDate.now()
    ): Map<Long, RecurringStreakResult> =
        histories.associate { history ->
            history.taskId to RecurringTaskStreakCalculator.calculate(
                engagementDates = history.engagementDates,
                skipDates = history.skipDates,
                effectiveThresholdDays = history.effectiveThresholdDays,
                today = today
            )
        }
}
