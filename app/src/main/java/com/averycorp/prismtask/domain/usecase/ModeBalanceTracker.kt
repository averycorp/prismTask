package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.domain.model.TaskMode
import java.util.Calendar
import java.util.TimeZone

/**
 * Snapshot of the user's current mode balance — the Work / Play / Relax
 * version of [BalanceState], orthogonal to life-category balance.
 *
 * Numbers are descriptive, not prescriptive (see `docs/WORK_PLAY_RELAX.md`).
 */
data class ModeBalanceState(
    val currentRatios: Map<TaskMode, Float>,
    val rollingRatios: Map<TaskMode, Float>,
    val targetRatios: Map<TaskMode, Float>,
    val dominantMode: TaskMode,
    val totalTracked: Int
) {
    companion object {
        val EMPTY = ModeBalanceState(
            currentRatios = TaskMode.TRACKED.associateWith { 0f },
            rollingRatios = TaskMode.TRACKED.associateWith { 0f },
            targetRatios = TaskMode.TRACKED.associateWith { 1f / TaskMode.TRACKED.size },
            dominantMode = TaskMode.UNCATEGORIZED,
            totalTracked = 0
        )
    }
}

/**
 * Optional user-set targets for the mode balance bar. Defaults to an even
 * split. Targets do not drive overload notifications — the philosophy doc
 * is explicit that mode is descriptive-only — they only colour the
 * progress bars to indicate the user's chosen mix.
 */
data class ModeBalanceConfig(val workTarget: Float = 1f / 3f, val playTarget: Float = 1f / 3f, val relaxTarget: Float = 1f / 3f) {
    fun asMap(): Map<TaskMode, Float> = mapOf(
        TaskMode.WORK to workTarget,
        TaskMode.PLAY to playTarget,
        TaskMode.RELAX to relaxTarget
    )

    fun isValid(): Boolean {
        val sum = workTarget + playTarget + relaxTarget
        return kotlin.math.abs(sum - 1f) < 0.01f
    }
}

/**
 * Pure-function balance computation for the [TaskMode] dimension.
 *
 * Window cutoffs respect the user-configured Start-of-Day, mirroring the
 * SoD-aware cutoff in [BalanceTracker] (see PR #1060). Callers without
 * access to the SoD preference fall back to system midnight
 * (`dayStartHour = 0`).
 */
class ModeBalanceTracker {
    fun compute(
        allTasks: List<TaskEntity>,
        config: ModeBalanceConfig = ModeBalanceConfig(),
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        dayStartHour: Int = 0,
        dayStartMinute: Int = 0
    ): ModeBalanceState {
        val weekCutoff = cutoff(now, days = 7, timeZone, dayStartHour, dayStartMinute)
        val monthCutoff = cutoff(now, days = 28, timeZone, dayStartHour, dayStartMinute)

        val current = computeRatios(allTasks, weekCutoff)
        val rolling = computeRatios(allTasks, monthCutoff)
        val total = countTracked(allTasks, weekCutoff)

        val dominant = if (total == 0) {
            TaskMode.UNCATEGORIZED
        } else {
            current.maxByOrNull { it.value }?.key ?: TaskMode.UNCATEGORIZED
        }

        return ModeBalanceState(
            currentRatios = current,
            rollingRatios = rolling,
            targetRatios = config.asMap(),
            dominantMode = dominant,
            totalTracked = total
        )
    }

    private fun computeRatios(tasks: List<TaskEntity>, cutoff: Long): Map<TaskMode, Float> {
        val counts = TaskMode.TRACKED.associateWith { 0 }.toMutableMap()
        var total = 0
        for (t in tasks) {
            val ts = timestampFor(t)
            if (ts < cutoff) continue
            val mode = TaskMode.fromStorage(t.taskMode)
            if (mode == TaskMode.UNCATEGORIZED) continue
            counts[mode] = (counts[mode] ?: 0) + 1
            total++
        }
        if (total == 0) return TaskMode.TRACKED.associateWith { 0f }
        return counts.mapValues { (_, count) -> count.toFloat() / total.toFloat() }
    }

    private fun countTracked(tasks: List<TaskEntity>, cutoff: Long): Int {
        var total = 0
        for (t in tasks) {
            val ts = timestampFor(t)
            if (ts < cutoff) continue
            val mode = TaskMode.fromStorage(t.taskMode)
            if (mode == TaskMode.UNCATEGORIZED) continue
            total++
        }
        return total
    }

    private fun timestampFor(task: TaskEntity): Long = task.completedAt ?: task.dueDate ?: task.createdAt

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
