package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.medicationDataStore: DataStore<Preferences> by preferencesDataStore(name = "medication_prefs")

enum class MedicationScheduleMode { INTERVAL, SPECIFIC_TIMES }

@Singleton
class MedicationPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val REMINDER_INTERVAL_MINUTES = intPreferencesKey("reminder_interval_minutes")
        private val SCHEDULE_MODE = stringPreferencesKey("schedule_mode")
        private val SPECIFIC_TIMES = stringSetPreferencesKey("specific_times")
        const val DEFAULT_INTERVAL = 0 // 0 = disabled
    }

    fun getReminderIntervalMinutes(): Flow<Int> = context.medicationDataStore.data.map { prefs ->
        prefs[REMINDER_INTERVAL_MINUTES] ?: DEFAULT_INTERVAL
    }

    suspend fun getReminderIntervalMinutesOnce(): Int =
        getReminderIntervalMinutes().first()

    suspend fun setReminderIntervalMinutes(minutes: Int) {
        context.medicationDataStore.edit { prefs ->
            prefs[REMINDER_INTERVAL_MINUTES] = minutes
        }
    }

    fun getScheduleMode(): Flow<MedicationScheduleMode> = context.medicationDataStore.data.map { prefs ->
        when (prefs[SCHEDULE_MODE]) {
            "SPECIFIC_TIMES" -> MedicationScheduleMode.SPECIFIC_TIMES
            else -> MedicationScheduleMode.INTERVAL
        }
    }

    suspend fun getScheduleModeOnce(): MedicationScheduleMode =
        getScheduleMode().first()

    suspend fun setScheduleMode(mode: MedicationScheduleMode) {
        context.medicationDataStore.edit { prefs ->
            prefs[SCHEDULE_MODE] = mode.name
        }
    }

    /** Specific times stored as "HH:mm" strings (e.g. "08:00", "14:30", "21:00") */
    fun getSpecificTimes(): Flow<Set<String>> = context.medicationDataStore.data.map { prefs ->
        prefs[SPECIFIC_TIMES] ?: emptySet()
    }

    suspend fun getSpecificTimesOnce(): Set<String> =
        getSpecificTimes().first()

    suspend fun setSpecificTimes(times: Set<String>) {
        context.medicationDataStore.edit { prefs ->
            prefs[SPECIFIC_TIMES] = times
        }
    }

    suspend fun addSpecificTime(time: String) {
        context.medicationDataStore.edit { prefs ->
            val current = prefs[SPECIFIC_TIMES] ?: emptySet()
            prefs[SPECIFIC_TIMES] = current + time
        }
    }

    suspend fun removeSpecificTime(time: String) {
        context.medicationDataStore.edit { prefs ->
            val current = prefs[SPECIFIC_TIMES] ?: emptySet()
            prefs[SPECIFIC_TIMES] = current - time
        }
    }
}
