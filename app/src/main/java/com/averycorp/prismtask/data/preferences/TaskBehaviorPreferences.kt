package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.taskBehaviorDataStore: DataStore<Preferences> by preferencesDataStore(name = "task_behavior_prefs")

data class UrgencyWeights(val dueDate: Float = 0.40f, val priority: Float = 0.30f, val age: Float = 0.15f, val subtasks: Float = 0.15f)

/**
 * User-configured Start of Day (SoD). Hour is 0..23, minute is 0..59.
 * [hasBeenSet] is true once the user has explicitly confirmed a value
 * (through the first-launch prompt or Settings). Defaults are 0:00 so
 * the app behaves identically to midnight-based day boundaries until
 * the user opts in.
 */
data class StartOfDay(val hour: Int = 0, val minute: Int = 0, val hasBeenSet: Boolean = false)

/**
 * Read-only source of truth for the current Start of Day, used by components
 * that need SoD outside of the DataStore-aware flow model (e.g. the offline
 * NLP regex parser, which cannot suspend). Production binds to
 * [TaskBehaviorPreferences.getStartOfDay] via Hilt; tests substitute a
 * fixed provider.
 */
interface StartOfDayProvider {
    /** Suspending read — preferred for coroutine contexts. */
    suspend fun current(): StartOfDay
}

