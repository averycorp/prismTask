package com.averycorp.prismtask.ui.screens.screenshotimport

import com.averycorp.prismtask.data.remote.api.ExtractedTaskCandidateResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.VisionExtractRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the Vision extract endpoint. Kept separate from the
 * ViewModel so it can be swapped for a fake in unit tests without
 * pulling in the Retrofit interface.
 */
@Singleton
class ScreenshotImportRepository
@Inject
constructor(
    private val api: PrismTaskApi
) {
    suspend fun extractTasksFromScreenshot(
        imageBase64: String,
        imageMediaType: String
    ): List<ExtractedTaskCandidateResponse> {
        val response = api.extractTasksFromImage(
            VisionExtractRequest(
                imageBase64 = imageBase64,
                imageMediaType = imageMediaType
            )
        )
        return response.tasks
    }
}
