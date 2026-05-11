package com.averycorp.prismtask.data.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed preferences for Google Calendar API sync.
 *
 * Takes a [DataStore] directly so it can be unit-tested without an Android
 * Context; production wiring lives in [com.averycorp.prismtask.di.PreferencesModule].
 */
class CalendarSyncPreferences(private val dataStore: DataStore<Preferences>) {
    companion object {
        val CALENDAR_SYNC_ENABLED = booleanPreferencesKey("gcal_sync_enabled")
        val SYNC_CALENDAR_ID = stringPreferencesKey("gcal_sync_calendar_id")
        val SYNC_DIRECTION = stringPreferencesKey("gcal_sync_direction")
        val SHOW_CALENDAR_EVENTS = booleanPreferencesKey("gcal_show_events")
        val SELECTED_DISPLAY_CALENDAR_IDS = stringSetPreferencesKey("gcal_display_calendar_ids")
        val SYNC_FREQUENCY = stringPreferencesKey("gcal_sync_frequency")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("gcal_last_sync_timestamp")
        val SYNC_COMPLETED_TASKS = booleanPreferencesKey("gcal_sync_completed_tasks")
    }

    // --- Sync Enabled ---
    fun isCalendarSyncEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CALENDAR_SYNC_ENABLED] ?: false
    }

    suspend fun getCalendarSyncEnabled(): Boolean = isCalendarSyncEnabled().first()

    suspend fun setCalendarSyncEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[CALENDAR_SYNC_ENABLED] = enabled }
    }

    // --- Sync Calendar ID ---
    fun getSyncCalendarId(): Flow<String> = dataStore.data.map { prefs ->
        prefs[SYNC_CALENDAR_ID] ?: "primary"
    }

    suspend fun getSyncCalendarIdOnce(): String = getSyncCalendarId().first()

    suspend fun setSyncCalendarId(calendarId: String) {
        dataStore.edit { prefs -> prefs[SYNC_CALENDAR_ID] = calendarId }
    }

    // --- Sync Direction ---
    fun getSyncDirection(): Flow<String> = dataStore.data.map { prefs ->
        prefs[SYNC_DIRECTION] ?: DIRECTION_BOTH
    }

    suspend fun getSyncDirectionOnce(): String = getSyncDirection().first()

    suspend fun setSyncDirection(direction: String) {
        dataStore.edit { prefs -> prefs[SYNC_DIRECTION] = direction }
    }

    // --- Show Calendar Events ---
    fun getShowCalendarEvents(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_CALENDAR_EVENTS] ?: true
    }

    suspend fun setShowCalendarEvents(show: Boolean) {
        dataStore.edit { prefs -> prefs[SHOW_CALENDAR_EVENTS] = show }
    }

    // --- Selected Display Calendar IDs ---
    fun getSelectedDisplayCalendarIds(): Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[SELECTED_DISPLAY_CALENDAR_IDS] ?: emptySet()
    }

    suspend fun setSelectedDisplayCalendarIds(ids: Set<String>) {
        dataStore.edit { prefs -> prefs[SELECTED_DISPLAY_CALENDAR_IDS] = ids }
    }

    // --- Sync Frequency ---
    fun getSyncFrequency(): Flow<String> = dataStore.data.map { prefs ->
        prefs[SYNC_FREQUENCY] ?: FREQUENCY_15MIN
    }

    suspend fun setSyncFrequency(frequency: String) {
        dataStore.edit { prefs -> prefs[SYNC_FREQUENCY] = frequency }
    }

    // --- Last Sync Timestamp ---
    fun getLastSyncTimestamp(): Flow<Long> = dataStore.data.map { prefs ->
        prefs[LAST_SYNC_TIMESTAMP] ?: 0L
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { prefs -> prefs[LAST_SYNC_TIMESTAMP] = timestamp }
    }

    // --- Sync Completed Tasks ---
    fun getSyncCompletedTasks(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SYNC_COMPLETED_TASKS] ?: false
    }

    suspend fun setSyncCompletedTasks(sync: Boolean) {
        dataStore.edit { prefs -> prefs[SYNC_COMPLETED_TASKS] = sync }
    }

    // --- Clear All ---
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}

// Sync direction constants
const val DIRECTION_PUSH = "push"
const val DIRECTION_PULL = "pull"
const val DIRECTION_BOTH = "both"

// Sync frequency constants
const val FREQUENCY_REALTIME = "realtime"
const val FREQUENCY_15MIN = "15min"
const val FREQUENCY_HOURLY = "hourly"
const val FREQUENCY_MANUAL = "manual"
