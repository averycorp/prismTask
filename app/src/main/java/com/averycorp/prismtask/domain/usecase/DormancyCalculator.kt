package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import java.util.concurrent.TimeUnit

/**
 * Pure dormancy math for the Dormancy Re-Entry feature.
 *
 * "Dormant" means a task has gone untouched longer than its effective
 * threshold. Detection is computed at query time — there is no background job
 * and no materialized dormancy flag. A task with a NULL [TaskEntity.lastEngagementAt]
 * has never been engaged and is therefore NOT dormant (never-engaged ≠ dormant).
 *
 * Day counting mirrors `ChronoUnit.DAYS.between(lastEngagementAt, now)`: whole
 * 24-hour periods elapsed, floored. We operate on epoch millis because that is
 * what the entity stores; callers pass `System.currentTimeMillis()` for `now`.
 */
object DormancyCalculator {

    /** Effective threshold in days: per-task override wins over the global default. */
    fun effectiveThresholdDays(override: Int?, global: Int): Int = override ?: global

    /** Whole days elapsed between two epoch-millis instants, floored, never negative. */
    fun daysBetween(fromMillis: Long, toMillis: Long): Long =
        TimeUnit.MILLISECONDS.toDays((toMillis - fromMillis).coerceAtLeast(0))

    /**
     * True when the task is dormant: it has been engaged at least once and the
     * whole-days gap since last engagement exceeds the effective threshold.
     */
    fun isDormant(lastEngagementAt: Long?, override: Int?, global: Int, nowMillis: Long): Boolean {
        if (lastEngagementAt == null) return false
        return daysBetween(lastEngagementAt, nowMillis) > effectiveThresholdDays(override, global)
    }

    /** Days dormant for display ("N days"); 0 when never engaged. */
    fun daysDormant(lastEngagementAt: Long?, nowMillis: Long): Long =
        lastEngagementAt?.let { daysBetween(it, nowMillis) } ?: 0L
}

/** Effective dormancy threshold in days for this task given the global default. */
fun TaskEntity.effectiveDormancyThreshold(global: Int): Int =
    DormancyCalculator.effectiveThresholdDays(dormancyThresholdDaysOverride, global)

/** Whether this task is dormant right now given the global default and clock. */
fun TaskEntity.isDormant(nowMillis: Long, global: Int): Boolean =
    DormancyCalculator.isDormant(lastEngagementAt, dormancyThresholdDaysOverride, global, nowMillis)
