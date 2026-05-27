package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity

/** A dormant task surfaced in the "Ready to Resume" section. */
data class DormantTask(
    val task: TaskEntity,
    val daysDormant: Long
)

/**
 * Dormancy Re-Entry: pure ranking for the "Ready to Resume" section.
 *
 * A task qualifies when it is an incomplete, non-archived recurring task that
 * is dormant (see [DormancyCalculator.isDormant]) and not dismissed for today.
 * Results are sorted longest-dormant first and capped at [MAX_VISIBLE].
 */
object ReadyToResumeProvider {
    const val MAX_VISIBLE = 5

    fun resume(
        tasks: List<TaskEntity>,
        globalThresholdDays: Int,
        dismissedTaskIds: Set<Long>,
        nowMillis: Long
    ): List<DormantTask> =
        tasks.asSequence()
            .filter { it.recurrenceRule != null }
            .filter { !it.isCompleted && it.archivedAt == null }
            .filter { it.id !in dismissedTaskIds }
            .filter { it.isDormant(nowMillis, globalThresholdDays) }
            .map { DormantTask(it, DormancyCalculator.daysDormant(it.lastEngagementAt, nowMillis)) }
            .sortedByDescending { it.daysDormant }
            .take(MAX_VISIBLE)
            .toList()
}
