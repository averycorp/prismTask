package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.preferences.MoodCorrelationConfig
import kotlin.math.sqrt

/**
 * Daily observation bundle fed into [MoodCorrelationEngine]. Each entry
 * represents a single calendar day with whatever signals the caller has
 * available — missing ones default to zero.
 */
data class DailyObservation(
    val date: Long,
    val mood: Int,
    val energy: Int,
    val tasksCompleted: Int = 0,
    val workTasksCompleted: Int = 0,
    val selfCareTasksCompleted: Int = 0,
    val habitCompletionRate: Float = 0f,
    val medicationAdherence: Float = 0f,
    val burnoutScore: Int = 0
)

/** Named input for correlation against mood or energy. */
enum class CorrelationFactor {
    TASKS_COMPLETED,
    WORK_TASKS_COMPLETED,
    SELF_CARE_TASKS_COMPLETED,
    HABIT_COMPLETION_RATE,
    MEDICATION_ADHERENCE,
    BURNOUT_SCORE
}

/**
 * Result of a single correlation computation. [coefficient] is the Pearson
 * coefficient in [-1, 1]. A positive value means the factor tends to rise
 * with mood/energy, negative the opposite. [strength] bucket aids UI
 * rendering.
 */
data class CorrelationResult(
    val factor: CorrelationFactor,
    val targetLabel: String,
    val coefficient: Float,
    val strength: CorrelationStrength
) {
    fun plainEnglish(): String {
        val direction = if (coefficient >= 0) "higher" else "lower"
        val factorLabel = when (factor) {
            CorrelationFactor.TASKS_COMPLETED -> "total task completions"
            CorrelationFactor.WORK_TASKS_COMPLETED -> "work tasks"
            CorrelationFactor.SELF_CARE_TASKS_COMPLETED -> "self-care tasks"
            CorrelationFactor.HABIT_COMPLETION_RATE -> "habit completion rate"
            CorrelationFactor.MEDICATION_ADHERENCE -> "medication adherence"
            CorrelationFactor.BURNOUT_SCORE -> "burnout score"
        }
        return "Your $targetLabel tends to be $direction on days with more $factorLabel (${"%.2f".format(coefficient)})."
    }
}

enum class CorrelationStrength { WEAK, MODERATE, STRONG }

/**
 * Mood + energy correlation analyzer (v1.4.0 V7).
 *
 * Computes Pearson correlation between a mood (or energy) series and each
 * supported [CorrelationFactor] over a rolling window. A minimum of 7 daily
 * observations is required — below that, the correlation is considered too
 * noisy to report and we return an empty list.
 *
 * Pure function: takes pre-aggregated [DailyObservation]s and returns
 * [CorrelationResult]s. The caller is responsible for stitching mood logs
 * together with task/habit/med stats into observations.
 */
class MoodCorrelationEngine(private val config: MoodCorrelationConfig = MoodCorrelationConfig()) {
    /**
     * Correlate mood against every supported factor. Returns a list sorted
     * by absolute coefficient (strongest first).
     */
    fun correlateMood(observations: List<DailyObservation>): List<CorrelationResult> {
        if (observations.size < config.minObservations) return emptyList()
        val moods = observations.map { it.mood.toFloat() }
        return CorrelationFactor
            .values()
            .map { factor ->
                val values = observations.map { it.extract(factor) }
                val coef = pearson(moods, values)
                CorrelationResult(
                    factor = factor,
                    targetLabel = "mood",
                    coefficient = coef,
                    strength = bucket(coef)
                )
            }.sortedByDescending { kotlin.math.abs(it.coefficient) }
    }

    /** Same as [correlateMood] but for energy. */
    fun correlateEnergy(observations: List<DailyObservation>): List<CorrelationResult> {
        if (observations.size < config.minObservations) return emptyList()
        val energies = observations.map { it.energy.toFloat() }
        return CorrelationFactor
            .values()
            .map { factor ->
                val values = observations.map { it.extract(factor) }
                val coef = pearson(energies, values)
                CorrelationResult(
                    factor = factor,
                    targetLabel = "energy",
                    coefficient = coef,
                    strength = bucket(coef)
                )
            }.sortedByDescending { kotlin.math.abs(it.coefficient) }
    }

    /**
     * Stitches raw [MoodEnergyLogEntity] rows into per-day averages that
     * the correlation routines can consume. Days with both morning and
     * evening entries are averaged.
     */
    fun averageByDay(logs: List<MoodEnergyLogEntity>): Map<Long, Pair<Float, Float>> {
        if (logs.isEmpty()) return emptyMap()
        val grouped = logs.groupBy { it.date }
        return grouped.mapValues { (_, entries) ->
            val avgMood = entries.map { it.mood }.average().toFloat()
            val avgEnergy = entries.map { it.energy }.average().toFloat()
            avgMood to avgEnergy
        }
    }

    private fun DailyObservation.extract(factor: CorrelationFactor): Float = when (factor) {
        CorrelationFactor.TASKS_COMPLETED -> tasksCompleted.toFloat()
        CorrelationFactor.WORK_TASKS_COMPLETED -> workTasksCompleted.toFloat()
        CorrelationFactor.SELF_CARE_TASKS_COMPLETED -> selfCareTasksCompleted.toFloat()
        CorrelationFactor.HABIT_COMPLETION_RATE -> habitCompletionRate
        CorrelationFactor.MEDICATION_ADHERENCE -> medicationAdherence
        CorrelationFactor.BURNOUT_SCORE -> burnoutScore.toFloat()
    }

    /**
     * Classic Pearson correlation coefficient. Returns 0 when variance is
     * zero (e.g., all mood values identical) so the UI doesn't report a
     * misleading "perfect correlation" on a flat series.
     */
    internal fun pearson(xs: List<Float>, ys: List<Float>): Float {
        if (xs.size != ys.size || xs.isEmpty()) return 0f
        val n = xs.size
        val meanX = xs.average().toFloat()
        val meanY = ys.average().toFloat()
        var num = 0f
        var denomX = 0f
        var denomY = 0f
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            num += dx * dy
            denomX += dx * dx
            denomY += dy * dy
        }
        if (denomX == 0f || denomY == 0f) return 0f
        val denom = sqrt(denomX * denomY)
        return (num / denom).coerceIn(-1f, 1f)
    }

    private fun bucket(coef: Float): CorrelationStrength {
        val abs = kotlin.math.abs(coef)
        return when {
            abs >= config.strongThreshold -> CorrelationStrength.STRONG
            abs >= config.moderateThreshold -> CorrelationStrength.MODERATE
            else -> CorrelationStrength.WEAK
        }
    }

    companion object {
        const val MIN_OBSERVATIONS = 7
    }
}
