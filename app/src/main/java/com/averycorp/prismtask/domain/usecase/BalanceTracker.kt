package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.domain.model.LifeCategory
import java.util.Calendar
import java.util.TimeZone

/**
 * Snapshot of the user's current Work-Life Balance state.
 *
 * @property currentRatios How the last 7 days of tasks are distributed across categories.
 *                         Values are normalized 0.0..1.0 and sum to 1.0 unless [totalTracked] == 0.
 * @property rollingRatios Same computation over the past 28 days (4-week average).
 * @property targetRatios The user's configured target for each category.
 * @property isOverloaded True when WORK ratio exceeds `workTarget + overloadThreshold`.
 * @property dominantCategory The category with the highest current ratio
 *                            (or [LifeCategory.UNCATEGORIZED] when there is no data).
 * @property totalTracked The number of tracked tasks contributing to [currentRatios].
 */
data class BalanceState(
    val currentRatios: Map<LifeCategory, Float>,
    val rollingRatios: Map<LifeCategory, Float>,
    val targetRatios: Map<LifeCategory, Float>,
    val isOverloaded: Boolean,
    val dominantCategory: LifeCategory,
    val totalTracked: Int
) {
    companion object {
        val EMPTY = BalanceState(
            currentRatios = LifeCategory.TRACKED.associateWith { 0f },
            rollingRatios = LifeCategory.TRACKED.associateWith { 0f },
            targetRatios = LifeCategory.TRACKED.associateWith { 0.25f },
            isOverloaded = false,
            dominantCategory = LifeCategory.UNCATEGORIZED,
            totalTracked = 0
        )
    }
}

/**
 * Configuration for [BalanceTracker]. Percentages are stored as 0..100 ints in
 * DataStore but exposed as 0f..1f floats here. [overloadThreshold] is an
 * additive buffer in the same 0f..1f units (0.10 = 10 percentage points).
 */
data class BalanceConfig(
    val workTarget: Float = 0.40f,
    val personalTarget: Float = 0.25f,
    val selfCareTarget: Float = 0.20f,
    val healthTarget: Float = 0.15f,
    val overloadThreshold: Float = 0.10f
) {
    fun asMap(): Map<LifeCategory, Float> = mapOf(
        LifeCategory.WORK to workTarget,
        LifeCategory.PERSONAL to personalTarget,
        LifeCategory.SELF_CARE to selfCareTarget,
        LifeCategory.HEALTH to healthTarget
    )

    /** Whether the targets form a valid distribution (sums to ~1.0, allowing rounding). */
    fun isValid(): Boolean {
        val sum = workTarget + personalTarget + selfCareTarget + healthTarget
        return kotlin.math.abs(sum - 1f) < 0.01f
    }
}

/**
 * One habit completion's contribution to balance ratios. The [lifeCategory]
 * is pre-resolved by the caller (either via an explicit habit-side field or
 * by running [LifeCategoryClassifier] on the habit's name / category) so
 * the tracker stays free of habit-entity coupling.
 */
data class HabitContribution(
    val completedAt: Long,
    val lifeCategory: LifeCategory
)

/**
 * One leisure session's contribution to balance ratios. Leisure mode is
 * orthogonal to [LifeCategory]; every logged session counts toward
 * [LifeCategory.SELF_CARE] because leisure time *is* the user's restorative
 * self-care signal in the work/play/relax model.
 */
data class LeisureContribution(
    val loggedAt: Long
)

/**
 * Pure-function balance computations used by the Today screen balance bar,
 * weekly report, and burnout scorer.
 *
 * The tracker takes tasks plus optional habit and leisure contributions so
 * the balance bar reflects every activity the user logs, not just tasks.
 * A habit completion counts as one unit in its resolved [LifeCategory]; a
 * leisure session counts as one unit in [LifeCategory.SELF_CARE]. Callers
 * that have not yet wired habits or leisure just leave the lists empty.
 *
 * Window cutoffs respect the user-configured Start-of-Day so that the balance
 * bar's "this week" matches the Today-screen task filter, habit streaks, and
 * widget windows (all of which derive their day boundary from
 * [com.averycorp.prismtask.util.DayBoundary]). Callers that don't have access
 * to the SoD preference fall back to system midnight (`dayStartHour = 0`).
 */
