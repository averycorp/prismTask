package com.averycorp.prismtask.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.repository.ChatRepository
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import com.averycorp.prismtask.domain.usecase.ParsedTaskResolver
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 fix #2 (CHAT_QUALITY_AUDIT C.1) baseline: the first-run AI
 * chat disclosure must fire when the persisted flag is false; dismiss
 * persists true; subsequent opens skip the dialog.
 *
 * Bumps to date:
 *   V1 -> V2 (F8 chat privacy doc update): expanded copy re-acknowledged.
 *   V2 -> V3 (D11 E.3 chat persistence): retention change re-acknowledged.
 *
 * Tests pin the V3 contract: gate on V3 flag, dismiss writes V2 + V3,
 * V1 setter must never be called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDisclosureTest {
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
    private lateinit var naturalLanguageParser: NaturalLanguageParser
    private lateinit var parsedTaskResolver: ParsedTaskResolver

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
        }
        taskBehaviorPreferences = mockk(relaxed = true)
        userPreferencesDataStore = mockk(relaxed = true) {
            coEvery { chatClearSkipConfirmationFlow } returns flowOf(false)
        }
        naturalLanguageParser = mockk(relaxed = true)
        parsedTaskResolver = mockk(relaxed = true)
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
        userPreferencesDataStore = userPreferencesDataStore,
        naturalLanguageParser = naturalLanguageParser,
        parsedTaskResolver = parsedTaskResolver
    )

    @Test
    fun showDisclosure_fires_on_first_chat_open() = runTest(dispatcher) {
        coEvery { userPreferencesDataStore.aiChatDisclosureShownV3Flow } returns flowOf(false)

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertTrue(
            "disclosure must surface on first chat open",
            viewModel.showDisclosure.value
        )
    }

    @Test
    fun showDisclosure_skipped_after_prior_dismissal() = runTest(dispatcher) {
        coEvery { userPreferencesDataStore.aiChatDisclosureShownV3Flow } returns flowOf(true)

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertFalse(
            "disclosure must NOT surface once persisted V2 flag is true",
            viewModel.showDisclosure.value
        )
    }

    @Test
    fun dismissDisclosure_persists_flag_and_hides_dialog() = runTest(dispatcher) {
        coEvery { userPreferencesDataStore.aiChatDisclosureShownV3Flow } returns flowOf(false)

        val viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.showDisclosure.value)

        viewModel.dismissDisclosure()
        advanceUntilIdle()

        assertFalse(viewModel.showDisclosure.value)
        coVerify { userPreferencesDataStore.setAiChatDisclosureShownV2(true) }
    }

    /**
     * D11 E.3 re-fire path: existing user who already dismissed V1+V2
     * must still see the dialog once after the V3 bump (chat
     * persistence shipped, retention shape changed materially).
     */
    @Test
    fun existingUser_with_v2_set_true_still_sees_v3_dialog_once() = runTest(dispatcher) {
        coEvery { userPreferencesDataStore.aiChatDisclosureShownFlow } returns flowOf(true)
        coEvery { userPreferencesDataStore.aiChatDisclosureShownV3Flow } returns flowOf(false)

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertTrue(
            "existing user with prior dismissals must still see V3 after bump",
            viewModel.showDisclosure.value
        )
    }

    /**
     * D11 E.3 invariant: dismiss writes BOTH V2 and V3 (V3 supersedes
     * V2 because V3 copy is a superset) but leaves V1 untouched so
     * back-revved clients keep their dismissal state.
     */
    @Test
    fun dismissDisclosure_persists_v2_and_v3_and_leaves_v1_alone() = runTest(dispatcher) {
        coEvery { userPreferencesDataStore.aiChatDisclosureShownV3Flow } returns flowOf(false)

        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.dismissDisclosure()
        advanceUntilIdle()

        coVerify(exactly = 1) { userPreferencesDataStore.setAiChatDisclosureShownV2(true) }
        coVerify(exactly = 1) { userPreferencesDataStore.setAiChatDisclosureShownV3(true) }
        coVerify(exactly = 0) { userPreferencesDataStore.setAiChatDisclosureShown(any()) }
    }
}
