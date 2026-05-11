package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.domain.model.CognitiveLoad
import java.util.Calendar
import java.util.TimeZone

/**
 * Snapshot of the user's current cognitive-load balance — the
 * Easy / Medium / Hard ratio version of [BalanceState] /
 * [ModeBalanceState], orthogonal to both life-category balance and
 * task-mode balance.
 *
 * Numbers are descriptive, not prescriptive (see
 * `docs/COGNITIVE_LOAD.md`). The dimension exists so the user can read
 * "this week was 90% easy" or "this week was 80% hard" and decide
 * themselves — bidirectional imbalance (procrastination via too-easy or
 * burnout via too-hard) is a *visual* signal, not a notification.
 */
data class CognitiveLoadBalanceState(
    val currentRatios: Map<CognitiveLoad, Float>,
    val rollingRatios: Map<CognitiveLoad, Float>,
    val targetRatios: Map<CognitiveLoad, Float>,
    val dominantLoad: CognitiveLoad,
    val totalTracked: Int
) {
    companion object {
        val EMPTY = CognitiveLoadBalanceState(
            currentRatios = CognitiveLoad.TRACKED.associateWith { 0f },
            rollingRatios = CognitiveLoad.TRACKED.associateWith { 0f },
            targetRatios = CognitiveLoad.TRACKED.associateWith { 1f / CognitiveLoad.TRACKED.size },
            dominantLoad = CognitiveLoad.UNCATEGORIZED,
            totalTracked = 0
        )
    }
}

/**
 * Optional user-set targets for the cognitive-load balance bar. Defaults
 * to an even split. Targets do not drive overload notifications — the
 * philosophy doc is explicit that load is descriptive-only — they only
 * colour the progress bars to indicate the user's chosen mix.
 */
data class CognitiveLoadBalanceConfig(val easyTarget: Float = 1f / 3f, val mediumTarget: Float = 1f / 3f, val hardTarget: Float = 1f / 3f) {
    fun asMap(): Map<CognitiveLoad, Float> = mapOf(
        CognitiveLoad.EASY to easyTarget,
        CognitiveLoad.MEDIUM to mediumTarget,
        CognitiveLoad.HARD to hardTarget
    )

    fun isValid(): Boolean {
        val sum = easyTarget + mediumTarget + hardTarget
        return kotlin.math.abs(sum - 1f) < 0.01f
    }
}

/**
 * Pure-function balance computation for the [CognitiveLoad] dimension.
 *
 * Window cutoffs respect the user-configured Start-of-Day, mirroring the
 * SoD-aware cutoff in [BalanceTracker] / [ModeBalanceTracker] (see
 * PR #1060). Callers without access to the SoD preference fall back to
 * system midnight (`dayStartHour = 0`).
 */
class CognitiveLoadBalanceTracker {
    fun compute(
        allTasks: List<TaskEntity>,
        config: CognitiveLoadBalanceConfig = CognitiveLoadBalanceConfig(),
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        dayStartHour: Int = 0,
        dayStartMinute: Int = 0
    ): CognitiveLoadBalanceState {
        val weekCutoff = cutoff(now, days = 7, timeZone, dayStartHour, dayStartMinute)
        val monthCutoff = cutoff(now, days = 28, timeZone, dayStartHour, dayStartMinute)

        val current = computeRatios(allTasks, weekCutoff)
        val rolling = computeRatios(allTasks, monthCutoff)
        val total = countTracked(allTasks, weekCutoff)

        val dominant = if (total == 0) {
            CognitiveLoad.UNCATEGORIZED
        } else {
            current.maxByOrNull { it.value }?.key ?: CognitiveLoad.UNCATEGORIZED
        }

        return CognitiveLoadBalanceState(
            currentRatios = current,
            rollingRatios = rolling,
            targetRatios = config.asMap(),
            dominantLoad = dominant,
            totalTracked = total
        )
    }

    private fun computeRatios(tasks: List<TaskEntity>, cutoff: Long): Map<CognitiveLoad, Float> {
        val counts = CognitiveLoad.TRACKED.associateWith { 0 }.toMutableMap()
        var total = 0
        for (t in tasks) {
            val ts = timestampFor(t)
            if (ts < cutoff) continue
            val load = CognitiveLoad.fromStorage(t.cognitiveLoad)
            if (load == CognitiveLoad.UNCATEGORIZED) continue
            counts[load] = (counts[load] ?: 0) + 1
            total++
        }
        if (total == 0) return CognitiveLoad.TRACKED.associateWith { 0f }
        return counts.mapValues { (_, count) -> count.toFloat() / total.toFloat() }
    }

    private fun countTracked(tasks: List<TaskEntity>, cutoff: Long): Int {
        var total = 0
        for (t in tasks) {
            val ts = timestampFor(t)
            if (ts < cutoff) continue
            val load = CognitiveLoad.fromStorage(t.cognitiveLoad)
            if (load == CognitiveLoad.UNCATEGORIZED) continue
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
