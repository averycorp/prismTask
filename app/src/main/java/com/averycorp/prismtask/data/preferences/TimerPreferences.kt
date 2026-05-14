package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.timerDataStore: DataStore<Preferences> by preferencesDataStore(name = "timer_prefs")

@Singleton
class TimerPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val WORK_DURATION_SECONDS = intPreferencesKey("work_duration_seconds")
        private val BREAK_DURATION_SECONDS = intPreferencesKey("break_duration_seconds")
        private val LONG_BREAK_DURATION_SECONDS = intPreferencesKey("long_break_duration_seconds")
        private val CUSTOM_DURATION_SECONDS = intPreferencesKey("custom_duration_seconds")
        private val POMODORO_ENABLED = intPreferencesKey("pomodoro_enabled")
        private val SESSIONS_UNTIL_LONG_BREAK = intPreferencesKey("sessions_until_long_break")
        private val AUTO_START_BREAKS = intPreferencesKey("auto_start_breaks")
        private val AUTO_START_WORK = intPreferencesKey("auto_start_work")
        private val POMODORO_AVAILABLE_MINUTES = intPreferencesKey("pomodoro_available_minutes")
        private val POMODORO_FOCUS_PREFERENCE = stringPreferencesKey("pomodoro_focus_preference")
        private val BUZZ_UNTIL_DISMISSED = booleanPreferencesKey("timer_buzz_until_dismissed")
        private val OVERRIDE_VOLUME = booleanPreferencesKey("timer_override_volume")
        private val ALARM_VOLUME_PERCENT = intPreferencesKey("timer_alarm_volume_percent")

        // ---- A2 Pomodoro+ AI Coaching toggles ---------------------------
        // Distinct subsection so parallel Weekly Task Summary work doesn't
        // collide on key names. All three default ON per spec.
        private val POMODORO_AI_PRE_SESSION = booleanPreferencesKey("pomodoro_ai_pre_session_coaching")
        private val POMODORO_AI_BREAK = booleanPreferencesKey("pomodoro_ai_break_coaching")
        private val POMODORO_AI_RECAP = booleanPreferencesKey("pomodoro_ai_recap_coaching")

        const val DEFAULT_WORK_SECONDS = 25 * 60
        const val DEFAULT_BREAK_SECONDS = 5 * 60
        const val DEFAULT_LONG_BREAK_SECONDS = 15 * 60
        const val DEFAULT_CUSTOM_SECONDS = 10 * 60
        const val DEFAULT_SESSIONS_UNTIL_LONG_BREAK = 4
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 180 * 60

        const val DEFAULT_AVAILABLE_MINUTES = 120
        const val MIN_AVAILABLE_MINUTES = 30
        const val MAX_AVAILABLE_MINUTES = 480

        const val DEFAULT_FOCUS_PREFERENCE = "balanced"
        val VALID_FOCUS_PREFERENCES = setOf("deep_work", "quick_wins", "balanced", "deadline_driven")

        const val DEFAULT_ALARM_VOLUME_PERCENT = 80
        const val MIN_ALARM_VOLUME_PERCENT = 1
        const val MAX_ALARM_VOLUME_PERCENT = 100
    }

    fun getWorkDurationSeconds(): Flow<Int> = context.timerDataStore.data.map { prefs ->
        prefs[WORK_DURATION_SECONDS] ?: DEFAULT_WORK_SECONDS
    }

    fun getBreakDurationSeconds(): Flow<Int> = context.timerDataStore.data.map { prefs ->
        prefs[BREAK_DURATION_SECONDS] ?: DEFAULT_BREAK_SECONDS
    }

    fun getLongBreakDurationSeconds(): Flow<Int> = context.timerDataStore.data.map { prefs ->
        prefs[LONG_BREAK_DURATION_SECONDS] ?: DEFAULT_LONG_BREAK_SECONDS
    }

    fun getCustomDurationSeconds(): Flow<Int> = context.timerDataStore.data.map { prefs ->
        prefs[CUSTOM_DURATION_SECONDS] ?: DEFAULT_CUSTOM_SECONDS
    }

    fun getPomodoroEnabled(): Flow<Boolean> = context.timerDataStore.data.map { prefs ->
        (prefs[POMODORO_ENABLED] ?: 0) == 1
    }

    fun getSessionsUntilLongBreak(): Flow<Int> = context.timerDataStore.data.map { prefs ->
        prefs[SESSIONS_UNTIL_LONG_BREAK] ?: DEFAULT_SESSIONS_UNTIL_LONG_BREAK
    }

    fun getAutoStartBreaks(): Flow<Boolean> = context.timerDataStore.data.map { prefs ->
        (prefs[AUTO_START_BREAKS] ?: 0) == 1
    }

    fun getAutoStartWork(): Flow<Boolean> = context.timerDataStore.data.map { prefs ->
        (prefs[AUTO_START_WORK] ?: 0) == 1
    }

    suspend fun setWorkDurationSeconds(seconds: Int) {
        context.timerDataStore.edit { prefs ->
            prefs[WORK_DURATION_SECONDS] = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        }
    }

    suspend fun setBreakDurationSeconds(seconds: Int) {
        context.timerDataStore.edit { prefs ->
            prefs[BREAK_DURATION_SECONDS] = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        }
    }

    suspend fun setLongBreakDurationSeconds(seconds: Int) {
        context.timerDataStore.edit { prefs ->
            prefs[LONG_BREAK_DURATION_SECONDS] = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        }
    }

    suspend fun setCustomDurationSeconds(seconds: Int) {
        context.timerDataStore.edit { prefs ->
            prefs[CUSTOM_DURATION_SECONDS] = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        }
    }

    suspend fun setPomodoroEnabled(enabled: Boolean) {
        context.timerDataStore.edit { prefs ->
            prefs[POMODORO_ENABLED] = if (enabled) 1 else 0
        }
    }

    suspend fun setSessionsUntilLongBreak(sessions: Int) {
        context.timerDataStore.edit { prefs ->
            prefs[SESSIONS_UNTIL_LONG_BREAK] = sessions.coerceIn(2, 10)
        }
    }

    suspend fun setAutoStartBreaks(enabled: Boolean) {
        context.timerDataStore.edit { prefs ->
            prefs[AUTO_START_BREAKS] = if (enabled) 1 else 0
        }
    }

    suspend fun setAutoStartWork(enabled: Boolean) {
        context.timerDataStore.edit { prefs ->
            prefs[AUTO_START_WORK] = if (enabled) 1 else 0
        }
    }

    fun getPomodoroAvailableMinutes(): Flow<Int> = context.timerDataStore.data.map { prefs ->
        prefs[POMODORO_AVAILABLE_MINUTES] ?: DEFAULT_AVAILABLE_MINUTES
    }

    suspend fun setPomodoroAvailableMinutes(minutes: Int) {
        context.timerDataStore.edit { prefs ->
            prefs[POMODORO_AVAILABLE_MINUTES] = minutes.coerceIn(MIN_AVAILABLE_MINUTES, MAX_AVAILABLE_MINUTES)
        }
    }

    fun getPomodoroFocusPreference(): Flow<String> = context.timerDataStore.data.map { prefs ->
        prefs[POMODORO_FOCUS_PREFERENCE] ?: DEFAULT_FOCUS_PREFERENCE
    }

    suspend fun setPomodoroFocusPreference(preference: String) {
        val sanitized = if (preference in VALID_FOCUS_PREFERENCES) preference else DEFAULT_FOCUS_PREFERENCE
        context.timerDataStore.edit { prefs ->
            prefs[POMODORO_FOCUS_PREFERENCE] = sanitized
        }
    }

    fun getBuzzUntilDismissed(): Flow<Boolean> = context.timerDataStore.data.map { prefs ->
        prefs[BUZZ_UNTIL_DISMISSED] ?: false
    }

    suspend fun setBuzzUntilDismissed(enabled: Boolean) {
        context.timerDataStore.edit { prefs ->
            prefs[BUZZ_UNTIL_DISMISSED] = enabled
        }
    }

    fun getOverrideVolume(): Flow<Boolean> = context.timerDataStore.data.map { prefs ->
        prefs[OVERRIDE_VOLUME] ?: false
    }

    suspend fun setOverrideVolume(enabled: Boolean) {
        context.timerDataStore.edit { prefs ->
            prefs[OVERRIDE_VOLUME] = enabled
        }
    }

    fun getAlarmVolumePercent(): Flow<Int> = context.timerDataStore.data.map { prefs ->
        prefs[ALARM_VOLUME_PERCENT] ?: DEFAULT_ALARM_VOLUME_PERCENT
    }

    suspend fun setAlarmVolumePercent(percent: Int) {
        context.timerDataStore.edit { prefs ->
            prefs[ALARM_VOLUME_PERCENT] = percent.coerceIn(MIN_ALARM_VOLUME_PERCENT, MAX_ALARM_VOLUME_PERCENT)
        }
    }

    // ---- A2 Pomodoro+ AI Coaching accessors ---------------------------
    // All three default true; the getters apply the default on missing key.

    fun getPomodoroPreSessionCoachingEnabled(): Flow<Boolean> =
        context.timerDataStore.data.map { prefs -> prefs[POMODORO_AI_PRE_SESSION] ?: true }

    fun getPomodoroBreakCoachingEnabled(): Flow<Boolean> =
        context.timerDataStore.data.map { prefs -> prefs[POMODORO_AI_BREAK] ?: true }

    fun getPomodoroRecapCoachingEnabled(): Flow<Boolean> =
        context.timerDataStore.data.map { prefs -> prefs[POMODORO_AI_RECAP] ?: true }

    suspend fun setPomodoroPreSessionCoachingEnabled(enabled: Boolean) {
        context.timerDataStore.edit { prefs -> prefs[POMODORO_AI_PRE_SESSION] = enabled }
    }

    suspend fun setPomodoroBreakCoachingEnabled(enabled: Boolean) {
        context.timerDataStore.edit { prefs -> prefs[POMODORO_AI_BREAK] = enabled }
    }

    suspend fun setPomodoroRecapCoachingEnabled(enabled: Boolean) {
        context.timerDataStore.edit { prefs -> prefs[POMODORO_AI_RECAP] = enabled }
    }
}
