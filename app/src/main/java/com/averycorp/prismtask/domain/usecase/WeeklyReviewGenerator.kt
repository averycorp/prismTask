package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.WeeklyReviewRequest
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.WeeklyReviewRepository
import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of a single auto-generation attempt. Modeled as a sealed result
 * instead of a plain exception so the worker can distinguish "retry later"
 * from "no work to do this week" without parsing error messages.
 */
sealed interface WeeklyReviewGenerationOutcome {
    /** A reviewable row was persisted. */
    data class Generated(val review: WeeklyReviewEntity) : WeeklyReviewGenerationOutcome

    /** Nothing to review — zero completed + zero slipped for the window. */
    data object NoActivity : WeeklyReviewGenerationOutcome

    /** Free-tier user — AI narrative is a Pro feature, so skip the write. */
    data object NotEligible : WeeklyReviewGenerationOutcome

    /** AI backend was unreachable. Caller should retry later. */
    data class BackendUnavailable(val cause: Throwable) : WeeklyReviewGenerationOutcome

    /** Unexpected failure (DB, serialization). Caller may retry. */
    data class Error(val cause: Throwable) : WeeklyReviewGenerationOutcome
}

/**
 * Pulls a week of activity, asks the AI backend for narrative insights,
 * and persists a [WeeklyReviewEntity]. Split out from [WeeklyReviewViewModel]
 * so [com.averycorp.prismtask.notifications.WeeklyReviewWorker] can run the
 * same pipeline on a Sunday-evening timer without standing up a Compose
 * scope.
 *
 * The generator is deliberately "AI-or-skip":
 *  - Free tier returns [WeeklyReviewGenerationOutcome.NotEligible] — the
 *    existing on-demand [WeeklyReviewViewModel] still serves Free users a
 *    rule-based narrative when they open the review screen.
 *  - Pro tier with a failed API call returns
 *    [WeeklyReviewGenerationOutcome.BackendUnavailable] — no row is
 *    written, per the spec's "skip generation for that week" rule. The
 *    worker may retry, and the user can always open the review screen
 *    to trigger on-demand generation manually.
 */
@Singleton
class WeeklyReviewGenerator
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val weeklyReviewRepository: WeeklyReviewRepository,
    private val api: PrismTaskApi,
    private val proFeatureGate: ProFeatureGate
) {
    private val aggregator = WeeklyReviewAggregator()
    private val gson = Gson()

    /**
     * Build and persist a weekly review for the ISO-week containing
     * [referenceMillis]. Idempotent by `week_start_date` (the DAO index
     * upserts on the Monday-midnight key), so two runs in the same week
     * replace rather than duplicate.
     */
    suspend fun generateReview(
        referenceMillis: Long = System.currentTimeMillis()
    ): WeeklyReviewGenerationOutcome {
        return try {
            val tasks = taskRepository.getAllTasksOnce()
            val thisWeek = aggregator.aggregate(tasks, reference = referenceMillis)

            if (thisWeek.completed == 0 && thisWeek.slipped == 0) {
                return WeeklyReviewGenerationOutcome.NoActivity
            }

            if (!proFeatureGate.hasAccess(ProFeatureGate.AI_WEEKLY_REVIEW)) {
                return WeeklyReviewGenerationOutcome.NotEligible
            }

            val response = try {
                api.getWeeklyReview(buildRequest(thisWeek))
            } catch (e: Exception) {
                return WeeklyReviewGenerationOutcome.BackendUnavailable(e)
            }

            val metricsJson = gson.toJson(SerializedMetrics.of(thisWeek))
            val aiInsightsJson = gson.toJson(response)

            weeklyReviewRepository.save(
                weekStart = thisWeek.weekStart,
                metricsJson = metricsJson,
                aiInsightsJson = aiInsightsJson
            )
            val entity = weeklyReviewRepository.getForWeek(thisWeek.weekStart)
                ?: return WeeklyReviewGenerationOutcome.Error(
                    IllegalStateException("Persisted review not found for week ${thisWeek.weekStart}")
                )
            WeeklyReviewGenerationOutcome.Generated(entity)
        } catch (e: Exception) {
            WeeklyReviewGenerationOutcome.Error(e)
        }
    }

    private fun buildRequest(stats: WeeklyReviewStats): WeeklyReviewRequest {
        val zone = ZoneId.systemDefault()
        val weekStartLocal = Instant.ofEpochMilli(stats.weekStart).atZone(zone).toLocalDate()
        val weekEndLocal = Instant.ofEpochMilli(stats.weekEnd - 1).atZone(zone).toLocalDate()
        return WeeklyReviewRequest(
            weekStart = weekStartLocal.format(DateTimeFormatter.ISO_LOCAL_DATE),
            weekEnd = weekEndLocal.format(DateTimeFormatter.ISO_LOCAL_DATE),
            completedTasks = stats.completedTasks.map { it.toSummary(completed = true) },
            slippedTasks = stats.slippedTasks.map { it.toSummary(completed = false) }
        )
    }

    private fun TaskEntity.toSummary(
        completed: Boolean
    ): com.averycorp.prismtask.data.remote.api.WeeklyTaskSummary {
        val completedIso = if (completed && completedAt != null) {
            Instant.ofEpochMilli(completedAt).atOffset(ZoneOffset.UTC).toString()
        } else {
            null
        }
        return com.averycorp.prismtask.data.remote.api.WeeklyTaskSummary(
            taskId = cloudId ?: id.toString(),
            title = title,
            completedAt = completedIso,
            priority = priority,
            eisenhowerQuadrant = eisenhowerQuadrant,
            lifeCategory = lifeCategory,
            projectId = projectId?.toString()
        )
    }

    /**
     * Matches the shape [WeeklyReviewViewModel.persistLocal] writes so
     * existing readers of `metricsJson` don't have to branch on source.
     */
    private data class SerializedMetrics(
        val weekStart: Long,
        val weekEnd: Long,
        val completed: Int,
        val slipped: Int,
        val rescheduled: Int,
        val byCategory: Map<String, Int>
    ) {
        companion object {
            fun of(stats: WeeklyReviewStats): SerializedMetrics = SerializedMetrics(
                weekStart = stats.weekStart,
                weekEnd = stats.weekEnd,
                completed = stats.completed,
                slipped = stats.slipped,
                rescheduled = stats.rescheduled,
                byCategory = stats.byCategory.mapKeys { it.key.name }
            )
        }
    }
}
