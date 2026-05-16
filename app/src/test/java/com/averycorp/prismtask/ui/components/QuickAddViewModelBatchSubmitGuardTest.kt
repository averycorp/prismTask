package com.averycorp.prismtask.ui.components

import app.cash.turbine.test
import com.averycorp.prismtask.data.local.dao.UsageLogDao
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.QuickAddRows
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TitleLengthLimit
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTemplateRepository
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import com.averycorp.prismtask.domain.usecase.ParsedTaskResolver
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.ProjectIntentParser
import com.averycorp.prismtask.domain.usecase.TextToSpeechManager
import com.averycorp.prismtask.domain.usecase.VoiceCommandParser
import com.averycorp.prismtask.domain.usecase.VoiceInputManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Locks in the batch-operations double-run re-entry guard (A4 / A5).
 *
 * Without the guard, two synchronous `onSubmit()` calls (a Compose
 * double-tap or Send-then-IME-Done combo) emit twice on
 * `_batchIntents` / `_multiCreateIntents`, the QuickAddBar collector
 * navigates twice, and two fresh `BatchPreviewViewModel`s each call
 * Haiku — producing the user-reported "different options the second
 * time" symptom (Haiku is non-deterministic).
 *
 * The guard is a synchronous `_isSubmitting.value` check + set at the
 * top of `onSubmit`. Compose dispatches click events serially on the
 * main thread, so the synchronous claim runs to completion before the
 * next click handler can run; the second tap reads `_isSubmitting ==
 * true` and returns immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelBatchSubmitGuardTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var parser: NaturalLanguageParser
    private lateinit var intentParser: ProjectIntentParser
    private lateinit var resolver: ParsedTaskResolver
    private lateinit var taskRepository: TaskRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var templateRepository: TaskTemplateRepository
    private lateinit var usageLogDao: UsageLogDao
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var voiceCommandParser: VoiceCommandParser
    private lateinit var tts: TextToSpeechManager
    private lateinit var voicePreferences: VoicePreferences
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var userPreferencesDataStore: UserPreferencesDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        parser = mockk(relaxed = true)
        intentParser = mockk(relaxed = true)
        resolver = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        templateRepository = mockk(relaxed = true)
        usageLogDao = mockk(relaxed = true)
        proFeatureGate = mockk()
        voiceInputManager = mockk(relaxed = true)
        voiceCommandParser = mockk(relaxed = true)
        tts = mockk(relaxed = true)
        voicePreferences = mockk(relaxed = true)
        advancedTuningPreferences = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        userPreferencesDataStore = mockk(relaxed = true)

        // Init-block dependencies — these flows must be cold-emittable
        // so the VM's `init { viewModelScope.launch { collect } }` doesn't
        // deadlock the test dispatcher.
        every { voicePreferences.getVoiceInputEnabled() } returns flowOf(true)
        every { voiceInputManager.partialText } returns MutableStateFlow("")
        every { voiceInputManager.isListening } returns MutableStateFlow(false)
        every { voiceInputManager.rmsLevel } returns MutableStateFlow(0f)
        every { advancedTuningPreferences.getQuickAddRows() } returns
            flowOf(QuickAddRows())
        every { advancedTuningPreferences.getLifeCategoryCustomKeywords() } returns
            flowOf(com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords())
        every { taskBehaviorPreferences.getTitleLengthLimit() } returns
            flowOf(TitleLengthLimit(TitleLengthLimit.DEFAULT_LIMIT))

        every { proFeatureGate.hasAccess(ProFeatureGate.AI_BATCH_OPS) } returns true
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_NLP) } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The load-bearing test: two synchronous `onSubmit()` calls on a
     * batch-detected input must emit exactly one value on
     * `batchIntents`. Pre-fix, this test would observe two emissions
     * (one navigation per emission, two fresh BatchPreviewVMs, two
     * Haiku calls).
     */
    @Test
    fun onSubmit_batch_doubleTap_emitsBatchIntentOnce() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.inputText.value = "complete all tasks today"

        viewModel.batchIntents.test {
            // Two synchronous taps in the same main-thread frame —
            // models a Compose double-tap or a Send-then-IME-Done combo.
            viewModel.onSubmit()
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals("complete all tasks today", awaitItem())
            // No second emission should arrive within a generous window
            // — the guard returned synchronously before the second
            // launch could schedule an emit.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Same shape, multi-create path. A multi-line paste that triggers
     * `MultiCreateDetector.Result.MultiCreate` must navigate to the
     * MultiCreateBottomSheet exactly once even on a double-tap.
     */
    @Test
    fun onSubmit_multiCreate_doubleTap_emitsMultiCreateIntentOnce() = runTest(dispatcher) {
        val viewModel = newViewModel()
        // Newline-separated input is rule-(a) of MultiCreateDetector.
        val multiLine = "buy milk\nfeed cat\ncall dad"
        viewModel.inputText.value = multiLine

        viewModel.multiCreateIntents.test {
            viewModel.onSubmit()
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(multiLine, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * After the first submit completes, a third tap should be allowed
     * through — the guard releases `_isSubmitting` in the launch's
     * `finally` block. Without this, the user would be stuck after one
     * batch submit per VM lifetime.
     */
    @Test
    fun onSubmit_batch_secondSubmitAfterFirstCompletes_isAllowed() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.inputText.value = "complete all tasks today"

        viewModel.batchIntents.test {
            viewModel.onSubmit()
            advanceUntilIdle()
            assertEquals("complete all tasks today", awaitItem())

            // Re-populate (the VM clears on emit) and submit again.
            viewModel.inputText.value = "delete all overdue tasks"
            viewModel.onSubmit()
            advanceUntilIdle()
            assertEquals("delete all overdue tasks", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Defense-in-depth regression test. The screen-level fix gives each
     * `QuickAddBar` composition site under the `MainTabs` pager a distinct
     * `hiltViewModel(key = …)` so two bars never share one VM. But the VM
     * itself also defends: `_batchIntents` is backed by a [Channel]
     * (`receiveAsFlow()`), not a `MutableSharedFlow`, so a single emission
     * is delivered to **exactly one** collector. Even if a future
     * composition site regresses and two `LaunchedEffect` collectors
     * share this VM, only one will fire `onBatchCommand` — the host
     * navigates once, not twice.
     *
     * Pre-Channel (when `_batchIntents` was `MutableSharedFlow`), the
     * single emit fanned out to both collectors and this assertion read
     * 2, not 1 — the user-visible "preview, accept, then it runs a
     * second time" bug.
     *
     * Each collector is bounded by a `withTimeoutOrNull` against the
     * test scheduler's virtual clock so the "loser" returns null rather
     * than hanging `runTest` forever (which is what the unbounded
     * `launch { collect { … } }` shape did and was killed by the
     * 12-minute CI step timeout — see PR #1273 attempt 2).
     */
    @Test
    fun onSubmit_batch_twoCollectors_emitDeliveredToExactlyOne() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.inputText.value = "complete all tasks today"

        val received1 = async {
            withTimeoutOrNull(1_000) { viewModel.batchIntents.first() }
        }
        val received2 = async {
            withTimeoutOrNull(1_000) { viewModel.batchIntents.first() }
        }
        // Run pending tasks at t=0 so both collectors subscribe, but DON'T
        // advance virtual time — `advanceUntilIdle` would auto-fire the
        // pending `withTimeoutOrNull(1_000)` delays at t=1000 before the
        // emit ever runs, leaving both async results null (CI attempt 3
        // failure shape).
        runCurrent()

        viewModel.onSubmit()
        // Past the 1s virtual-time bound so the loser's withTimeoutOrNull
        // returns null instead of hanging on `first()`.
        advanceTimeBy(2_000)
        advanceUntilIdle()

        val r1 = received1.await()
        val r2 = received2.await()

        // Exactly one collector received the emit; the other timed out.
        // Which one wins is racy (dispatcher order), but the count must
        // be 1, not 2.
        assertEquals(1, listOf(r1, r2).count { it != null })
    }

    /**
     * Same defense for the multi-create path. The bug class is identical
     * (single emit fanning out to two `LaunchedEffect` collectors that
     * share a `QuickAddViewModel`), so the `Channel` defense applies the
     * same way.
     */
    @Test
    fun onSubmit_multiCreate_twoCollectors_emitDeliveredToExactlyOne() = runTest(dispatcher) {
        val viewModel = newViewModel()
        // Newline-separated input is rule-(a) of MultiCreateDetector.
        val multiLine = "buy milk\nfeed cat\ncall dad"
        viewModel.inputText.value = multiLine

        val received1 = async {
            withTimeoutOrNull(1_000) { viewModel.multiCreateIntents.first() }
        }
        val received2 = async {
            withTimeoutOrNull(1_000) { viewModel.multiCreateIntents.first() }
        }
        // See sibling test — `runCurrent` instead of `advanceUntilIdle` so
        // the withTimeoutOrNull delays don't auto-fire before the emit.
        runCurrent()

        viewModel.onSubmit()
        advanceTimeBy(2_000)
        advanceUntilIdle()

        val r1 = received1.await()
        val r2 = received2.await()

        assertEquals(1, listOf(r1, r2).count { it != null })
    }

    private fun newViewModel(): QuickAddViewModel = QuickAddViewModel(
        parser = parser,
        intentParser = intentParser,
        resolver = resolver,
        taskRepository = taskRepository,
        tagRepository = tagRepository,
        projectRepository = projectRepository,
        templateRepository = templateRepository,
        usageLogDao = usageLogDao,
        proFeatureGate = proFeatureGate,
        voiceInputManager = voiceInputManager,
        voiceCommandParser = voiceCommandParser,
        tts = tts,
        voicePreferences = voicePreferences,
        advancedTuningPreferences = advancedTuningPreferences,
        taskBehaviorPreferences = taskBehaviorPreferences,
        userPreferencesDataStore = userPreferencesDataStore
    )
}
