package com.averycorp.prismtask.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatStreamEvent
import com.averycorp.prismtask.data.remote.api.ChatTokensUsed
import com.averycorp.prismtask.data.repository.ChatRepository
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * F7 D.1 + F8 D.2 — pins the streaming-turn state machine + cancel UX.
 * Token events grow `partialText`; Done commits via the repository;
 * Error surfaces to `_error` without committing; cancelInFlight commits
 * the partial text with a "(cancelled)" suffix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStreamingTest {
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
            coEvery { aiChatDisclosureShownV3Flow } returns flowOf(true)
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
    fun token_events_grow_partialText_in_order() = runTest(dispatcher) {
        val events = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 8)
        every { chatRepository.streamMessage(any(), any(), any()) } returns events

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        // Pre-token: Streaming with empty partialText
        val s0 = viewModel.turnState.value as ChatViewModel.ChatTurnState.Streaming
        assertEquals("", s0.partialText)

        events.emit(ChatStreamEvent.Token("Hello"))
        advanceUntilIdle()
        val s1 = viewModel.turnState.value as ChatViewModel.ChatTurnState.Streaming
        assertEquals("Hello", s1.partialText)

        events.emit(ChatStreamEvent.Token(" world"))
        advanceUntilIdle()
        val s2 = viewModel.turnState.value as ChatViewModel.ChatTurnState.Streaming
        assertEquals("Hello world", s2.partialText)
    }

    @Test
    fun done_event_commits_full_message_and_actions_via_repository() = runTest(dispatcher) {
        val events = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 8)
        every { chatRepository.streamMessage(any(), any(), any()) } returns events

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        events.emit(ChatStreamEvent.Token("All "))
        events.emit(ChatStreamEvent.Token("done"))
        events.emit(
            ChatStreamEvent.Done(
                message = "All done",
                actions = listOf(ChatActionResponse(type = "start_timer", minutes = 25)),
                tokensUsed = ChatTokensUsed(input = 10, output = 4)
            )
        )
        advanceUntilIdle()

        // turnState back to Idle
        assertEquals(ChatViewModel.ChatTurnState.Idle, viewModel.turnState.value)
        assertEquals(false, viewModel.isTyping.value)

        // Final commit goes through commitAssistantTurn — the repository
        // owns the message list, so this is what reaches the screen.
        val textSlot = slot<String>()
        val actionsSlot = slot<List<ChatActionResponse>>()
        verify {
            chatRepository.commitAssistantTurn(
                userText = any(),
                text = capture(textSlot),
                actions = capture(actionsSlot),
                userMessageId = any(),
                assistantMessageId = any(),
                userTaskContext = any()
            )
        }
        assertEquals("All done", textSlot.captured)
        assertEquals(1, actionsSlot.captured.size)
        assertEquals("start_timer", actionsSlot.captured[0].type)
    }

    @Test
    fun error_event_surfaces_to_error_flow_without_commit() = runTest(dispatcher) {
        val events = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 8)
        every { chatRepository.streamMessage(any(), any(), any()) } returns events

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        events.emit(
            ChatStreamEvent.Error(
                message = "Daily chat limit reached. Try again later.",
                code = "http_429"
            )
        )
        advanceUntilIdle()

        assertEquals(
            "Daily chat limit reached. Try again later.",
            viewModel.error.value
        )
        assertEquals(ChatViewModel.ChatTurnState.Idle, viewModel.turnState.value)
        verify(exactly = 0) {
            chatRepository.commitAssistantTurn(
                userText = any(),
                text = any(),
                actions = any(),
                userMessageId = any(),
                assistantMessageId = any(),
                userTaskContext = any()
            )
        }
    }

    @Test
    fun cancel_during_streaming_commits_partial_with_cancelled_suffix() = runTest(dispatcher) {
        val events = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 8)
        every { chatRepository.streamMessage(any(), any(), any()) } returns events

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("plan my week")
        advanceUntilIdle()

        events.emit(ChatStreamEvent.Token("Here is "))
        events.emit(ChatStreamEvent.Token("your plan"))
        advanceUntilIdle()

        viewModel.cancelInFlight()
        advanceUntilIdle()

        // turnState back to Idle, partial text committed with suffix
        assertEquals(ChatViewModel.ChatTurnState.Idle, viewModel.turnState.value)

        val textSlot = slot<String>()
        val actionsSlot = slot<List<ChatActionResponse>>()
        verify {
            chatRepository.commitAssistantTurn(
                userText = any(),
                text = capture(textSlot),
                actions = capture(actionsSlot),
                userMessageId = any(),
                assistantMessageId = any(),
                userTaskContext = any()
            )
        }
        assertEquals("Here is your plan (cancelled)", textSlot.captured)
        assertEquals("cancelled commit drops actions", true, actionsSlot.captured.isEmpty())
    }

    @Test
    fun cancel_when_idle_is_no_op() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.cancelInFlight()
        advanceUntilIdle()

        assertEquals(ChatViewModel.ChatTurnState.Idle, viewModel.turnState.value)
        verify(exactly = 0) {
            chatRepository.commitAssistantTurn(
                userText = any(),
                text = any(),
                actions = any(),
                userMessageId = any(),
                assistantMessageId = any(),
                userTaskContext = any()
            )
        }
    }

    @Test
    fun cancel_with_empty_partial_commits_only_cancelled_suffix() = runTest(dispatcher) {
        // User taps Stop before any Token event lands. The committed
        // bubble shouldn't be empty — show "(cancelled)" so the user
        // sees the boundary.
        every { chatRepository.streamMessage(any(), any(), any()) } returns
            flow { awaitCancellation() }

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("hi")
        advanceUntilIdle()
        viewModel.cancelInFlight()
        advanceUntilIdle()

        val textSlot = slot<String>()
        verify {
            chatRepository.commitAssistantTurn(
                userText = any(),
                text = capture(textSlot),
                actions = any(),
                userMessageId = any(),
                assistantMessageId = any(),
                userTaskContext = any()
            )
        }
        assertEquals("(cancelled)", textSlot.captured)
    }

    @Test
    fun second_sendMessage_during_streaming_is_dedup_dropped() = runTest(dispatcher) {
        // Streaming Flow that never resolves — first sendMessage parks
        // turnState in Streaming. The second tap must short-circuit at
        // the dedup guard before opening a second SSE connection.
        every { chatRepository.streamMessage(any(), any(), any()) } returns
            flow { awaitCancellation() }

        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("first")
        viewModel.sendMessage("second")
        advanceUntilIdle()

        verify(exactly = 1) {
            chatRepository.streamMessage(any(), any(), any())
        }
    }
}
