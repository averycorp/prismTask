package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.habitListDataStore: DataStore<Preferences> by preferencesDataStore(name = "habit_list_prefs")

data class BuiltInSortOrders(val morning: Int, val bedtime: Int, val medication: Int, val school: Int, val leisure: Int, val housework: Int)

@Singleton
class HabitListPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val MORNING_SORT_ORDER = intPreferencesKey("morning_sort_order")
        private val BEDTIME_SORT_ORDER = intPreferencesKey("bedtime_sort_order")
        private val MEDICATION_SORT_ORDER = intPreferencesKey("medication_sort_order")
        private val SCHOOL_SORT_ORDER = intPreferencesKey("school_sort_order")
        private val LEISURE_SORT_ORDER = intPreferencesKey("leisure_sort_order")
        private val HOUSEWORK_SORT_ORDER = intPreferencesKey("housework_sort_order")
        private val SELF_CARE_ENABLED = booleanPreferencesKey("self_care_enabled")
        private val MEDICATION_ENABLED = booleanPreferencesKey("medication_enabled")
        private val SCHOOL_ENABLED = booleanPreferencesKey("school_enabled")
        private val LEISURE_ENABLED = booleanPreferencesKey("leisure_enabled")
        private val HOUSEWORK_ENABLED = booleanPreferencesKey("housework_enabled")
        private val STREAK_MAX_MISSED_DAYS = intPreferencesKey("streak_max_missed_days")
        private val TODAY_SKIP_AFTER_COMPLETE_DAYS =
            intPreferencesKey("today_skip_after_complete_days")
        private val TODAY_SKIP_BEFORE_SCHEDULE_DAYS =
            intPreferencesKey("today_skip_before_schedule_days")
        const val DEFAULT_MORNING_ORDER = -6
        const val DEFAULT_BEDTIME_ORDER = -5
        const val DEFAULT_MEDICATION_ORDER = -4
        const val DEFAULT_HOUSEWORK_ORDER = -3
        const val DEFAULT_SCHOOL_ORDER = -2
        const val DEFAULT_LEISURE_ORDER = -1
        const val DEFAULT_STREAK_MAX_MISSED_DAYS = 1
        const val MIN_STREAK_MAX_MISSED_DAYS = 1
        const val MAX_STREAK_MAX_MISSED_DAYS = 7

        /** Default global "skip on Today if completed within N days" window. 0 = disabled. */
        const val DEFAULT_TODAY_SKIP_AFTER_COMPLETE_DAYS = 0

        /** Default global "skip on Today if next occurrence is within N days" window. 0 = disabled. */
        const val DEFAULT_TODAY_SKIP_BEFORE_SCHEDULE_DAYS = 0

        const val MAX_TODAY_SKIP_DAYS = 30
    }

    fun getAutoHabitSortOrders(): Flow<Triple<Int, Int, Int>> = context.habitListDataStore.data.map { prefs ->
        Triple(
            prefs[MORNING_SORT_ORDER] ?: DEFAULT_MORNING_ORDER,
            prefs[BEDTIME_SORT_ORDER] ?: DEFAULT_BEDTIME_ORDER,
            prefs[MEDICATION_SORT_ORDER] ?: DEFAULT_MEDICATION_ORDER
        )
    }

    fun getBuiltInSortOrders(): Flow<BuiltInSortOrders> = context.habitListDataStore.data.map { prefs ->
        BuiltInSortOrders(
            morning = prefs[MORNING_SORT_ORDER] ?: DEFAULT_MORNING_ORDER,
            bedtime = prefs[BEDTIME_SORT_ORDER] ?: DEFAULT_BEDTIME_ORDER,
            medication = prefs[MEDICATION_SORT_ORDER] ?: DEFAULT_MEDICATION_ORDER,
            school = prefs[SCHOOL_SORT_ORDER] ?: DEFAULT_SCHOOL_ORDER,
            leisure = prefs[LEISURE_SORT_ORDER] ?: DEFAULT_LEISURE_ORDER,
            housework = prefs[HOUSEWORK_SORT_ORDER] ?: DEFAULT_HOUSEWORK_ORDER
        )
    }

    suspend fun setAutoHabitSortOrders(morningSortOrder: Int, bedtimeSortOrder: Int, medicationSortOrder: Int) {
        context.habitListDataStore.edit { prefs ->
            prefs[MORNING_SORT_ORDER] = morningSortOrder
            prefs[BEDTIME_SORT_ORDER] = bedtimeSortOrder
            prefs[MEDICATION_SORT_ORDER] = medicationSortOrder
        }
    }

    suspend fun setBuiltInSortOrders(orders: BuiltInSortOrders) {
        context.habitListDataStore.edit { prefs ->
            prefs[MORNING_SORT_ORDER] = orders.morning
            prefs[BEDTIME_SORT_ORDER] = orders.bedtime
            prefs[MEDICATION_SORT_ORDER] = orders.medication
            prefs[SCHOOL_SORT_ORDER] = orders.school
            prefs[LEISURE_SORT_ORDER] = orders.leisure
            prefs[HOUSEWORK_SORT_ORDER] = orders.housework
        }
    }

    fun isSelfCareEnabled(): Flow<Boolean> = context.habitListDataStore.data.map { prefs ->
        prefs[SELF_CARE_ENABLED] ?: true
    }

    fun isMedicationEnabled(): Flow<Boolean> = context.habitListDataStore.data.map { prefs ->
        prefs[MEDICATION_ENABLED] ?: true
    }

    fun isSchoolEnabled(): Flow<Boolean> = context.habitListDataStore.data.map { prefs ->
        prefs[SCHOOL_ENABLED] ?: true
    }

    fun isLeisureEnabled(): Flow<Boolean> = context.habitListDataStore.data.map { prefs ->
        prefs[LEISURE_ENABLED] ?: true
    }

    suspend fun setSelfCareEnabled(enabled: Boolean) {
        context.habitListDataStore.edit { prefs -> prefs[SELF_CARE_ENABLED] = enabled }
    }

    suspend fun setMedicationEnabled(enabled: Boolean) {
        context.habitListDataStore.edit { prefs -> prefs[MEDICATION_ENABLED] = enabled }
    }

    suspend fun setSchoolEnabled(enabled: Boolean) {
        context.habitListDataStore.edit { prefs -> prefs[SCHOOL_ENABLED] = enabled }
    }

    suspend fun setLeisureEnabled(enabled: Boolean) {
        context.habitListDataStore.edit { prefs -> prefs[LEISURE_ENABLED] = enabled }
    }

    fun isHouseworkEnabled(): Flow<Boolean> = context.habitListDataStore.data.map { prefs ->
        prefs[HOUSEWORK_ENABLED] ?: true
    }

    suspend fun setHouseworkEnabled(enabled: Boolean) {
        context.habitListDataStore.edit { prefs -> prefs[HOUSEWORK_ENABLED] = enabled }
    }

    fun getStreakMaxMissedDays(): Flow<Int> = context.habitListDataStore.data.map { prefs ->
        prefs[STREAK_MAX_MISSED_DAYS] ?: DEFAULT_STREAK_MAX_MISSED_DAYS
    }

    suspend fun setStreakMaxMissedDays(days: Int) {
        context.habitListDataStore.edit { prefs ->
            prefs[STREAK_MAX_MISSED_DAYS] = days.coerceIn(MIN_STREAK_MAX_MISSED_DAYS, MAX_STREAK_MAX_MISSED_DAYS)
        }
    }

    /**
     * Global default for the Today-screen "skip if completed within N days"
     * window. A per-habit override of -1 (the default for new habits) inherits
     * this value. 0 = the feature is disabled and habits are never hidden on
     * Today based on a recent completion.
     */
    fun getTodaySkipAfterCompleteDays(): Flow<Int> = context.habitListDataStore.data.map { prefs ->
        prefs[TODAY_SKIP_AFTER_COMPLETE_DAYS] ?: DEFAULT_TODAY_SKIP_AFTER_COMPLETE_DAYS
    }

    suspend fun setTodaySkipAfterCompleteDays(days: Int) {
        context.habitListDataStore.edit { prefs ->
            prefs[TODAY_SKIP_AFTER_COMPLETE_DAYS] = days.coerceIn(0, MAX_TODAY_SKIP_DAYS)
        }
    }

    /**
     * Global default for the Today-screen "skip if next scheduled occurrence
     * is within N days" window. Same semantics as
     * [getTodaySkipAfterCompleteDays].
     */
    fun getTodaySkipBeforeScheduleDays(): Flow<Int> = context.habitListDataStore.data.map { prefs ->
        prefs[TODAY_SKIP_BEFORE_SCHEDULE_DAYS] ?: DEFAULT_TODAY_SKIP_BEFORE_SCHEDULE_DAYS
    }

    suspend fun setTodaySkipBeforeScheduleDays(days: Int) {
        context.habitListDataStore.edit { prefs ->
            prefs[TODAY_SKIP_BEFORE_SCHEDULE_DAYS] = days.coerceIn(0, MAX_TODAY_SKIP_DAYS)
        }
    }

    suspend fun clearAll() {
        context.habitListDataStore.edit { it.clear() }
    }
}
