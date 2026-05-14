package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.BurnoutWeights
import com.averycorp.prismtask.domain.model.LifeCategory

/**
 * Inputs for [BurnoutScorer] — aggregated stats that drive the 0–100 score.
 *
 * Each field has a sensible default so callers that don't yet have the data
 * available (e.g. mood tracking not enabled, no medication logging) can omit
 * it rather than fudging a zero.
 */
data class BurnoutInputs(
    /** Work category ratio (0.0..1.0) as computed by [BalanceTracker]. */
    val workRatio: Float = 0f,
    /** Target work ratio configured by the user (0.0..1.0). */
    val workTarget: Float = 0.40f,
    /** Number of currently overdue tasks across all categories. */
    val overdueCount: Int = 0,
    /**
     * Fraction of scheduled self-care tasks NOT completed this week (0.0..1.0).
     * 1.0 means every planned self-care task was skipped.
     */
    val skippedSelfCareRatio: Float = 0f,
    /**
     * Fraction of scheduled medication doses missed this week (0.0..1.0).
     * If medication tracking is disabled, pass 0f.
     */
    val medicationGapRatio: Float = 0f,
    /** Number of habits broken this week (strict streak reset). */
    val streakBreaks: Int = 0,
    /**
     * True when the user has not logged any self-care tasks in the last 2 days.
     * Used as a "rest deficit" signal.
     */
    val restDeficit: Boolean = false
)

/**
 * Human-readable interpretation of a burnout score.
 */
enum class BurnoutBand {
    BALANCED, // 0..25
    MONITOR, // 26..50
    CAUTION, // 51..75
    HIGH_RISK; // 76..100

    companion object {
        fun forScore(score: Int): BurnoutBand = when {
            score <= 25 -> BALANCED
            score <= 50 -> MONITOR
            score <= 75 -> CAUTION
            else -> HIGH_RISK
        }

        fun label(band: BurnoutBand): String = when (band) {
            BALANCED -> "Balanced"
            MONITOR -> "Monitor"
            CAUTION -> "Caution"
            HIGH_RISK -> "High Risk"
        }
    }
}

/**
 * Result bundle exposing both the composite score and per-component
 * contributions so the UI can explain *why* a user is in a particular band.
 */
