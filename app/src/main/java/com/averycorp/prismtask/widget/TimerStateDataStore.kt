package com.averycorp.prismtask.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Lightweight DataStore that the timer ViewModel writes to when state
 * changes, and the TimerWidget reads from in provideGlance.
 *
 * ViewModels can't be accessed from widgets directly, so this acts as the
 * shared communication layer between the in-app timer and the widget.
 */
private val Context.timerStateDataStore by preferencesDataStore(name = "timer_widget_state")

data class TimerWidgetState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentTaskTitle: String? = null,
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
    // "work" or "break"
    val sessionType: String = "work",
    val currentSession: Int = 0,
    val totalSessions: Int = 4,
    // True when the current break is a Pomodoro long break. Lets the widget
    // mirror the in-app "Long Break" / "Short Break" label split.
    val isLongBreak: Boolean = false,
    // True when the user has Pomodoro mode on. Drives the session-indicator
    // dot row in the widget — without this the dots would render even for
    // plain work/break/custom sessions where they're meaningless.
    val pomodoroEnabled: Boolean = false
)

object TimerStateDataStore {
    private val IS_RUNNING = booleanPreferencesKey("timer_is_running")
    private val IS_PAUSED = booleanPreferencesKey("timer_is_paused")
    private val TASK_TITLE = stringPreferencesKey("timer_task_title")
    private val REMAINING_SECONDS = intPreferencesKey("timer_remaining_seconds")
    private val TOTAL_SECONDS = intPreferencesKey("timer_total_seconds")
    private val SESSION_TYPE = stringPreferencesKey("timer_session_type")
    private val CURRENT_SESSION = intPreferencesKey("timer_current_session")
    private val TOTAL_SESSIONS = intPreferencesKey("timer_total_sessions")
    private val IS_LONG_BREAK = booleanPreferencesKey("timer_is_long_break")
    private val POMODORO_ENABLED = booleanPreferencesKey("timer_pomodoro_enabled")

    suspend fun write(context: Context, state: TimerWidgetState) {
        context.timerStateDataStore.edit { prefs ->
            prefs[IS_RUNNING] = state.isRunning
            prefs[IS_PAUSED] = state.isPaused
            if (state.currentTaskTitle != null) {
                prefs[TASK_TITLE] = state.currentTaskTitle
            } else {
                prefs.remove(TASK_TITLE)
            }
            prefs[REMAINING_SECONDS] = state.remainingSeconds
            prefs[TOTAL_SECONDS] = state.totalSeconds
            prefs[SESSION_TYPE] = state.sessionType
            prefs[CURRENT_SESSION] = state.currentSession
            prefs[TOTAL_SESSIONS] = state.totalSessions
            prefs[IS_LONG_BREAK] = state.isLongBreak
            prefs[POMODORO_ENABLED] = state.pomodoroEnabled
        }
    }

    /**
     * Updates only the per-tick fields (remaining seconds + run/pause flags)
     * so [PomodoroTimerService] can drive the widget at 1Hz without
     * clobbering structural fields (mode, session counts, pomodoro flag) the
     * ViewModel owns. Structural fields are written via [write] on lifecycle
     * events; live fields are written here on every tick. DataStore edits are
     * atomic so the two writers don't race.
     */
    suspend fun writeLive(
        context: Context,
        remainingSeconds: Int,
        isRunning: Boolean,
        isPaused: Boolean
    ) {
        context.timerStateDataStore.edit { prefs ->
            prefs[REMAINING_SECONDS] = remainingSeconds
            prefs[IS_RUNNING] = isRunning
            prefs[IS_PAUSED] = isPaused
        }
    }

    suspend fun read(context: Context): TimerWidgetState {
        val prefs = context.timerStateDataStore.data.first()
        return TimerWidgetState(
            isRunning = prefs[IS_RUNNING] ?: false,
            isPaused = prefs[IS_PAUSED] ?: false,
            currentTaskTitle = prefs[TASK_TITLE],
            remainingSeconds = prefs[REMAINING_SECONDS] ?: 0,
            totalSeconds = prefs[TOTAL_SECONDS] ?: 0,
            sessionType = prefs[SESSION_TYPE] ?: "work",
            currentSession = prefs[CURRENT_SESSION] ?: 0,
            totalSessions = prefs[TOTAL_SESSIONS] ?: 4,
            isLongBreak = prefs[IS_LONG_BREAK] ?: false,
            pomodoroEnabled = prefs[POMODORO_ENABLED] ?: false
        )
    }

    suspend fun clear(context: Context) {
        context.timerStateDataStore.edit { it.clear() }
    }
}