class BalanceTracker {
    /**
     * Compute a [BalanceState] from tasks, habit completions, and leisure sessions.
     *
     * @param defaultDurationMinutes Per-entry weight in minutes used when a
     *   task has no `estimatedDuration` set, and the unit weight used for
     *   every habit completion / leisure session (those have no per-entry
     *   duration field). Coerced to at least 1 to prevent divide-by-zero.
     *   Default 30 keeps ratios identical to the legacy count-based behaviour
     *   when no task carries an explicit estimate.
     */
    fun compute(
        allTasks: List<TaskEntity>,
        config: BalanceConfig,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        dayStartHour: Int = 0,
        dayStartMinute: Int = 0,
        habitContributions: List<HabitContribution> = emptyList(),
        leisureContributions: List<LeisureContribution> = emptyList(),
        defaultDurationMinutes: Int = 30
    ): BalanceState {
        val weekCutoff = cutoff(now, days = 7, timeZone, dayStartHour, dayStartMinute)
        val monthCutoff = cutoff(now, days = 28, timeZone, dayStartHour, dayStartMinute)
        val unitMinutes = defaultDurationMinutes.coerceAtLeast(1)

        val current = computeRatios(allTasks, habitContributions, leisureContributions, weekCutoff, unitMinutes)
        val rolling = computeRatios(allTasks, habitContributions, leisureContributions, monthCutoff, unitMinutes)
        val total = countTracked(allTasks, habitContributions, leisureContributions, weekCutoff)

        val workRatio = current[LifeCategory.WORK] ?: 0f
        val overloaded = total > 0 && workRatio > (config.workTarget + config.overloadThreshold)

        val dominant = if (total == 0) {
            LifeCategory.UNCATEGORIZED
        } else {
            current.maxByOrNull { it.value }?.key ?: LifeCategory.UNCATEGORIZED
        }

        return BalanceState(
            currentRatios = current,
            rollingRatios = rolling,
            targetRatios = config.asMap(),
            isOverloaded = overloaded,
            dominantCategory = dominant,
            totalTracked = total
        )
    }

    private fun computeRatios(
        tasks: List<TaskEntity>,
        habitContributions: List<HabitContribution>,
        leisureContributions: List<LeisureContribution>,
        cutoff: Long,
        unitMinutes: Int
    ): Map<LifeCategory, Float> {
        val minutes = LifeCategory.TRACKED.associateWith { 0L }.toMutableMap()
        var total = 0L
        for (t in tasks) {
            val ts = timestampFor(t)
            if (ts < cutoff) continue
            val cat = LifeCategory.fromStorage(t.lifeCategory)
            if (cat == LifeCategory.UNCATEGORIZED) continue
            val w = (t.estimatedDuration?.takeIf { it > 0 } ?: unitMinutes).toLong()
            minutes[cat] = (minutes[cat] ?: 0L) + w
            total += w
        }
        for (h in habitContributions) {
            if (h.completedAt < cutoff) continue
            if (h.lifeCategory == LifeCategory.UNCATEGORIZED) continue
            minutes[h.lifeCategory] = (minutes[h.lifeCategory] ?: 0L) + unitMinutes
            total += unitMinutes
        }
        for (l in leisureContributions) {
            if (l.loggedAt < cutoff) continue
            minutes[LifeCategory.SELF_CARE] = (minutes[LifeCategory.SELF_CARE] ?: 0L) + unitMinutes
            total += unitMinutes
        }
        if (total == 0L) return LifeCategory.TRACKED.associateWith { 0f }
        return minutes.mapValues { (_, m) -> m.toFloat() / total.toFloat() }
    }

    private fun countTracked(
        tasks: List<TaskEntity>,
        habitContributions: List<HabitContribution>,
        leisureContributions: List<LeisureContribution>,
        cutoff: Long
    ): Int {
        var total = 0
        for (t in tasks) {
            val ts = timestampFor(t)
            if (ts < cutoff) continue
            val cat = LifeCategory.fromStorage(t.lifeCategory)
            if (cat == LifeCategory.UNCATEGORIZED) continue
            total++
        }
        for (h in habitContributions) {
            if (h.completedAt < cutoff) continue
            if (h.lifeCategory == LifeCategory.UNCATEGORIZED) continue
            total++
        }
        for (l in leisureContributions) {
            if (l.loggedAt < cutoff) continue
            total++
        }
        return total
    }

    /**
     * Choose the most relevant timestamp for a task when deciding whether
     * it falls into the balance window:
     *  - Completed tasks use `completedAt`.
     *  - Otherwise, `dueDate` if set, else `createdAt`.
     */
    private fun timestampFor(task: TaskEntity): Long = task.completedAt ?: task.dueDate ?: task.createdAt

    /**
     * Lower bound of the balance window. Snaps `now` back to the most recent
     * day-start (the configured SoD on today's calendar date if `now` is at or
     * after it; otherwise yesterday's SoD), then walks back `days - 1` days so
     * the resulting window covers `days` logical days inclusive of today.
     */
    private fun cutoff(
        now: Long,
        days: Int,
        timeZone: TimeZone,
        dayStartHour: Int,
        dayStartMinute: Int
    ): Long {
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = now
        val currentMinutesSinceMidnight = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val sodMinutesSinceMidnight = dayStartHour * 60 + dayStartMinute
        cal.set(Calendar.HOUR_OF_DAY, dayStartHour)
        cal.set(Calendar.MINUTE, dayStartMinute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (currentMinutesSinceMidnight < sodMinutesSinceMidnight) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        return cal.timeInMillis
    }
}
