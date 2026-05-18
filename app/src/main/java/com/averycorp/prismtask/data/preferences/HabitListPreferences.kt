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

data class BuiltInSortOrders(
    val morning: Int,
    val bedtime: Int,
    val medication: Int,
    val school: Int,
    val leisure: Int,
    val housework: Int
)

/**
 * Habit-list category enable flags, built-in sort offsets, and forgiveness /
 * Today-skip windows.
 *
 * Backed by [PreferenceAccessor] (pilot of T2.2 in
 * `docs/audits/REFACTOR_TIERS_1_3_AUDIT.md`) for the single-key boolean / Int
 * fields. The multi-key bundles ([BuiltInSortOrders] and the legacy
 * morning/bedtime/medication Triple) stay on `edit { }` because they need a
 * single transactional write across multiple keys. Public method signatures
 * are byte-identical to the pre-pilot version.
 */
@Singleton
class HabitListPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    private val ds: DataStore<Preferences> get() = context.habitListDataStore

    private val selfCareEnabled = PreferenceAccessor(ds, SELF_CARE_ENABLED, default = true)
    private val medicationEnabled = PreferenceAccessor(ds, MEDICATION_ENABLED, default = true)
    private val schoolEnabled = PreferenceAccessor(ds, SCHOOL_ENABLED, default = true)
    private val leisureEnabled = PreferenceAccessor(ds, LEISURE_ENABLED, default = true)
    private val houseworkEnabled = PreferenceAccessor(ds, HOUSEWORK_ENABLED, default = true)

    private val streakMaxMissedDaysAccessor =
        PreferenceAccessor(ds, STREAK_MAX_MISSED_DAYS, default = DEFAULT_STREAK_MAX_MISSED_DAYS)
    private val todaySkipAfterCompleteDaysAccessor =
        PreferenceAccessor(ds, TODAY_SKIP_AFTER_COMPLETE_DAYS, default = DEFAULT_TODAY_SKIP_AFTER_COMPLETE_DAYS)
    private val todaySkipBeforeScheduleDaysAccessor =
        PreferenceAccessor(ds, TODAY_SKIP_BEFORE_SCHEDULE_DAYS, default = DEFAULT_TODAY_SKIP_BEFORE_SCHEDULE_DAYS)
    private val skipCapPerWeekAccessor =
        PreferenceAccessor(ds, SKIP_CAP_PER_WEEK, default = DEFAULT_SKIP_CAP_PER_WEEK)

    fun getAutoHabitSortOrders(): Flow<Triple<Int, Int, Int>> = ds.data.map { prefs ->
        Triple(
            prefs[MORNING_SORT_ORDER] ?: DEFAULT_MORNING_ORDER,
            prefs[BEDTIME_SORT_ORDER] ?: DEFAULT_BEDTIME_ORDER,
            prefs[MEDICATION_SORT_ORDER] ?: DEFAULT_MEDICATION_ORDER
        )
    }

    fun getBuiltInSortOrders(): Flow<BuiltInSortOrders> = ds.data.map { prefs ->
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
        ds.edit { prefs ->
            prefs[MORNING_SORT_ORDER] = morningSortOrder
            prefs[BEDTIME_SORT_ORDER] = bedtimeSortOrder
            prefs[MEDICATION_SORT_ORDER] = medicationSortOrder
        }
    }

    suspend fun setBuiltInSortOrders(orders: BuiltInSortOrders) {
        ds.edit { prefs ->
            prefs[MORNING_SORT_ORDER] = orders.morning
            prefs[BEDTIME_SORT_ORDER] = orders.bedtime
            prefs[MEDICATION_SORT_ORDER] = orders.medication
            prefs[SCHOOL_SORT_ORDER] = orders.school
            prefs[LEISURE_SORT_ORDER] = orders.leisure
            prefs[HOUSEWORK_SORT_ORDER] = orders.housework
        }
    }

    fun isSelfCareEnabled(): Flow<Boolean> = selfCareEnabled.flow

    fun isMedicationEnabled(): Flow<Boolean> = medicationEnabled.flow

    fun isSchoolEnabled(): Flow<Boolean> = schoolEnabled.flow

    fun isLeisureEnabled(): Flow<Boolean> = leisureEnabled.flow

    suspend fun setSelfCareEnabled(enabled: Boolean) {
        selfCareEnabled.set(enabled)
    }

    suspend fun setMedicationEnabled(enabled: Boolean) {
        medicationEnabled.set(enabled)
    }

    suspend fun setSchoolEnabled(enabled: Boolean) {
        schoolEnabled.set(enabled)
    }

    suspend fun setLeisureEnabled(enabled: Boolean) {
        leisureEnabled.set(enabled)
    }

    fun isHouseworkEnabled(): Flow<Boolean> = houseworkEnabled.flow

    suspend fun setHouseworkEnabled(enabled: Boolean) {
        houseworkEnabled.set(enabled)
    }

    fun getStreakMaxMissedDays(): Flow<Int> = streakMaxMissedDaysAccessor.flow

    suspend fun setStreakMaxMissedDays(days: Int) {
        streakMaxMissedDaysAccessor.set(days.coerceIn(MIN_STREAK_MAX_MISSED_DAYS, MAX_STREAK_MAX_MISSED_DAYS))
    }

    /**
     * Global default for the Today-screen "skip if completed within N days"
     * window. A per-habit override of -1 (the default for new habits) inherits
     * this value. 0 = the feature is disabled and habits are never hidden on
     * Today based on a recent completion.
     */
    fun getTodaySkipAfterCompleteDays(): Flow<Int> = todaySkipAfterCompleteDaysAccessor.flow

    suspend fun setTodaySkipAfterCompleteDays(days: Int) {
        todaySkipAfterCompleteDaysAccessor.set(days.coerceIn(0, MAX_TODAY_SKIP_DAYS))
    }

    /**
     * Global default for the Today-screen "skip if next scheduled occurrence
     * is within N days" window. Same semantics as
     * [getTodaySkipAfterCompleteDays].
     */
    fun getTodaySkipBeforeScheduleDays(): Flow<Int> = todaySkipBeforeScheduleDaysAccessor.flow

    suspend fun setTodaySkipBeforeScheduleDays(days: Int) {
        todaySkipBeforeScheduleDaysAccessor.set(days.coerceIn(0, MAX_TODAY_SKIP_DAYS))
    }

    /**
     * Max long-press "skip today" actions allowed per habit in a rolling 7-day
     * window. Once the cap is reached, the gesture is rejected (the ViewModel
     * surfaces a snackbar). 0 disables the cap entirely. Applies to every habit
     * — there is no per-habit override.
     */
    fun getSkipCapPerWeek(): Flow<Int> = skipCapPerWeekAccessor.flow

    suspend fun setSkipCapPerWeek(cap: Int) {
        skipCapPerWeekAccessor.set(cap.coerceIn(MIN_SKIP_CAP_PER_WEEK, MAX_SKIP_CAP_PER_WEEK))
    }

    suspend fun clearAll() {
        ds.edit { it.clear() }
    }

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
        private val SKIP_CAP_PER_WEEK = intPreferencesKey("habit_skip_cap_per_week")
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

        /** Default per-habit skip-day allowance in a rolling 7-day window. */
        const val DEFAULT_SKIP_CAP_PER_WEEK = 2
        const val MIN_SKIP_CAP_PER_WEEK = 0
        const val MAX_SKIP_CAP_PER_WEEK = 7

        /** Rolling-window length for the skip cap. */
        const val SKIP_CAP_WINDOW_DAYS = 7
    }
}
