package com.averycorp.prismtask.data.remote

import android.util.Log
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.remote.api.LifeCategoryClassifyTextRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.model.LifeCategory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies a single task into a Work-Life Balance [LifeCategory] by sending
 * raw task text to the backend, which calls Claude Haiku server-side. Same
 * shape and offline semantics as [EisenhowerClassifier]: on any error
 * (no auth, network, 5xx, 429, 451, malformed response) returns
 * [Result.failure] so the on-device keyword classifier remains the source
 * of truth.
 *
 * Invoked from the OrganizeTab "Auto" button via
 * [com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskViewModel.autoPickLifeCategory].
 */
@Singleton
class LifeCategoryRemoteClassifier
@Inject
constructor(
    private val api: PrismTaskApi,
    private val authTokenPreferences: AuthTokenPreferences
) {
    data class Classification(
        val category: LifeCategory,
        val reason: String
    )

    suspend fun classify(title: String, description: String?): Result<Classification> {
        val token = authTokenPreferences.getAccessToken()
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No auth token; offline or logged out"))
        }
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return Result.failure(IllegalArgumentException("Title is blank; nothing to classify"))
        }
        return try {
            val response = api.classifyLifeCategoryText(
                LifeCategoryClassifyTextRequest(
                    title = trimmedTitle,
                    description = description?.takeIf { it.isNotBlank() }
                )
            )
            val category = try {
                LifeCategory.valueOf(response.category)
            } catch (_: IllegalArgumentException) {
                return Result.failure(
                    IllegalStateException("Unknown life category: ${response.category}")
                )
            }
            Result.success(Classification(category, response.reason))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("LifeCategoryRemote", "classify failed", e)
            Result.failure(e)
        }
    }
}
