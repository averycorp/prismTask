package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.remote.api.InAppFeedbackRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts custom in-app rating feedback to `POST /api/v1/feedback/in-app`.
 * Auth header is attached by [AuthInterceptor]; the server rejects
 * unauthenticated calls.
 */
@Singleton
class RatingFeedbackRepository @Inject constructor(
    private val api: PrismTaskApi
) {
    suspend fun submit(
        sentiment: String,
        freeText: String?,
        clientTimestampMs: Long
    ): Result<Long> = runCatching {
        val response = api.submitInAppFeedback(
            InAppFeedbackRequest(
                sentiment = sentiment,
                rating = null,
                freeText = freeText?.takeIf { it.isNotBlank() },
                clientTimestamp = clientTimestampMs
            )
        )
        response.feedbackId
    }
}