data class BurnoutResult(
    val score: Int,
    val band: BurnoutBand,
    val workOvershootPoints: Int,
    val overduePoints: Int,
    val skippedSelfCarePoints: Int,
    val medicationPoints: Int,
    val streakBreakPoints: Int,
    val restDeficitPoints: Int
) {
    companion object {
        val EMPTY = BurnoutResult(0, BurnoutBand.BALANCED, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * Pure-function burnout composite scorer (v1.4.0 V2).
 *
 * The score is a weighted sum of 6 inputs, each capped at its individual
 * ceiling so no single signal can dominate:
 *
 *  - Work-ratio overshoot       → up to 25 points
 *  - Overdue task count         → up to 20 points
 *  - Skipped self-care          → up to 20 points
 *  - Medication adherence gap   → up to 15 points
 *  - Habit streak breaks        → up to 10 points
 *  - 2-day rest deficit         → up to 10 points
 *
 * Total max = 100. The resulting [BurnoutBand] is `BALANCED` / `MONITOR` /
 * `CAUTION` / `HIGH_RISK`. The UI renders a gauge + a descriptive caption
 * like "Balanced" or "High risk" — no prescriptive advice ("consider doing
 * X"), per WORK_PLAY_RELAX.md § "Descriptive, not prescriptive".
 */
class BurnoutScorer(
    private val weights: BurnoutWeights = BurnoutWeights()
) {
    fun compute(inputs: BurnoutInputs): BurnoutResult {
        val workPoints = workOvershoot(inputs.workRatio, inputs.workTarget)
        val overduePoints = overdueComponent(inputs.overdueCount)
        val selfCarePoints = (inputs.skippedSelfCareRatio.coerceIn(0f, 1f) * weights.selfCareMax).toInt()
        val medicationPoints = (inputs.medicationGapRatio.coerceIn(0f, 1f) * weights.medicationMax).toInt()
        val streakPoints = streakBreakComponent(inputs.streakBreaks)
        val restDeficitPoints = if (inputs.restDeficit) weights.restDeficitMax else 0

        val total = (
            workPoints + overduePoints + selfCarePoints + medicationPoints +
                streakPoints + restDeficitPoints
            ).coerceIn(0, 100)

        return BurnoutResult(
            score = total,
            band = BurnoutBand.forScore(total),
            workOvershootPoints = workPoints,
            overduePoints = overduePoints,
            skippedSelfCarePoints = selfCarePoints,
            medicationPoints = medicationPoints,
            streakBreakPoints = streakPoints,
            restDeficitPoints = restDeficitPoints
        )
    }

    /**
     * Convenience overload: derive [BurnoutInputs] from the raw pieces the
     * rest of the app already has (task list, target ratio, current work
     * ratio from [BalanceTracker]). Any unavailable signal defaults to 0.
     */
    fun computeFromTasks(
        tasks: List<TaskEntity>,
        workRatio: Float,
        workTarget: Float,
        now: Long = System.currentTimeMillis()
    ): BurnoutResult {
        val overdue = tasks.count { t ->
            !t.isCompleted &&
                t.archivedAt == null &&
                t.dueDate != null &&
                t.dueDate < now
        }

        // Self-care tasks this week: how many were completed vs. total.
        val selfCareThisWeek = tasks.filter { task ->
            LifeCategory.fromStorage(task.lifeCategory) == LifeCategory.SELF_CARE &&
                task.dueDate != null &&
                task.dueDate >= now - SEVEN_DAYS_MILLIS
        }
        val skippedSelfCareRatio = if (selfCareThisWeek.isEmpty()) {
            0f
        } else {
            val skipped = selfCareThisWeek.count { !it.isCompleted }
            skipped.toFloat() / selfCareThisWeek.size.toFloat()
        }

        // Rest deficit: no self-care tasks completed in the last [restDeficitDays] days.
        val twoDaysAgo = now - weights.restDeficitDays.toLong() * 24L * 60 * 60 * 1000
        val selfCareCompletedRecently = tasks.any { task ->
            LifeCategory.fromStorage(task.lifeCategory) == LifeCategory.SELF_CARE &&
                task.isCompleted &&
                (task.completedAt ?: 0L) >= twoDaysAgo
        }

        return compute(
            BurnoutInputs(
                workRatio = workRatio,
                workTarget = workTarget,
                overdueCount = overdue,
                skippedSelfCareRatio = skippedSelfCareRatio,
                medicationGapRatio = 0f,
                streakBreaks = 0,
                restDeficit = !selfCareCompletedRecently && selfCareThisWeek.isNotEmpty()
            )
        )
    }

    private fun workOvershoot(actual: Float, target: Float): Int {
        // workMax points scaled linearly from 0 (at target) to workMax (target+40%).
        if (actual <= target) return 0
        val overshoot = (actual - target).coerceIn(0f, 0.40f)
        return ((overshoot / 0.40f) * weights.workMax).toInt()
    }

    private fun overdueComponent(count: Int): Int {
        // overdueMax points scaled at 10+ overdue tasks.
        if (count <= 0) return 0
        val scaled = count.coerceAtMost(10).toFloat() / 10f
        return (scaled * weights.overdueMax).toInt()
    }

    private fun streakBreakComponent(count: Int): Int {
        // streakMax points scaled at 5+ broken streaks.
        if (count <= 0) return 0
        val scaled = count.coerceAtMost(5).toFloat() / 5f
        return (scaled * weights.streakMax).toInt()
    }

    companion object {
        internal const val WORK_MAX = 25
        internal const val OVERDUE_MAX = 20
        internal const val SELF_CARE_MAX = 20
        internal const val MEDICATION_MAX = 15
        internal const val STREAK_MAX = 10
        internal const val REST_DEFICIT_MAX = 10

        private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val TWO_DAYS_MILLIS = 2L * 24 * 60 * 60 * 1000
    }
}
