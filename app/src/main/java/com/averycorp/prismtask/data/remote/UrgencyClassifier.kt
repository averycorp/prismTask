package com.averycorp.prismtask.data.remote

import android.util.Log
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.UrgencyScoreInputDto
import com.averycorp.prismtask.data.remote.api.UrgencyScoreRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scores a batch of tasks for urgency by sending raw task fields to the
 * backend, which calls Claude Haiku server-side. Users never supply
 * their own Claude API key — same pattern as [EisenhowerClassifier].
 *
 * Offline-safe: returns [Result.failure] on any error (no session,
 * network failure, 5xx, malformed response). Callers must fall back to
 * the on-device [com.averycorp.prismtask.domain.usecase.UrgencyScorer]
 * formula per-task; never block sort rendering on this call.
 */
@Singleton
class UrgencyClassifier
@Inject
constructor(
    private val api: PrismTaskApi,
    private val authTokenPreferences: AuthTokenPreferences
) {
    /** Map of local task id → AI-determined urgency score in `[0f, 1f]`. */
    suspend fun scoreBatch(
        tasks: List<TaskEntity>,
        subtaskCounts: Map<Long, Pair<Int, Int>> = emptyMap()
    ): Result<Map<Long, Float>> {
        if (tasks.isEmpty()) return Result.success(emptyMap())
        val token = authTokenPreferences.getAccessToken()
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No auth token; offline or logged out"))
        }
        // Backend caps batch size at 50 — slice to stay under the cap.
        val window = tasks.take(MAX_BATCH_SIZE)
        return try {
            val response = api.scoreUrgency(
                UrgencyScoreRequest(
                    tasks = window.map { task ->
                        val (count, completed) = subtaskCounts[task.id] ?: (0 to 0)
                        UrgencyScoreInputDto(
                            id = task.id.toString(),
                            title = task.title,
                            description = task.description,
                            dueDate = task.dueDate?.let { isoDate.format(Date(it)) },
                            priority = task.priority,
                            createdAt = isoDate.format(Date(task.createdAt)),
                            subtaskCount = count,
                            subtaskCompleted = completed
                        )
                    }
                )
            )
            val scores = response.scores.mapNotNull { entry ->
                val id = entry.id.toLongOrNull() ?: return@mapNotNull null
                id to entry.score.coerceIn(0f, 1f)
            }.toMap()
            Result.success(scores)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("UrgencyClassifier", "scoreBatch failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 50
        val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