@Singleton
class TaskBehaviorPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val DEFAULT_SORT = stringPreferencesKey("default_sort")
        private val DEFAULT_VIEW_MODE = stringPreferencesKey("default_view_mode")
        private val URGENCY_WEIGHT_DUE_DATE = floatPreferencesKey("urgency_weight_due_date")
        private val URGENCY_WEIGHT_PRIORITY = floatPreferencesKey("urgency_weight_priority")
        private val URGENCY_WEIGHT_AGE = floatPreferencesKey("urgency_weight_age")
        private val URGENCY_WEIGHT_SUBTASKS = floatPreferencesKey("urgency_weight_subtasks")
        private val REMINDER_PRESETS = stringPreferencesKey("reminder_presets")
        private val FIRST_DAY_OF_WEEK = stringPreferencesKey("first_day_of_week")
        private val DAY_START_HOUR = intPreferencesKey("day_start_hour")
        private val DAY_START_MINUTE = intPreferencesKey("day_start_minute")
        private val HAS_SET_START_OF_DAY = booleanPreferencesKey("has_set_start_of_day")
    }

    fun getDefaultSort(): Flow<String> = context.taskBehaviorDataStore.data.map { prefs ->
        prefs[DEFAULT_SORT] ?: "DUE_DATE"
    }

    fun getDefaultViewMode(): Flow<String> = context.taskBehaviorDataStore.data.map { prefs ->
        prefs[DEFAULT_VIEW_MODE] ?: "UPCOMING"
    }

    fun getUrgencyWeights(): Flow<UrgencyWeights> {
        val dueDateFlow = context.taskBehaviorDataStore.data.map { it[URGENCY_WEIGHT_DUE_DATE] ?: 0.40f }
        val priorityFlow = context.taskBehaviorDataStore.data.map { it[URGENCY_WEIGHT_PRIORITY] ?: 0.30f }
        val ageFlow = context.taskBehaviorDataStore.data.map { it[URGENCY_WEIGHT_AGE] ?: 0.15f }
        val subtasksFlow = context.taskBehaviorDataStore.data.map { it[URGENCY_WEIGHT_SUBTASKS] ?: 0.15f }
        return combine(dueDateFlow, priorityFlow, ageFlow, subtasksFlow) { d, p, a, s ->
            UrgencyWeights(d, p, a, s)
        }
    }

    fun getReminderPresets(): Flow<List<Long>> = context.taskBehaviorDataStore.data.map { prefs ->
        prefs[REMINDER_PRESETS]?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
            ?: listOf(0L, 900_000L, 1_800_000L, 3_600_000L, 86_400_000L)
    }

    fun getFirstDayOfWeek(): Flow<DayOfWeek> = context.taskBehaviorDataStore.data.map { prefs ->
        prefs[FIRST_DAY_OF_WEEK]?.let { DayOfWeek.valueOf(it) } ?: DayOfWeek.MONDAY
    }

    fun getDayStartHour(): Flow<Int> = context.taskBehaviorDataStore.data.map { prefs ->
        prefs[DAY_START_HOUR] ?: 0
    }

    fun getDayStartMinute(): Flow<Int> = context.taskBehaviorDataStore.data.map { prefs ->
        prefs[DAY_START_MINUTE] ?: 0
    }

    fun getHasSetStartOfDay(): Flow<Boolean> = context.taskBehaviorDataStore.data.map { prefs ->
        prefs[HAS_SET_START_OF_DAY] ?: false
    }

    fun getStartOfDay(): Flow<StartOfDay> = context.taskBehaviorDataStore.data.map { prefs ->
        StartOfDay(
            hour = (prefs[DAY_START_HOUR] ?: 0).coerceIn(0, 23),
            minute = (prefs[DAY_START_MINUTE] ?: 0).coerceIn(0, 59),
            hasBeenSet = prefs[HAS_SET_START_OF_DAY] ?: false
        )
    }

    suspend fun setDefaultSort(sort: String) {
        context.taskBehaviorDataStore.edit { it[DEFAULT_SORT] = sort }
    }

    suspend fun setDefaultViewMode(mode: String) {
        context.taskBehaviorDataStore.edit { it[DEFAULT_VIEW_MODE] = mode }
    }

    suspend fun setUrgencyWeights(weights: UrgencyWeights) {
        context.taskBehaviorDataStore.edit { prefs ->
            prefs[URGENCY_WEIGHT_DUE_DATE] = weights.dueDate
            prefs[URGENCY_WEIGHT_PRIORITY] = weights.priority
            prefs[URGENCY_WEIGHT_AGE] = weights.age
            prefs[URGENCY_WEIGHT_SUBTASKS] = weights.subtasks
        }
    }

    suspend fun setReminderPresets(presets: List<Long>) {
        context.taskBehaviorDataStore.edit { prefs ->
            prefs[REMINDER_PRESETS] = presets.joinToString(",")
        }
    }

    suspend fun setFirstDayOfWeek(day: DayOfWeek) {
        context.taskBehaviorDataStore.edit { it[FIRST_DAY_OF_WEEK] = day.name }
    }

    suspend fun setDayStartHour(hour: Int) {
        context.taskBehaviorDataStore.edit { it[DAY_START_HOUR] = hour.coerceIn(0, 23) }
    }

    suspend fun setDayStartMinute(minute: Int) {
        context.taskBehaviorDataStore.edit { it[DAY_START_MINUTE] = minute.coerceIn(0, 59) }
    }

    /**
     * Atomically set SoD hour, minute, and mark [HAS_SET_START_OF_DAY] true.
     * Called from the first-launch prompt and the Settings wheel picker so
     * that a single user action never leaves the flag out of sync with the
     * chosen value.
     */
    suspend fun setStartOfDay(hour: Int, minute: Int) {
        context.taskBehaviorDataStore.edit { prefs ->
            prefs[DAY_START_HOUR] = hour.coerceIn(0, 23)
            prefs[DAY_START_MINUTE] = minute.coerceIn(0, 59)
            prefs[HAS_SET_START_OF_DAY] = true
        }
    }

    suspend fun setHasSetStartOfDay(value: Boolean) {
        context.taskBehaviorDataStore.edit { it[HAS_SET_START_OF_DAY] = value }
    }

    suspend fun resetToDefaults() {
        context.taskBehaviorDataStore.edit { prefs ->
            prefs.remove(DEFAULT_SORT)
            prefs.remove(DEFAULT_VIEW_MODE)
            prefs.remove(URGENCY_WEIGHT_DUE_DATE)
            prefs.remove(URGENCY_WEIGHT_PRIORITY)
            prefs.remove(URGENCY_WEIGHT_AGE)
            prefs.remove(URGENCY_WEIGHT_SUBTASKS)
            prefs.remove(REMINDER_PRESETS)
            prefs.remove(FIRST_DAY_OF_WEEK)
            prefs.remove(DAY_START_HOUR)
            prefs.remove(DAY_START_MINUTE)
            // [HAS_SET_START_OF_DAY] is intentionally NOT reset — if the user has
            // already completed the first-launch prompt, resetting Task Behavior
            // defaults shouldn't trigger the prompt again.
        }
    }
}
