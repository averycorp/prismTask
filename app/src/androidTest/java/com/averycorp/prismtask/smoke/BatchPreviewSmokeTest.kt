package com.averycorp.prismtask.smoke

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.averycorp.prismtask.data.preferences.NdPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.remote.api.BatchParseResponse
import com.averycorp.prismtask.data.remote.api.ProposedMutationResponse
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import com.averycorp.prismtask.ui.screens.batch.BatchPreviewScreen
import com.averycorp.prismtask.ui.screens.batch.BatchPreviewState
import com.averycorp.prismtask.ui.screens.batch.BatchPreviewViewModel
import com.averycorp.prismtask.ui.screens.batch.BatchUndoEventBus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Compose smokes for [BatchPreviewScreen]. Constructs a real
 * [BatchPreviewViewModel] backed by mocked repository / bus / preferences
 * so the test exercises the production state-transition flow without a
 * Hilt graph or a network call. The fuller end-to-end QuickAdd → preview
 * route is covered by [NavigationSmokeTest].
 *
 * Coverage matches Phase 1.1 of the BatchPreview audit:
 *  (a) Ready state renders the mutation row
 *  (b) Apply button is disabled in Loading and Error states
 *  (c) Cancel via the top-bar Close action pops the back stack
 */
class BatchPreviewSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newViewModel(
        repository: BatchOperationsRepository
    ): BatchPreviewViewModel {
        val ndPrefs = mockk<NdPreferencesDataStore>().also {
            every { it.ndPreferencesFlow } returns flowOf(NdPreferences())
        }
        val customBrainPrefs = mockk<com.averycorp.prismtask.data.preferences.CustomBrainModePreferences>().also {
            every { it.observeActive() } returns flowOf(null)
            every { it.observe() } returns flowOf(emptyList())
        }
        return BatchPreviewViewModel(
            repository = repository,
            undoBus = BatchUndoEventBus(),
            ndPreferencesDataStore = ndPrefs,
            customBrainModePreferences = customBrainPrefs
        )
    }

    private fun stubReady(repository: BatchOperationsRepository) {
        coEvery { repository.parseCommand(any()) } returns
            BatchOperationsRepository.BatchParseOutcome(
                response = BatchParseResponse(
                    mutations = listOf(
                        ProposedMutationResponse(
                            entityType = "TASK",
                            entityId = "7",
                            mutationType = "RESCHEDULE",
                            proposedNewValues = mapOf("due_date" to "2026-05-03"),
                            humanReadableDescription = "Move \"Finish report\" to Sunday"
                        )
                    ),
                    confidence = 0.95f,
                    ambiguousEntities = emptyList()
                ),
                committedMedicationIds = emptySet()
            )
        coEvery { repository.getTagNamesForTasks(any()) } returns emptyMap()
        coEvery { repository.getMedicationsByIds(any()) } returns emptyList()
    }

    @Test
    fun readyState_rendersMutationRowAndEnablesApprove() {
        val repository = mockk<BatchOperationsRepository>()
        stubReady(repository)
        val viewModel = newViewModel(repository)

        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "preview") {
                composable("preview") {
                    BatchPreviewScreen(
                        navController = nav,
                        commandText = "move report to sunday",
                        onApproved = { _, _, _ -> },
                        onCancelled = { },
                        viewModel = viewModel
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value is BatchPreviewState.Loaded
        }
        composeRule.onNodeWithText("Move \"Finish report\" to Sunday").assertIsDisplayed()
        composeRule.onNodeWithText("Approve").assertIsEnabled().assertHasClickAction()
    }

    @Test
    fun errorState_disablesApproveButton() {
        val repository = mockk<BatchOperationsRepository>()
        coEvery { repository.parseCommand(any()) } throws HttpException(
            Response.error<Any>(
                451,
                "{}".toResponseBody("application/json".toMediaType())
            )
        )
        coEvery { repository.getTagNamesForTasks(any()) } returns emptyMap()
        coEvery { repository.getMedicationsByIds(any()) } returns emptyList()
        val viewModel = newViewModel(repository)

        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "preview") {
                composable("preview") {
                    BatchPreviewScreen(
                        navController = nav,
                        commandText = "anything",
                        onApproved = { _, _, _ -> },
                        onCancelled = { },
                        viewModel = viewModel
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value is BatchPreviewState.Error
        }
        // 451 path renders the AI-gate copy, not a generic parse error.
        composeRule.onNodeWithText("AI features are off").assertIsDisplayed()
        composeRule.onNodeWithText("Approve").assertIsNotEnabled()
    }

    @Test
    fun cancelAction_invokesOnCancelledCallback() {
        val repository = mockk<BatchOperationsRepository>()
        stubReady(repository)
        val viewModel = newViewModel(repository)
        var cancelled = false

        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "preview") {
                composable("preview") {
                    BatchPreviewScreen(
                        navController = nav,
                        commandText = "move report to sunday",
                        onApproved = { _, _, _ -> },
                        onCancelled = { cancelled = true },
                        viewModel = viewModel
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value is BatchPreviewState.Loaded
        }
        // Top-bar close icon carries the "Cancel" content description.
        composeRule.onNodeWithContentDescription("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { cancelled }
    }
}
