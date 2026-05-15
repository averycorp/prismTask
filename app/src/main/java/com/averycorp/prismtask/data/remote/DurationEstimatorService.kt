package com.averycorp.prismtask.data.remote

import android.util.Log
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.remote.api.EstimateDurationRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pro-only per-task duration estimate. Posts the title + description to the
 * backend, which calls Claude Haiku server-side and returns an integer
 * minutes value clamped to 1..480.
 *
 * Invoked fire-and-forget from
 * [com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskViewModel.save] when
 * the user leaves `estimatedDuration` blank. Free users never reach here —
 * the call site Pro-gates first and Free users inherit
 * `TaskDefaults.defaultDuration` (preset 30 min) for balance + cognitive-load
 * weighting. The service Pro-gates a second time as defence-in-depth.
 *
 * Returns [Result.failure] on no-auth / non-Pro / network errors so the
 * caller can fall back to the deterministic preset without surfacing an
 * error in the UI.
 */
@Singleton
class DurationEstimatorService
@Inject
constructor(
    private val api: PrismTaskApi,
    private val authTokenPreferences: AuthTokenPreferences,
    private val proFeatureGate: ProFeatureGate
) {
    suspend fun estimate(title: String, description: String?): Result<Int> {
        if (!proFeatureGate.isPro()) {
            return Result.failure(IllegalStateException("Duration estimate requires Pro tier"))
        }
        val token = authTokenPreferences.getAccessToken()
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No auth token; offline or logged out"))
        }
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return Result.failure(IllegalArgumentException("Title is blank; nothing to estimate"))
        }
        return try {
            val response = api.estimateTaskDuration(
                EstimateDurationRequest(
                    title = trimmedTitle,
                    description = description?.takeIf { it.isNotBlank() }
                )
            )
            val minutes = response.estimatedMinutes.coerceIn(1, 480)
            Result.success(minutes)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("DurationEstimator", "estimate failed", e)
            Result.failure(e)
        }
    }
}
