package com.averycorp.prismtask.ui.screens.timer

import android.content.Context
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.widget.WidgetUpdateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TimerViewModel]. Focused on the non-Pomodoro
 * work/break auto-switch behaviour; the Pomodoro branch is exercised by
 * a single regression test to lock the existing flow in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var appContext: Context
    private lateinit var timerPreferences: TimerPreferences
    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private val activeViewModels = mutableListOf<TimerViewModel>()

    private val workSeconds = 25 * 60
    private val breakSeconds = 5 * 60
    private val longBreakSeconds = 15 * 60
    private val customSeconds = 10 * 60

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        appContext = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        coEvery { widgetUpdateManager.updateTimerWidget() } returns Unit

        timerPreferences = mockk(relaxed = true)
        every { timerPreferences.getWorkDurationSeconds() } returns flowOf(workSeconds)
        every { timerPreferences.getBreakDurationSeconds() } returns flowOf(breakSeconds)
        every { timerPreferences.getLongBreakDurationSeconds() } returns flowOf(longBreakSeconds)
        every { timerPreferences.getCustomDurationSeconds() } returns flowOf(customSeconds)
        every { timerPreferences.getPomodoroEnabled() } returns flowOf(false)
        every { timerPreferences.getSessionsUntilLongBreak() } returns
            flowOf(TimerPreferences.DEFAULT_SESSIONS_UNTIL_LONG_BREAK)
        every { timerPreferences.getAutoStartBreaks() } returns flowOf(false)
        every { timerPreferences.getAutoStartWork() } returns flowOf(false)
    }

    @After
    fun tearDown() {
        // Pause every VM created during this test before resetting Main.
        // start() launches a viewModelScope tickJob that suspends on
        // delay(1000); without an explicit pause, the suspended
        // continuation is bound to the about-to-be-discarded test
        // dispatcher and bleeds into the next test as a Dispatchers.Main
        // race or a DataStore actor NPE attributed to whichever test
        // JUnit happens to be running when the leaked exception
        // resolves.
        activeViewModels.forEach { it.toggleStartPauseIfRunning() }
        activeViewModels.clear()
        Dispatchers.resetMain()
    }

    private fun TimerViewModel.toggleStartPauseIfRunning() {
        if (uiState.value.isRunning) toggleStartPause()
    }

    private fun newViewModel(
        pomodoroEnabled: Boolean = false,
        autoStartBreaks: Boolean = false,
        autoStartWork: Boolean = false
    ): TimerViewModel {
        every { timerPreferences.getPomodoroEnabled() } returns flowOf(pomodoroEnabled)
        every { timerPreferences.getAutoStartBreaks() } returns flowOf(autoStartBreaks)
        every { timerPreferences.getAutoStartWork() } returns flowOf(autoStartWork)
        return TimerViewModel(appContext, timerPreferences, widgetUpdateManager)
            .also(activeViewModels::add)
    }

    private fun TimerViewModel.seedMode(mode: TimerMode) {
        // Drive through the public setMode so totalSeconds / remainingSeconds
        // align with the seeded mode without reaching into private state.
        if (mode != TimerMode.WORK) setMode(mode)
    }

    @Test
    fun nonPomodoro_workCompletes_autoStartBreaksOff_switchesToBreakIdle() = runTest(dispatcher) {
        val vm = newViewModel(pomodoroEnabled = false, autoStartBreaks = false)
        advanceUntilIdle()
        vm.seedMode(TimerMode.WORK)

        vm.onTimerCompleted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(TimerMode.BREAK, state.mode)
        assertEquals(breakSeconds, state.totalSeconds)
        assertEquals(breakSeconds, state.remainingSeconds)
        assertFalse(state.isRunning)
        assertFalse(state.isLongBreak)
    }

    @Test
    fun nonPomodoro_workCompletes_autoStartBreaksOn_switchesToBreakAndStarts() = runTest(dispatcher) {
        val vm = newViewModel(pomodoroEnabled = false, autoStartBreaks = true)
        advanceUntilIdle()
        vm.seedMode(TimerMode.WORK)

        vm.onTimerCompleted()
        // Skip both runCurrent and advanceUntilIdle — onTimerCompleted +
        // start() flip _uiState synchronously. Running the tickJob's
        // body (which calls a suspending mocked widgetUpdateManager
        // followed by `delay(1000)`) captures a real continuation
        // bound to the test dispatcher; that continuation outlives
        // tickJob.cancel() in @After and bleeds into the next test as
        // a Dispatchers.Main / DataStore-actor NPE attributed to
        // whichever test JUnit happens to be running. Reading
        // _uiState.value immediately verifies the synchronous flip
        // without launching the body.

        val state = vm.uiState.value
        assertEquals(TimerMode.BREAK, state.mode)
        assertTrue(state.isRunning)

        // Pause the timer BEFORE returning. runTest's implicit cleanup
        // calls advanceUntilIdle on the scheduler, which would otherwise
        // burn through the entire 25-min tick loop into
        // NotificationHelper.showTimerCompleteNotification(appContext,
        // ...) — and `appContext` is a mockk with no real filesDir, so
        // NotificationPreferences.from(context).timerAlertsEnabled.first()
        // NPEs deep inside DataStore. Cancelling tickJob here keeps
        // runTest's idle-flush a no-op.
        vm.toggleStartPause()
    }

    @Test
    fun nonPomodoro_breakCompletes_autoStartWorkOff_switchesToWorkIdle() = runTest(dispatcher) {
        val vm = newViewModel(pomodoroEnabled = false, autoStartWork = false)
        advanceUntilIdle()
        vm.seedMode(TimerMode.BREAK)

        vm.onTimerCompleted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(TimerMode.WORK, state.mode)
        assertEquals(workSeconds, state.totalSeconds)
        assertEquals(workSeconds, state.remainingSeconds)
        assertFalse(state.isRunning)
    }

    @Test
    fun nonPomodoro_breakCompletes_autoStartWorkOn_switchesToWorkAndStarts() = runTest(dispatcher) {
        val vm = newViewModel(pomodoroEnabled = false, autoStartWork = true)
        advanceUntilIdle()
        vm.seedMode(TimerMode.BREAK)

        vm.onTimerCompleted()
        // See sibling test — skip runCurrent so the tickJob body never
        // executes. Reading _uiState.value verifies the synchronous
        // start() flip without capturing a real continuation that
        // would outlive @After.

        val state = vm.uiState.value
        assertEquals(TimerMode.WORK, state.mode)
        assertTrue(state.isRunning)

        // See sibling test — pause before returning so runTest's
        // implicit advanceUntilIdle cleanup doesn't run the tick loop.
        vm.toggleStartPause()
    }

    @Test
    fun nonPomodoro_customCompletes_modeUnchanged() = runTest(dispatcher) {
        val vm = newViewModel(pomodoroEnabled = false)
        advanceUntilIdle()
        vm.seedMode(TimerMode.CUSTOM)

        vm.onTimerCompleted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(TimerMode.CUSTOM, state.mode)
        assertFalse(state.isRunning)
    }

    @Test
    fun pomodoro_workCompletes_autoStartBreaksOff_regressionLockIn() = runTest(dispatcher) {
        val vm = newViewModel(pomodoroEnabled = true, autoStartBreaks = false)
        advanceUntilIdle()
        // Default seeded mode is WORK; pomodoroEnabled is honoured via the
        // collected flow.

        vm.onTimerCompleted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(TimerMode.BREAK, state.mode)
        assertEquals(1, state.completedSessions)
        assertFalse(state.isRunning)
    }
}
