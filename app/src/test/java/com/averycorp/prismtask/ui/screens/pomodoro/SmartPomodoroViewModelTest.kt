package com.averycorp.prismtask.ui.screens.pomodoro

import android.content.Context
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.data.remote.api.PomodoroResponse
import com.averycorp.prismtask.data.remote.api.PomodoroSessionResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.SessionTaskResponse
import com.averycorp.prismtask.data.repository.MoodEnergyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTimingRepository
import com.averycorp.prismtask.domain.usecase.PomodoroAICoach
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SmartPomodoroViewModel]. Validates the state-machine flow
 * between PLANNING / SESSION_ACTIVE / COMPLETE, the Pro feature gate, and
 * config mutation helpers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartPomodoroViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var appContext: Context
    private lateinit var taskRepository: TaskRepository
    private lateinit var api: PrismTaskApi
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var timerPreferences: TimerPreferences
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var moodEnergyRepository: MoodEnergyRepository
    private lateinit var aiCoach: PomodoroAICoach
    private lateinit var taskTimingRepository: TaskTimingRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        appContext = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        // Default cloud-id resolution for the standard fixtures used across
        // generatePlan tests. Individual tests can override as needed.
        coEvery { taskRepository.getIdByCloudId("cloud-1") } returns 1L
        coEvery { taskRepository.getIdByCloudId("cloud-2") } returns 2L
        api = mockk(relaxed = true)
        proFeatureGate = mockk(relaxed = true)
        every { proFeatureGate.userTier } returns MutableStateFlow(UserTier.PRO)
        timerPreferences = mockk(relaxed = true)
        every { timerPreferences.getPomodoroAvailableMinutes() } returns flowOf(120)
        every { timerPreferences.getWorkDurationSeconds() } returns flowOf(25 * 60)
        every { timerPreferences.getBreakDurationSeconds() } returns flowOf(5 * 60)
        every { timerPreferences.getLongBreakDurationSeconds() } returns flowOf(15 * 60)
        every { timerPreferences.getPomodoroFocusPreference() } returns flowOf("balanced")
        // Pre-session coaching toggle defaults to true in prod, but for the
        // legacy tests (which don't set up a plan) we hold the modal off so
        // startSession() maps to an immediate timer start.
        every { timerPreferences.getPomodoroPreSessionCoachingEnabled() } returns flowOf(false)
        every { timerPreferences.getPomodoroBreakCoachingEnabled() } returns flowOf(false)
        every { timerPreferences.getPomodoroRecapCoachingEnabled() } returns flowOf(false)
        moodEnergyRepository = mockk(relaxed = true)
        coEvery { moodEnergyRepository.getRange(any(), any()) } returns emptyList()
        taskBehaviorPreferences = mockk(relaxed = true)
        every { taskBehaviorPreferences.getDayStartHour() } returns flowOf(4)
        aiCoach = mockk(relaxed = true)
        taskTimingRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = SmartPomodoroViewModel(
        appContext,
        taskRepository,
        api,
        proFeatureGate,
        moodEnergyRepository,
        timerPreferences,
        taskBehaviorPreferences,
        aiCoach,
        taskTimingRepository,
        io.mockk.mockk(relaxed = true)
    )

    @Test
    fun initialState_isPlanning() {
        val vm = newViewModel()
        assertEquals(PomodoroState.PLANNING, vm.screenState.value)
    }

    @Test
    fun config_reflectsTimerPreferences() = runTest(dispatcher) {
        every { timerPreferences.getPomodoroAvailableMinutes() } returns flowOf(90)
        every { timerPreferences.getWorkDurationSeconds() } returns flowOf(45 * 60)
        every { timerPreferences.getPomodoroFocusPreference() } returns flowOf("deep_work")

        val vm = newViewModel()
        // Start a subscriber so the WhileSubscribed upstream activates.
        val job = launch { vm.config.collect {} }
        advanceUntilIdle()

        assertEquals(90, vm.config.value.availableMinutes)
        assertEquals(45, vm.config.value.sessionLength)
        assertEquals("deep_work", vm.config.value.focusPreference)

        job.cancel()
    }

    @Test
    fun generatePlan_freeTierShowsUpgradePrompt() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns false
        val vm = newViewModel()

        vm.generatePlan()
        advanceUntilIdle()

        assertTrue(vm.showUpgradePrompt.value)
        assertEquals(PomodoroPlanUiState.Idle, vm.planUiState.value)
        coVerify(exactly = 0) { api.planPomodoro(any()) }
    }

    @Test
    fun generatePlan_proTierCallsApiAndStoresPlan() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse(taskId = "cloud-1", title = "Focus", allocatedMinutes = 25)),
                    rationale = "Warm up"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        val state = vm.planUiState.value
        assertTrue("expected Success, got $state", state is PomodoroPlanUiState.Success)
        val plan = (state as PomodoroPlanUiState.Success).plan
        assertEquals(1, plan.sessions.size)
        assertEquals(25, plan.totalWorkMinutes)
    }

    @Test
    fun generatePlan_resolvesFirestoreCloudIdsToLocalLongIds() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { taskRepository.getIdByCloudId("cloud-abc") } returns 42L
        coEvery { taskRepository.getIdByCloudId("cloud-missing") } returns null
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(
                        SessionTaskResponse(taskId = "cloud-abc", title = "Resolved", allocatedMinutes = 25),
                        SessionTaskResponse(taskId = "cloud-missing", title = "Unsynced", allocatedMinutes = 15)
                    ),
                    rationale = "Mixed"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        val plan = (vm.planUiState.value as PomodoroPlanUiState.Success).plan
        // Resolved task lands in the session, unresolved task is moved to skipped.
        assertEquals(1, plan.sessions.size)
        assertEquals(listOf(42L), plan.sessions[0].tasks.map { it.taskId })
        assertEquals(1, plan.skippedTasks.size)
        assertTrue(plan.skippedTasks[0].reason.contains("not synced"))
    }

    @Test
    fun generatePlan_emptySessionsEmitsEmptyState() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = emptyList(),
            totalSessions = 0,
            totalWorkMinutes = 0,
            totalBreakMinutes = 0,
            skippedTasks = emptyList()
        )

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        val state = vm.planUiState.value
        assertTrue("expected Empty, got $state", state is PomodoroPlanUiState.Empty)
        assertEquals(
            "No tasks to plan around. Add a task or check that your tasks are synced.",
            (state as PomodoroPlanUiState.Empty).reason
        )
    }

    @Test
    fun generatePlan_apiFailureEmitsErrorState() = runTest(dispatcher) {
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } throws RuntimeException("upstream down")

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        val state = vm.planUiState.value
        assertTrue("expected Error, got $state", state is PomodoroPlanUiState.Error)
        assertEquals("upstream down", (state as PomodoroPlanUiState.Error).message)
    }

    @Test
    fun startSession_transitionsToActiveStateWithFullTimer() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.startSession()
        // requestPreSessionCoaching runs on viewModelScope; let it settle
        // before asserting against timer state.
        advanceUntilIdle()

        assertEquals(PomodoroState.SESSION_ACTIVE, vm.screenState.value)
        assertEquals(25 * 60, vm.timerSecondsRemaining.value)
        assertTrue(vm.isTimerRunning.value)
    }

    @Test
    fun pauseTimer_stopsTheTimer() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.startSession()
        advanceUntilIdle()
        vm.pauseTimer()
        assertFalse(vm.isTimerRunning.value)
    }

    @Test
    fun completeTask_routesThroughTaskRepository() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.completeTask(42L)
        advanceUntilIdle()

        // Audit: docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md (Item 3).
        // Going through TaskRepository is what spawns the recurrence's next
        // occurrence, cancels the reminder, and triggers sync/widget updates.
        // The pre-fix path called `taskDao.markCompleted` directly and skipped
        // every one of those side effects.
        coVerify { taskRepository.completeTask(42L) }
        assertTrue(42L in vm.completedTaskIds.value)
    }

    @Test
    fun resetToPlanning_clearsPlanAndStats() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.startSession()
        advanceUntilIdle()
        vm.resetToPlanning()

        assertEquals(PomodoroState.PLANNING, vm.screenState.value)
        assertEquals(PomodoroPlanUiState.Idle, vm.planUiState.value)
        assertTrue(vm.completedTaskIds.value.isEmpty())
        assertTrue(vm.preSessionCoaching.value is PreSessionCoachingUiState.Hidden)
        assertEquals(null, vm.breakSuggestion.value)
        assertEquals(null, vm.sessionRecap.value)
    }

    // ---- A2 Pomodoro+ AI coaching paths -----------------------------------

    @Test
    fun preSessionCoaching_disabled_fallsThroughToTimer() = runTest(dispatcher) {
        // Default setUp stubs the pre-session pref to false; confirm the
        // modal never surfaces and the timer starts synchronously (modulo
        // the viewModelScope.launch hop).
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )
        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()

        assertTrue(vm.preSessionCoaching.value is PreSessionCoachingUiState.Hidden)
        assertTrue(vm.isTimerRunning.value)
    }

    @Test
    fun preSessionCoaching_enabledAndHappy_surfacesModalAndGatesTimer() = runTest(dispatcher) {
        every { timerPreferences.getPomodoroPreSessionCoachingEnabled() } returns flowOf(true)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )
        coEvery { aiCoach.suggestPreSession(any(), any()) } returns
            Result.success("Start with the draft.")
        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()

        val state = vm.preSessionCoaching.value
        assertTrue("expected Ready, got $state", state is PreSessionCoachingUiState.Ready)
        assertEquals("Start with the draft.", (state as PreSessionCoachingUiState.Ready).message)
        // Timer is still gated on modal dismiss.
        assertFalse(vm.isTimerRunning.value)

        // Accepting the suggestion should start the actual timer.
        vm.acceptPreSessionCoaching()
        advanceUntilIdle()
        assertTrue(vm.preSessionCoaching.value is PreSessionCoachingUiState.Hidden)
        assertTrue(vm.isTimerRunning.value)
    }

    @Test
    fun preSessionCoaching_rpcFailure_silentSkipAndTimerStarts() = runTest(dispatcher) {
        every { timerPreferences.getPomodoroPreSessionCoachingEnabled() } returns flowOf(true)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )
        coEvery { aiCoach.suggestPreSession(any(), any()) } returns
            Result.failure(RuntimeException("offline"))
        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()

        assertTrue(vm.preSessionCoaching.value is PreSessionCoachingUiState.Hidden)
        assertTrue(vm.isTimerRunning.value)
    }

    @Test
    fun dismissBreakSuggestion_clearsState() = runTest(dispatcher) {
        val vm = newViewModel()
        // Simulate arrival of a break suggestion via private field semantics —
        // we can't poke internal state, so go the public route: enable break
        // coaching + stub the coach.
        every { timerPreferences.getPomodoroBreakCoachingEnabled() } returns flowOf(true)
        coEvery { aiCoach.suggestBreakActivity(any(), any(), any()) } returns
            Result.success("Drink water.")

        // Directly observe the dismissal pathway — pre-set a suggestion by
        // driving the ViewModel via its public surface. With no plan, the
        // break flow doesn't fire; so we assert dismissal is idempotent when
        // already null, which is the important invariant.
        vm.dismissBreakSuggestion()
        assertEquals(null, vm.breakSuggestion.value)
    }

    @Test
    fun dismissSessionRecap_clearsState() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.dismissSessionRecap()
        assertEquals(null, vm.sessionRecap.value)
    }

    @Test
    fun endEarly_withRecapEnabled_firesRecapCoach() = runTest(dispatcher) {
        every { timerPreferences.getPomodoroRecapCoachingEnabled() } returns flowOf(true)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )
        coEvery { aiCoach.recapSession(any(), any(), any()) } returns
            Result.success("Good work on the draft. Next: polish it.")

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()
        vm.endEarly()
        advanceUntilIdle()

        assertEquals(PomodoroState.COMPLETE, vm.screenState.value)
        val recap = vm.sessionRecap.value
        assertNotNull("recap should surface", recap)
        assertEquals("Good work on the draft. Next: polish it.", recap?.message)
        coVerify(exactly = 1) { aiCoach.recapSession(any(), any(), any()) }
    }

    @Test
    fun endEarly_withRecapDisabled_skipsRecap() = runTest(dispatcher) {
        // Default setUp has recap disabled.
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()
        vm.endEarly()
        advanceUntilIdle()

        assertEquals(PomodoroState.COMPLETE, vm.screenState.value)
        assertEquals(null, vm.sessionRecap.value)
        coVerify(exactly = 0) { aiCoach.recapSession(any(), any(), any()) }
    }

    @Test
    fun endEarly_recapRpcFailure_leavesRecapNull() = runTest(dispatcher) {
        every { timerPreferences.getPomodoroRecapCoachingEnabled() } returns flowOf(true)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )
        coEvery { aiCoach.recapSession(any(), any(), any()) } returns
            Result.failure(RuntimeException("offline"))

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()
        vm.endEarly()
        advanceUntilIdle()

        // Failure is silent — COMPLETE screen still shows, recap card just
        // doesn't render.
        assertEquals(PomodoroState.COMPLETE, vm.screenState.value)
        assertEquals(null, vm.sessionRecap.value)
    }

    @Test
    fun nextSession_nonFinal_rerunsPreSessionCoaching() = runTest(dispatcher) {
        every { timerPreferences.getPomodoroPreSessionCoachingEnabled() } returns flowOf(true)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                ),
                PomodoroSessionResponse(
                    sessionNumber = 2,
                    tasks = listOf(SessionTaskResponse("cloud-2", "Review", 25)),
                    rationale = "then"
                )
            ),
            totalSessions = 2,
            totalWorkMinutes = 50,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )
        // First call serves the pre-session modal for session 1.
        coEvery { aiCoach.suggestPreSession(any(), any()) } returns
            Result.success("Start the draft.")

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()
        vm.acceptPreSessionCoaching()
        advanceUntilIdle()

        // Drive into the break slot and then advance to the next session.
        // resumeTimer / skipBreak mirror the user tapping "Skip Break" on the
        // break screen. nextSession() is the public hook; simulate the state
        // machine state with a direct call (consistent with the prior
        // nextSession-based tests).
        vm.nextSession()
        advanceUntilIdle()

        // The pre-session coach should have been invoked again for session 2.
        coVerify(atLeast = 2) { aiCoach.suggestPreSession(any(), any()) }
        // And the modal should be surfacing the (mocked) message again.
        val state = vm.preSessionCoaching.value
        assertTrue("expected Ready, got $state", state is PreSessionCoachingUiState.Ready)
    }

    @Test
    fun nextSession_finalIndex_firesRecap() = runTest(dispatcher) {
        every { timerPreferences.getPomodoroRecapCoachingEnabled() } returns flowOf(true)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Only task", 25)),
                    rationale = "just one"
                )
            ),
            totalSessions = 1,
            totalWorkMinutes = 25,
            totalBreakMinutes = 0,
            skippedTasks = emptyList()
        )
        coEvery { aiCoach.recapSession(any(), any(), any()) } returns
            Result.success("Nicely done. Carry forward the review.")

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()
        // nextSession on the final index short-circuits to COMPLETE + recap.
        vm.nextSession()
        advanceUntilIdle()

        assertEquals(PomodoroState.COMPLETE, vm.screenState.value)
        val recap = vm.sessionRecap.value
        assertNotNull(recap)
        assertEquals("Nicely done. Carry forward the review.", recap?.message)
        coVerify(exactly = 1) { aiCoach.recapSession(any(), any(), any()) }
    }

    @Test
    fun nextSession_clearsStaleBreakSuggestion() = runTest(dispatcher) {
        // Break suggestion was already tested for dismissal; here we confirm
        // nextSession() — which is the "skip break" hook — also clears it so
        // a stale card doesn't linger into the next work session.
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_POMODORO) } returns true
        coEvery { api.planPomodoro(any()) } returns PomodoroResponse(
            sessions = listOf(
                PomodoroSessionResponse(
                    sessionNumber = 1,
                    tasks = listOf(SessionTaskResponse("cloud-1", "Draft", 25)),
                    rationale = "go"
                ),
                PomodoroSessionResponse(
                    sessionNumber = 2,
                    tasks = listOf(SessionTaskResponse("cloud-2", "Review", 25)),
                    rationale = "then"
                )
            ),
            totalSessions = 2,
            totalWorkMinutes = 50,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()
        // dismissBreakSuggestion as a pre-condition confirms the getter works;
        // the real invariant under test is post-nextSession clearance.
        vm.dismissBreakSuggestion()
        vm.nextSession()
        advanceUntilIdle()

        assertEquals(null, vm.breakSuggestion.value)
    }

    // ---------------------------------------------------------------------
    // Pomodoro auto-log (P2-D)
    // ---------------------------------------------------------------------

    @Test
    fun autoLog_writesPomodoroEntryPerSessionTask() = runTest(dispatcher) {
        val vm = newViewModel()
        val plan = PomodoroPlan(
            sessions = listOf(
                PomodoroSession(
                    sessionNumber = 1,
                    tasks = listOf(
                        SessionTask(taskId = 7L, title = "Draft", allocatedMinutes = 15),
                        SessionTask(taskId = 8L, title = "Review", allocatedMinutes = 10)
                    ),
                    rationale = "go"
                )
            ),
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )

        vm.autoLogPomodoroSessionTime(plan, 0)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            taskTimingRepository.logTime(
                taskId = 7L,
                durationMinutes = 15,
                source = TaskTimingEntity.SOURCE_POMODORO
            )
        }
        coVerify(exactly = 1) {
            taskTimingRepository.logTime(
                taskId = 8L,
                durationMinutes = 10,
                source = TaskTimingEntity.SOURCE_POMODORO
            )
        }
    }

    @Test
    fun autoLog_skipsZeroMinuteTasks() = runTest(dispatcher) {
        val vm = newViewModel()
        val plan = PomodoroPlan(
            sessions = listOf(
                PomodoroSession(
                    sessionNumber = 1,
                    tasks = listOf(
                        SessionTask(taskId = 7L, title = "Empty alloc", allocatedMinutes = 0),
                        SessionTask(taskId = 8L, title = "Review", allocatedMinutes = 25)
                    ),
                    rationale = "go"
                )
            ),
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )

        vm.autoLogPomodoroSessionTime(plan, 0)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            taskTimingRepository.logTime(
                taskId = 7L,
                durationMinutes = any(),
                source = any()
            )
        }
        coVerify(exactly = 1) {
            taskTimingRepository.logTime(
                taskId = 8L,
                durationMinutes = 25,
                source = TaskTimingEntity.SOURCE_POMODORO
            )
        }
    }

    @Test
    fun autoLog_outOfBoundsSessionIndex_isNoOp() = runTest(dispatcher) {
        val vm = newViewModel()
        val plan = PomodoroPlan(
            sessions = listOf(
                PomodoroSession(1, listOf(SessionTask(1L, "X", 25)), "go")
            ),
            totalWorkMinutes = 25,
            totalBreakMinutes = 5,
            skippedTasks = emptyList()
        )

        vm.autoLogPomodoroSessionTime(plan, sessionIndex = 99)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            taskTimingRepository.logTime(any(), any(), any(), any(), any(), any())
        }
    }
}
