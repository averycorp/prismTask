package com.averycorp.prismtask.data.remote

import android.util.Log
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.remote.api.EisenhowerClassifyTextRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.model.EisenhowerQuadrant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies a single task into an Eisenhower quadrant by sending raw task
 * text to the backend, which calls Claude Haiku server-side. Users never
 * supply their own Claude API key — same pattern as [ClaudeParserService]
 * for NLP parsing.
 *
 * Offline-safe: returns [Result.failure] on any error (no session, network
 * failure, 5xx, malformed response). Callers should treat failure as
 * "leave UNCLASSIFIED and try again later"; never block task creation on
 * this call.
 */
@Singleton
class EisenhowerClassifier
@Inject
constructor(private val api: PrismTaskApi, private val authTokenPreferences: AuthTokenPreferences) {
    data class Classification(val quadrant: EisenhowerQuadrant, val reason: String)

    suspend fun classify(task: TaskEntity): Result<Classification> {
        val token = authTokenPreferences.getAccessToken()
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No auth token; offline or logged out"))
        }
        return try {
            val response = api.classifyEisenhowerText(
                EisenhowerClassifyTextRequest(
                    title = task.title,
                    description = task.description,
                    dueDate = task.dueDate?.let { isoDate.format(Date(it)) },
                    priority = task.priority
                )
            )
            val quadrant = EisenhowerQuadrant.fromCode(response.quadrant)
            if (quadrant == EisenhowerQuadrant.UNCLASSIFIED) {
                // Server returned a quadrant string outside the expected set
                // ("Q1".."Q4"). Treat as classification failure so the caller
                // leaves the task in its current state rather than silently
                // overwriting with UNCLASSIFIED.
                return Result.failure(
                    IllegalStateException("Unknown quadrant code: ${response.quadrant}")
                )
            }
            Result.success(Classification(quadrant, response.reason))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("EisenhowerClassifier", "classify failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
