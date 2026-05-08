package com.averycorp.prismtask.ui.screens.timer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.notifications.NotificationHelper
import com.averycorp.prismtask.widget.TimerStateDataStore
import com.averycorp.prismtask.widget.TimerWidgetState
import com.averycorp.prismtask.widget.WidgetUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private var tickJob: Job? = null

    init {
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

    fun toggleStartPause() {
        val state = _uiState.value
        if (state.isRunning) {
            pause()
        } else {
            start()
        }
    }

    private fun start() {
        val state = _uiState.value
        if (state.remainingSeconds <= 0) return
        _uiState.value = state.copy(isRunning = true)
        syncWidgetState()
        tickJob?.cancel()
        var ticksSinceWidgetUpdate = 0
        tickJob = viewModelScope.launch {
            widgetUpdateManager.updateTimerWidget()
            while (true) {
                delay(1000L)
                val current = _uiState.value
                if (!current.isRunning) break
                val next = current.remainingSeconds - 1
                ticksSinceWidgetUpdate++
                if (next <= 0) {
                    _uiState.value = current.copy(remainingSeconds = 0, isRunning = false)
                    syncWidgetState()
                    widgetUpdateManager.updateTimerWidget()
                    // TODO: migrate countdown to PomodoroTimerService-style
                    //  foreground service so the timer survives backgrounding
                    //  (currently the viewModelScope coroutine is cancelled
                    //  when the ViewModel is cleared).
                    NotificationHelper.showTimerCompleteNotification(
                        appContext,
                        current.mode.name
                    )
                    onTimerCompleted()
                    // onTimerCompleted flips mode (WORK -> BREAK or BREAK ->
                    // WORK) and isLongBreak; resync so the widget reflects
                    // the post-completion state even when both auto-start
                    // flags are off.
                    syncWidgetState()
                    widgetUpdateManager.updateTimerWidget()
                    break
                } else {
                    _uiState.value = current.copy(remainingSeconds = next)
                    // Update widget every 30 seconds for sub-minute accuracy
                    if (ticksSinceWidgetUpdate >= 30) {
                        ticksSinceWidgetUpdate = 0
                        syncWidgetState()
                        widgetUpdateManager.updateTimerWidget()
                    }
                }
            }
        }
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
        tickJob?.cancel()
        tickJob = null

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

    private fun pause() {
        tickJob?.cancel()
        tickJob = null
        _uiState.value = _uiState.value.copy(isRunning = false)
        syncWidgetState()
        viewModelScope.launch { widgetUpdateManager.updateTimerWidget() }
    }

    fun reset() {
        tickJob?.cancel()
        tickJob = null
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
        tickJob?.cancel()
        tickJob = null
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
        tickJob?.cancel()
        tickJob = null
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

    /** Syncs the current timer UI state to the widget DataStore. */
    private fun syncWidgetState() {
        val s = _uiState.value
        viewModelScope.launch {
            // DataStore writes can fail (disk pressure, file-system errors,
            // and most loudly: tests running with a mocked Context that has
            // no real applicationContext.filesDir to back the underlying
            // file). Widget state is best-effort UX — a write failure
            // leaves the widget showing the last successful snapshot but
            // must NEVER take the timer ViewModel down.
            try {
                TimerStateDataStore.write(
                    appContext,
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
            } catch (e: Exception) {
                android.util.Log.w("TimerViewModel", "Widget DataStore write failed", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        // Always clear widget state on close: tickJob has just been
        // cancelled, so any "running" flag in the DataStore would now be
        // a lie and the widget would show a frozen clock. Routed through
        // an application-scoped scope because viewModelScope is already
        // cancelled by the time onCleared runs.
        widgetUpdateManager.clearTimerStateAndUpdate()
    }
}
