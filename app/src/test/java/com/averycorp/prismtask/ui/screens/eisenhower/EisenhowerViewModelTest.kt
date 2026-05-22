package com.averycorp.prismtask.ui.screens.eisenhower

import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.remote.api.EisenhowerCategorization
import com.averycorp.prismtask.data.remote.api.EisenhowerResponse
import com.averycorp.prismtask.data.remote.api.EisenhowerSummary
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.EisenhowerQuadrant
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EisenhowerViewModel]. Covers the Pro feature gate,
 * manual quadrant moves, completion, AI categorize wiring, and the
 * new sealed [EisenhowerUiState] including the Empty state for
 * empty-response UX.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EisenhowerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var api: PrismTaskApi
    private lateinit var taskRepository: TaskRepository
    private lateinit var proFeatureGate: ProFeatureGate

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        proFeatureGate = mockk(relaxed = true)

        every { taskRepository.getIncompleteRootTasks() } returns flowOf(emptyList())
        every { proFeatureGate.userTier } returns MutableStateFlow(UserTier.FREE)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = EisenhowerViewModel(api, taskRepository, proFeatureGate, io.mockk.mockk(relaxed = true))

    @Test
    fun categorize_freeTierShowsUpgradePromptAndSkipsApiCall() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns false

        val vm = newViewModel()
        advanceUntilIdle()

        vm.categorize()
        advanceUntilIdle()

        assertTrue(vm.showUpgradePrompt.value)
        coVerify(exactly = 0) { api.categorizeEisenhower(any()) }
        // No API call attempted, so state stays Idle.
        assertEquals(EisenhowerUiState.Idle, vm.uiState.value)
    }

    @Test
    fun categorize_proTierCallsApiAndUpdatesTaskQuadrants() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns true
        coEvery { taskRepository.getIdByCloudId("abc-firestore-id") } returns 1L
        coEvery { taskRepository.getIdByCloudId("def-firestore-id") } returns 2L
        coEvery { api.categorizeEisenhower(any()) } returns EisenhowerResponse(
            categorizations = listOf(
                EisenhowerCategorization(taskId = "abc-firestore-id", quadrant = "Q1", reason = "Due today"),
                EisenhowerCategorization(taskId = "def-firestore-id", quadrant = "Q2", reason = "Important")
            ),
            summary = EisenhowerSummary()
        )

        val vm = newViewModel()
        advanceUntilIdle()

        vm.categorize()
        advanceUntilIdle()

        coVerify {
            taskRepository.updateEisenhowerQuadrant(id = 1L, quadrant = "Q1", reason = "Due today")
        }
        coVerify {
            taskRepository.updateEisenhowerQuadrant(id = 2L, quadrant = "Q2", reason = "Important")
        }
        val state = vm.uiState.value
        assertTrue("expected Success, got $state", state is EisenhowerUiState.Success)
    }

    @Test
    fun categorize_emptyResponseEmitsEmptyState() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns true
        coEvery { api.categorizeEisenhower(any()) } returns EisenhowerResponse(
            categorizations = emptyList(),
            summary = EisenhowerSummary()
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.categorize()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("expected Empty, got $state", state is EisenhowerUiState.Empty)
        assertEquals(
            "No incomplete tasks to categorize. Add a task and try again.",
            (state as EisenhowerUiState.Empty).reason
        )
    }

    @Test
    fun categorize_apiFailureEmitsErrorState() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns true
        coEvery { api.categorizeEisenhower(any()) } throws RuntimeException("network down")

        val vm = newViewModel()
        advanceUntilIdle()

        vm.categorize()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("expected Error, got $state", state is EisenhowerUiState.Error)
        assertEquals("Couldn't categorize tasks", (state as EisenhowerUiState.Error).message)
    }

    @Test
    fun moveTaskToQuadrant_delegatesToRepositorySetQuadrantManual() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.moveTaskToQuadrant(taskId = 42L, quadrant = "Q3")
        advanceUntilIdle()

        coVerify {
            taskRepository.setQuadrantManual(
                taskId = 42L,
                quadrant = EisenhowerQuadrant.URGENT_NOT_IMPORTANT
            )
        }
    }

    @Test
    fun reclassify_proTierDelegatesToRepository() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns true
        coEvery { taskRepository.reclassify(99L) } returns Result.success(Unit)

        val vm = newViewModel()
        advanceUntilIdle()

        vm.reclassify(99L)
        advanceUntilIdle()

        coVerify { taskRepository.reclassify(99L) }
        assertEquals(EisenhowerUiState.Idle, vm.uiState.value)
    }

    @Test
    fun reclassify_freeTierShowsUpgradePromptAndSkipsRepository() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns false

        val vm = newViewModel()
        advanceUntilIdle()

        vm.reclassify(99L)
        advanceUntilIdle()

        assertTrue(vm.showUpgradePrompt.value)
        coVerify(exactly = 0) { taskRepository.reclassify(any()) }
    }

    @Test
    fun reclassify_aiFailureSurfacesErrorState() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns true
        coEvery { taskRepository.reclassify(99L) } returns
            Result.failure(IllegalStateException("Backend down"))

        val vm = newViewModel()
        advanceUntilIdle()

        vm.reclassify(99L)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is EisenhowerUiState.Error)
    }

    @Test
    fun completeTask_routesThroughTaskRepository() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.completeTask(42L)
        advanceUntilIdle()

        // Audit: docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md (Item 3).
        // Going through TaskRepository is what spawns the recurrence's next
        // occurrence, cancels the reminder, and triggers sync/widget updates.
        // The pre-fix path called `taskDao.markCompleted` directly and skipped
        // every one of those side effects.
        coVerify { taskRepository.completeTask(42L) }
    }

    @Test
    fun expandQuadrant_updatesStateFlow() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.expandQuadrant("Q1")
        assertEquals("Q1", vm.expandedQuadrant.value)

        vm.expandQuadrant(null)
        assertNull(vm.expandedQuadrant.value)
    }

    @Test
    fun dismissUiMessage_returnsErrorStateToIdle() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns true
        coEvery { api.categorizeEisenhower(any()) } throws RuntimeException("boom")

        val vm = newViewModel()
        advanceUntilIdle()
        vm.categorize()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is EisenhowerUiState.Error)

        vm.dismissUiMessage()
        assertEquals(EisenhowerUiState.Idle, vm.uiState.value)
    }

    @Test
    fun dismissUpgradePrompt_clearsFlag() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER) } returns false

        val vm = newViewModel()
        advanceUntilIdle()
        vm.categorize()
        advanceUntilIdle()
        assertTrue(vm.showUpgradePrompt.value)

        vm.dismissUpgradePrompt()
        assertFalse(vm.showUpgradePrompt.value)
    }
}
