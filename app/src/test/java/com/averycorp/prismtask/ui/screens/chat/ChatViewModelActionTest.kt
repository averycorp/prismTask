package com.averycorp.prismtask.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.repository.ChatRepository
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 fix #3 (audit B.2 idempotency + per-item batch reporting) and
 * fix #4 (audit C.2 snackbar-with-undo on destructive ops). Pins the new
 * shape of [ChatViewModel.executeAction]: actions emit
 * [ChatViewModel.ChatActionResult] entries with optional undo callbacks,
 * and a duplicate tap on the same chip while the first is still in
 * flight is silently dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelActionTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var chatRepository: ChatRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var taskDao: TaskDao
    private lateinit var projectDao: ProjectDao
    private lateinit var habitRepository: HabitRepository
    private lateinit var habitCompletionDao: HabitCompletionDao
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var userPreferencesDataStore: UserPreferencesDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        chatRepository = mockk(relaxed = true) {
            every { messages } returns MutableStateFlow(emptyList())
        }
        taskRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        taskDao = mockk(relaxed = true) {
            coEvery { getTaskByIdOnce(any()) } returns null
        }
        projectDao = mockk(relaxed = true)
        habitRepository = mockk(relaxed = true)
        habitCompletionDao = mockk(relaxed = true)
        proFeatureGate = mockk(relaxed = true) {
            every { userTier } returns MutableStateFlow(UserTier.PRO)
            every { hasAccess(any()) } returns true
        }
        taskBehaviorPreferences = mockk(relaxed = true)
        userPreferencesDataStore = mockk(relaxed = true) {
            coEvery { aiChatDisclosureShownFlow } returns flowOf(true)
            coEvery { aiChatDisclosureShownV2Flow } returns flowOf(true)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ChatViewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(),
        chatRepository = chatRepository,
        taskRepository = taskRepository,
        tagRepository = tagRepository,
        taskDao = taskDao,
        projectDao = projectDao,
        habitRepository = habitRepository,
        habitCompletionDao = habitCompletionDao,
        proFeatureGate = proFeatureGate,
        taskBehaviorPreferences = taskBehaviorPreferences,
        userPreferencesDataStore = userPreferencesDataStore
    )

    @Test
    fun complete_emits_result_with_undo_callback_and_completes_task_once() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            viewModel.executeAction(ChatActionResponse(type = "complete", taskId = "42"))
            advanceUntilIdle()

            val result = awaitItem()
            assertEquals("Task Completed", result.message)
            assertEquals("Undo", result.undoLabel)
            assertNotNull("complete must surface an undo callback", result.undoAction)

            // Invoke the undo callback to confirm it routes through the repository.
            result.undoAction?.invoke()
            coVerify(exactly = 1) { taskRepository.completeTask(42L) }
            coVerify(exactly = 1) { taskRepository.uncompleteTask(42L) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun duplicate_tap_while_in_flight_is_silently_dropped() = runTest(dispatcher) {
        // Hold the first executeAction open so the second tap arrives while
        // the in-flight signature is still set.
        val gate = CompletableDeferred<Unit>()
        coEvery { taskRepository.completeTask(any()) } coAnswers {
            gate.await()
            null
        }

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            // First tap: enters the in-flight set, suspends inside completeTask.
            viewModel.executeAction(ChatActionResponse(type = "complete", taskId = "7"))
            // Second tap on the same chip while the first is still suspended.
            // Must be silently no-oped — no second mutation, no second result.
            viewModel.executeAction(ChatActionResponse(type = "complete", taskId = "7"))
            advanceUntilIdle()

            // Release the first call. Only one result is emitted.
            gate.complete(Unit)
            advanceUntilIdle()

            val result = awaitItem()
            assertEquals("Task Completed", result.message)
            // No follow-up emission for the dropped duplicate tap.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { taskRepository.completeTask(7L) }
    }

    @Test
    fun reschedule_batch_reports_per_item_counts_when_some_fail() = runTest(dispatcher) {
        // Two of three reschedules succeed; the middle one throws.
        coEvery { taskRepository.rescheduleTask(1L, any()) } returns Unit
        coEvery { taskRepository.rescheduleTask(2L, any()) } throws IllegalStateException("boom")
        coEvery { taskRepository.rescheduleTask(3L, any()) } returns Unit
        coEvery { taskDao.getTaskByIdOnce(1L) } returns
            TaskEntity(id = 1L, title = "a", dueDate = 100L, createdAt = 0L, updatedAt = 0L)
        coEvery { taskDao.getTaskByIdOnce(2L) } returns
            TaskEntity(id = 2L, title = "b", dueDate = 200L, createdAt = 0L, updatedAt = 0L)
        coEvery { taskDao.getTaskByIdOnce(3L) } returns
            TaskEntity(id = 3L, title = "c", dueDate = 300L, createdAt = 0L, updatedAt = 0L)

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            viewModel.executeAction(
                ChatActionResponse(
                    type = "reschedule_batch",
                    taskIds = listOf("1", "2", "3"),
                    to = "tomorrow"
                )
            )
            advanceUntilIdle()

            val result = awaitItem()
            assertEquals("Rescheduled 2 of 3 Tasks (1 Failed)", result.message)
            assertNotNull("undo must still be offered when at least one task moved", result.undoAction)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reschedule_batch_undo_restores_each_task_to_original_due_date() = runTest(dispatcher) {
        coEvery { taskDao.getTaskByIdOnce(1L) } returns
            TaskEntity(id = 1L, title = "a", dueDate = 100L, createdAt = 0L, updatedAt = 0L)
        coEvery { taskDao.getTaskByIdOnce(2L) } returns
            TaskEntity(id = 2L, title = "b", dueDate = 200L, createdAt = 0L, updatedAt = 0L)

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            viewModel.executeAction(
                ChatActionResponse(
                    type = "reschedule_batch",
                    taskIds = listOf("1", "2"),
                    to = "tomorrow"
                )
            )
            advanceUntilIdle()

            val result = awaitItem()
            result.undoAction?.invoke()
            advanceUntilIdle()

            // Both reschedules were issued (forward + undo for each id). The
            // undo path must pass each task's ORIGINAL dueDate, not the new one.
            coVerifySequence {
                taskDao.getTaskByIdOnce(1L)
                taskDao.getTaskByIdOnce(2L)
                taskRepository.rescheduleTask(1L, any())
                taskRepository.rescheduleTask(2L, any())
                taskRepository.rescheduleTask(1L, 100L)
                taskRepository.rescheduleTask(2L, 200L)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun start_timer_with_minutes_surfaces_duration_and_emits_open_timer_nav_event() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.navigationEvents.test navTest@{
            viewModel.actionResults.test {
                viewModel.executeAction(ChatActionResponse(type = "start_timer", minutes = 25))
                advanceUntilIdle()

                val result = awaitItem()
                // B.4 (F8 follow-on): the AI-suggested duration is surfaced
                // in the snackbar text. The Timer screen still opens at the
                // user's configured default; we don't deep-link the duration.
                assertEquals("Starting Timer (25 min)", result.message)
                assertNull("non-destructive ops carry no undo", result.undoLabel)
                assertNull(result.undoAction)

                val navEvent = this@navTest.awaitItem()
                assertEquals(ChatViewModel.ChatNavEvent.OpenTimer(minutes = 25), navEvent)

                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun start_timer_without_minutes_falls_back_to_generic_message() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            viewModel.executeAction(ChatActionResponse(type = "start_timer"))
            advanceUntilIdle()

            val result = awaitItem()
            assertEquals("Timer Started", result.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun create_task_plumbs_description_project_and_tags_through_addtask() = runTest(dispatcher) {
        // Project name resolves to id; one existing tag, one new tag.
        coEvery { projectDao.getProjectByNameOnce("Q2 Planning") } returns
            com.averycorp.prismtask.data.local.entity.ProjectEntity(
                id = 77L,
                name = "Q2 Planning"
            )
        coEvery { tagRepository.getTagByNameOnce("work") } returns
            TagEntity(id = 11L, name = "work", color = "#000000")
        coEvery { tagRepository.getTagByNameOnce("planning") } returns null
        coEvery { tagRepository.addTag("planning") } returns 12L
        coEvery {
            taskRepository.addTask(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
            )
        } returns 99L

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            viewModel.executeAction(
                ChatActionResponse(
                    type = "create_task",
                    title = "Draft Q2 OKR doc",
                    due = "tomorrow",
                    priority = "high",
                    description = "Cover team goals + risk register",
                    tags = listOf("work", "planning"),
                    project = "Q2 Planning"
                )
            )
            advanceUntilIdle()

            val result = awaitItem()
            assertEquals("Task Created: Draft Q2 OKR doc", result.message)

            coVerify(exactly = 1) {
                taskRepository.addTask(
                    title = "Draft Q2 OKR doc",
                    description = "Cover team goals + risk register",
                    dueDate = any(),
                    priority = 3,
                    projectId = 77L
                )
            }
            // Existing tag re-used, new tag created, both linked to the new task.
            coVerify(exactly = 1) { tagRepository.addTagToTask(99L, 11L) }
            coVerify(exactly = 1) { tagRepository.addTag("planning") }
            coVerify(exactly = 1) { tagRepository.addTagToTask(99L, 12L) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun create_task_silently_drops_unknown_project_name() = runTest(dispatcher) {
        coEvery { projectDao.getProjectByNameOnce(any()) } returns null
        coEvery {
            taskRepository.addTask(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
            )
        } returns 50L

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            viewModel.executeAction(
                ChatActionResponse(
                    type = "create_task",
                    title = "Random task",
                    project = "Nonexistent Project"
                )
            )
            advanceUntilIdle()
            awaitItem()

            // We never auto-create projects from chat — projectId must be null.
            coVerify(exactly = 1) {
                taskRepository.addTask(
                    title = "Random task",
                    description = null,
                    dueDate = null,
                    priority = 0,
                    projectId = null
                )
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun rapid_double_send_only_dispatches_one_request() = runTest(dispatcher) {
        // First sendMessage suspends until we release the gate, mirroring the
        // ~16ms recompose window where Compose's `enabled = !isTyping` hasn't
        // caught up yet and a second tap could land.
        val gate = CompletableDeferred<Unit>()
        coEvery { chatRepository.sendMessage(any(), any(), any()) } coAnswers {
            gate.await()
            mockk(relaxed = true)
        }

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("hello")
        // second tap — must be silently dropped
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.sendMessage(any(), any(), any()) }
    }

    @Test
    fun request_clear_conversation_does_not_drop_messages_until_confirmed() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        // Tap the DeleteSweep button → only flips the confirm-flag, no clear yet.
        viewModel.requestClearConversation()
        assertEquals(true, viewModel.showClearConfirm.value)
        coVerify(exactly = 0) { chatRepository.clearConversation() }

        // User cancels the dialog.
        viewModel.dismissClearConfirm()
        assertEquals(false, viewModel.showClearConfirm.value)
        coVerify(exactly = 0) { chatRepository.clearConversation() }

        // Tap again, this time confirm → repository clears.
        viewModel.requestClearConversation()
        viewModel.clearConversation()
        assertEquals(false, viewModel.showClearConfirm.value)
        coVerify(exactly = 1) { chatRepository.clearConversation() }
    }

    @Test
    fun archive_emits_undo_that_unarchives() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.actionResults.test {
            viewModel.executeAction(ChatActionResponse(type = "archive", taskId = "9"))
            advanceUntilIdle()

            val result = awaitItem()
            assertEquals("Task Archived", result.message)
            assertEquals("Undo", result.undoLabel)

            result.undoAction?.invoke()
            coVerify(exactly = 1) { taskRepository.archiveTask(9L) }
            coVerify(exactly = 1) { taskRepository.unarchiveTask(9L) }

            cancelAndIgnoreRemainingEvents()
        }
    }
}
