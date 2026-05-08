package com.averycorp.prismtask.ui.screens.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.notifications.NotificationHelper
import com.averycorp.prismtask.notifications.PomodoroTimerService
import com.averycorp.prismtask.widget.TimerWidgetState
import com.averycorp.prismtask.widget.WidgetUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TimerMode { WORK, BREAK, CUSTOM }

data class TimerUiState(
    val mode: TimerMode = TimerMode.WORK,
    val remainingSeconds: Int = TimerPreferences.DEFAULT_WORK_SECONDS,
    val totalSeconds: Int = TimerPreferences.DEFAULT_WORK_SECONDS,
    val isRunning: Boolean = false,
    val pomodoroEnabled: Boolean = false,
    val completedSessions: Int = 0,
    val sessionsUntilLongBreak: Int = TimerPreferences.DEFAULT_SESSIONS_UNTIL_LONG_BREAK,
    val isLongBreak: Boolean = false,
    val autoStartBreaks: Boolean = false,
    val autoStartWork: Boolean = false,
    val customDurationSeconds: Int = TimerPreferences.DEFAULT_CUSTOM_SECONDS
)

@HiltViewModel
class TimerViewModel
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val timerPreferences: TimerPreferences,
    private val widgetUpdateManager: WidgetUpdateManager
) : ViewModel() {
    private val workDurationSeconds: StateFlow<Int> = timerPreferences
        .getWorkDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimerPreferences.DEFAULT_WORK_SECONDS)

    private val breakDurationSeconds: StateFlow<Int> = timerPreferences
        .getBreakDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimerPreferences.DEFAULT_BREAK_SECONDS)

    private val longBreakDurationSeconds: StateFlow<Int> = timerPreferences
        .getLongBreakDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimerPreferences.DEFAULT_LONG_BREAK_SECONDS)

    private val customDurationSeconds: StateFlow<Int> = timerPreferences
        .getCustomDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimerPreferences.DEFAULT_CUSTOM_SECONDS)

    private val _uiState = MutableStateFlow(TimerUiState())
    val uiState: StateFlow<TimerUiState> = _uiState.asStateFlow()

    private var ticksSinceWidgetUpdate: Int = 0

    /**
     * Listens for tick / pause / resume / complete broadcasts from
     * [PomodoroTimerService]. Filters on
     * [PomodoroTimerService.EXTRA_OWNER] == [PomodoroTimerService.OWNER_TIMER]
     * so a SmartPomodoroViewModel session running concurrently doesn't bleed
     * into our state.
     */
    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val incomingOwner = intent?.getStringExtra(PomodoroTimerService.EXTRA_OWNER)
            if (incomingOwner != PomodoroTimerService.OWNER_TIMER) return
            when (intent.action) {
                PomodoroTimerService.ACTION_TICK -> {
                    val seconds = intent.getIntExtra(
                        PomodoroTimerService.EXTRA_SECONDS_REMAINING,
                        -1
                    )
                    if (seconds >= 0) onTick(seconds)
                }
                PomodoroTimerService.ACTION_PAUSED -> onServicePaused()
                PomodoroTimerService.ACTION_RESUMED -> onServiceResumed()
                PomodoroTimerService.ACTION_COMPLETE -> onServiceComplete()
            }
        }
    }

    private var receiverRegistered = false

    init {
        registerTimerReceiver()
        // Sync pomodoro preferences. Also push the new value into
        // TimerStateDataStore so widget consumers see the toggle without
        // waiting for a control tap to flush the snapshot.
        viewModelScope.launch {
            timerPreferences.getPomodoroEnabled().collect { enabled ->
                _uiState.value = _uiState.value.copy(pomodoroEnabled = enabled)
                syncWidgetState()
            }
        }
        viewModelScope.launch {
            timerPreferences.getSessionsUntilLongBreak().collect { sessions ->
                _uiState.value = _uiState.value.copy(sessionsUntilLongBreak = sessions)
                syncWidgetState()
            }
        }
        viewModelScope.launch {
            timerPreferences.getAutoStartBreaks().collect { auto ->
                _uiState.value = _uiState.value.copy(autoStartBreaks = auto)
            }
        }
        viewModelScope.launch {
            timerPreferences.getAutoStartWork().collect { auto ->
                _uiState.value = _uiState.value.copy(autoStartWork = auto)
            }
        }

        // Sync duration preferences (existing logic)
        viewModelScope.launch {
            timerPreferences.getWorkDurationSeconds().collect { work ->
                val current = _uiState.value
                if (current.mode == TimerMode.WORK) {
                    val isIdleAtFull = !current.isRunning &&
                        current.remainingSeconds == current.totalSeconds
                    _uiState.value = current.copy(
                        totalSeconds = work,
                        remainingSeconds = if (isIdleAtFull) work else current.remainingSeconds
                    )
                }
            }
        }
        viewModelScope.launch {
            timerPreferences.getBreakDurationSeconds().collect { brk ->
                val current = _uiState.value
                if (current.mode == TimerMode.BREAK && !current.isLongBreak) {
                    val isIdleAtFull = !current.isRunning &&
                        current.remainingSeconds == current.totalSeconds
                    _uiState.value = current.copy(
                        totalSeconds = brk,
                        remainingSeconds = if (isIdleAtFull) brk else current.remainingSeconds
                    )
                }
            }
        }
        viewModelScope.launch {
            timerPreferences.getLongBreakDurationSeconds().collect { longBrk ->
                val current = _uiState.value
                if (current.mode == TimerMode.BREAK && current.isLongBreak) {
                    val isIdleAtFull = !current.isRunning &&
                        current.remainingSeconds == current.totalSeconds
                    _uiState.value = current.copy(
                        totalSeconds = longBrk,
                        remainingSeconds = if (isIdleAtFull) longBrk else current.remainingSeconds
                    )
                }
            }
        }
        viewModelScope.launch {
            timerPreferences.getCustomDurationSeconds().collect { custom ->
                val current = _uiState.value
                val updated = current.copy(customDurationSeconds = custom)
                _uiState.value = if (current.mode == TimerMode.CUSTOM) {
                    val isIdleAtFull = !current.isRunning &&
                        current.remainingSeconds == current.totalSeconds
                    updated.copy(
                        totalSeconds = custom,
                        remainingSeconds = if (isIdleAtFull) custom else current.remainingSeconds
                    )
                } else {
                    updated
                }
            }
        }
    }

    private fun registerTimerReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(PomodoroTimerService.ACTION_TICK)
                addAction(PomodoroTimerService.ACTION_PAUSED)
                addAction(PomodoroTimerService.ACTION_RESUMED)
                addAction(PomodoroTimerService.ACTION_COMPLETE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(timerReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // Test contexts and edge cases (e.g. early in Application lifecycle)
            // may reject receiver registration; the service still delivers
            // the completion notification via the system notification manager
            // so UI sync is best-effort.
        }
    }

    private fun unregisterTimerReceiver() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(timerReceiver)
        } catch (_: Exception) {
            // Already unregistered or context invalid.
        }
        receiverRegistered = false
    }

    fun toggleStartPause() {
        val state = _uiState.value
        when {
            state.isRunning -> pause()
            // Paused mid-session: remainingSeconds < totalSeconds. Resume the
            // existing service-side countdown rather than starting a fresh
            // session that would reset to totalSeconds.
            state.remainingSeconds in 1 until state.totalSeconds -> resume()
            else -> start()
        }
    }

    private fun start() {
        val state = _uiState.value
        if (state.remainingSeconds <= 0) return
        ticksSinceWidgetUpdate = 0
        _uiState.value = state.copy(isRunning = true)
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
        PomodoroTimerService.start(
            context = appContext,
            durationSeconds = state.remainingSeconds,
            sessionIndex = state.completedSessions,
            sessionType = sessionTypeFor(state),
            owner = PomodoroTimerService.OWNER_TIMER
        )
    }

    private fun resume() {
        val state = _uiState.value
        if (state.remainingSeconds <= 0) return
        _uiState.value = state.copy(isRunning = true)
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
        PomodoroTimerService.resume(appContext)
    }

    private fun pause() {
        _uiState.value = _uiState.value.copy(isRunning = false)
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
        PomodoroTimerService.pause(appContext)
    }

    private fun onTick(secondsRemaining: Int) {
        val current = _uiState.value
        ticksSinceWidgetUpdate++
        // The first tick after a resume / start may set isRunning to true if
        // the service caught up before our optimistic flip. Keep both in
        // sync.
        _uiState.value = current.copy(
            remainingSeconds = secondsRemaining,
            isRunning = secondsRemaining > 0
        )
        // Widget update cadence mirrors the legacy in-memory timer:
        // every 30 ticks for sub-minute accuracy without a flood.
        if (ticksSinceWidgetUpdate >= 30) {
            ticksSinceWidgetUpdate = 0
            syncWidgetState()
            viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
        }
    }

    private fun onServicePaused() {
        _uiState.value = _uiState.value.copy(isRunning = false)
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
    }

    private fun onServiceResumed() {
        _uiState.value = _uiState.value.copy(isRunning = true)
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
    }

    private fun onServiceComplete() {
        val state = _uiState.value
        _uiState.value = state.copy(remainingSeconds = 0, isRunning = false)
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
        // Mirror the in-app completion notification the previous
        // in-memory tick loop fired. The service already shows its own
        // ongoing notification; this surfaces the alert sound/vibration the
        // user expects regardless of which path delivers it.
        viewModelScope.launch {
            NotificationHelper.showTimerCompleteNotification(appContext, state.mode.name)
        }
        onTimerCompleted()
        // onTimerCompleted flips mode (WORK -> BREAK or BREAK ->
        // WORK) and isLongBreak; resync so the widget reflects
        // the post-completion state even when both auto-start
        // flags are off.
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
    }

    @androidx.annotation.VisibleForTesting
    internal fun onTimerCompleted() {
        val state = _uiState.value

        if (!state.pomodoroEnabled) {
            // Non-Pomodoro: auto-switch the work/break tab when the active
            // timer runs out, gated by the same auto-start prefs the
            // Pomodoro branch uses below. CUSTOM has no natural counterpart
            // and stays put.
            when (state.mode) {
                TimerMode.WORK -> {
                    val breakDuration = breakDurationSeconds.value
                    _uiState.value = state.copy(
                        mode = TimerMode.BREAK,
                        isLongBreak = false,
                        remainingSeconds = breakDuration,
                        totalSeconds = breakDuration,
                        isRunning = false
                    )
                    if (state.autoStartBreaks) start()
                }
                TimerMode.BREAK -> {
                    val workDuration = workDurationSeconds.value
                    _uiState.value = state.copy(
                        mode = TimerMode.WORK,
                        isLongBreak = false,
                        remainingSeconds = workDuration,
                        totalSeconds = workDuration,
                        isRunning = false
                    )
                    if (state.autoStartWork) start()
                }
                TimerMode.CUSTOM -> Unit
            }
            return
        }

        if (state.mode == TimerMode.WORK) {
            val newCompleted = state.completedSessions + 1
            val isLongBreak = newCompleted % state.sessionsUntilLongBreak == 0
            val breakDuration = if (isLongBreak) {
                longBreakDurationSeconds.value
            } else {
                breakDurationSeconds.value
            }
            _uiState.value = state.copy(
                mode = TimerMode.BREAK,
                completedSessions = newCompleted,
                isLongBreak = isLongBreak,
                remainingSeconds = breakDuration,
                totalSeconds = breakDuration,
                isRunning = false
            )
            if (state.autoStartBreaks) {
                start()
            }
        } else {
            val workDuration = workDurationSeconds.value
            _uiState.value = state.copy(
                mode = TimerMode.WORK,
                isLongBreak = false,
                remainingSeconds = workDuration,
                totalSeconds = workDuration,
                isRunning = false
            )
            if (state.autoStartWork) {
                start()
            }
        }
    }

    fun skipToNext() {
        val state = _uiState.value
        if (!state.pomodoroEnabled) return
        PomodoroTimerService.stop(appContext)

        if (state.mode == TimerMode.WORK) {
            val newCompleted = state.completedSessions + 1
            val isLongBreak = newCompleted % state.sessionsUntilLongBreak == 0
            val breakDuration = if (isLongBreak) {
                longBreakDurationSeconds.value
            } else {
                breakDurationSeconds.value
            }
            _uiState.value = state.copy(
                mode = TimerMode.BREAK,
                completedSessions = newCompleted,
                isLongBreak = isLongBreak,
                remainingSeconds = breakDuration,
                totalSeconds = breakDuration,
                isRunning = false
            )
        } else {
            val workDuration = workDurationSeconds.value
            _uiState.value = state.copy(
                mode = TimerMode.WORK,
                isLongBreak = false,
                remainingSeconds = workDuration,
                totalSeconds = workDuration,
                isRunning = false
            )
        }
    }

    fun reset() {
        PomodoroTimerService.stop(appContext)
        val state = _uiState.value
        val total = when {
            state.mode == TimerMode.WORK -> workDurationSeconds.value
            state.mode == TimerMode.CUSTOM -> customDurationSeconds.value
            state.isLongBreak -> longBreakDurationSeconds.value
            else -> breakDurationSeconds.value
        }
        _uiState.value = state.copy(
            remainingSeconds = total,
            totalSeconds = total,
            isRunning = false
        )
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
    }

    fun resetPomodoro() {
        PomodoroTimerService.stop(appContext)
        val workDuration = workDurationSeconds.value
        _uiState.value = _uiState.value.copy(
            mode = TimerMode.WORK,
            remainingSeconds = workDuration,
            totalSeconds = workDuration,
            isRunning = false,
            completedSessions = 0,
            isLongBreak = false
        )
    }

    fun setMode(mode: TimerMode) {
        if (_uiState.value.mode == mode) return
        PomodoroTimerService.stop(appContext)
        val total = when (mode) {
            TimerMode.WORK -> workDurationSeconds.value
            TimerMode.BREAK -> breakDurationSeconds.value
            TimerMode.CUSTOM -> customDurationSeconds.value
        }
        _uiState.value = _uiState.value.copy(
            mode = mode,
            remainingSeconds = total,
            totalSeconds = total,
            isRunning = false,
            isLongBreak = false
        )
    }

    fun togglePomodoroEnabled() {
        viewModelScope.launch {
            val newEnabled = !_uiState.value.pomodoroEnabled
            timerPreferences.setPomodoroEnabled(newEnabled)
            if (newEnabled) {
                resetPomodoro()
            }
        }
    }

    fun toggleAutoStartBreaks() {
        viewModelScope.launch {
            timerPreferences.setAutoStartBreaks(!_uiState.value.autoStartBreaks)
        }
    }

    fun toggleAutoStartWork() {
        viewModelScope.launch {
            timerPreferences.setAutoStartWork(!_uiState.value.autoStartWork)
        }
    }

    fun setCustomDurationMinutes(minutes: Int) {
        viewModelScope.launch {
            timerPreferences.setCustomDurationSeconds(minutes * 60)
        }
    }

    private fun sessionTypeFor(state: TimerUiState): String = when {
        state.mode == TimerMode.BREAK && state.isLongBreak ->
            PomodoroTimerService.SESSION_TYPE_LONG_BREAK
        state.mode == TimerMode.BREAK -> PomodoroTimerService.SESSION_TYPE_BREAK
        else -> PomodoroTimerService.SESSION_TYPE_WORK
    }

    /** Syncs the current timer UI state to the widget DataStore. */
    private fun syncWidgetState() {
        val s = _uiState.value
        // Routed through WidgetUpdateManager (which wraps the write in
        // try/catch on its own app-scoped SupervisorJob) so that:
        //   - Real-world DataStore failures (disk pressure, FS errors)
        //     don't take the timer ViewModel down.
        //   - Unit tests with a mocked Context don't NPE on
        //     `Context.filesDir = null`, and the underlying actor
        //     coroutine doesn't leak `Dispatchers.Main` references
        //     across `Dispatchers.setMain` / `resetMain` boundaries
        //     between tests.
        widgetUpdateManager.writeTimerState(
            TimerWidgetState(
                isRunning = s.isRunning,
                isPaused = !s.isRunning && s.remainingSeconds < s.totalSeconds && s.remainingSeconds > 0,
                remainingSeconds = s.remainingSeconds,
                totalSeconds = s.totalSeconds,
                sessionType = when (s.mode) {
                    TimerMode.WORK -> "work"
                    TimerMode.BREAK -> "break"
                    TimerMode.CUSTOM -> "custom"
                },
                currentSession = s.completedSessions + if (s.mode == TimerMode.WORK) 1 else 0,
                totalSessions = s.sessionsUntilLongBreak,
                isLongBreak = s.isLongBreak,
                pomodoroEnabled = s.pomodoroEnabled
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        unregisterTimerReceiver()
        // Don't stop the foreground service here: the whole point of the
        // migration is for the countdown to survive the ViewModel being
        // cleared (process backgrounded, navigation away, configuration
        // change). Same applies to widget state: only clear it if no
        // session is currently running, so the widget keeps showing the
        // active session even after the ViewModel goes away.
        if (!_uiState.value.isRunning) {
            widgetUpdateManager.clearTimerStateAndUpdate()
        }
    }
}
