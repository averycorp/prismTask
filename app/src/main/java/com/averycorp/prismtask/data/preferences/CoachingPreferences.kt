package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.coachingDataStore: DataStore<Preferences> by preferencesDataStore(name = "coaching_prefs")

/**
 * Persists AI coaching state: free-tier daily breakdown counter,
 * last app open timestamp (for welcome-back detection), and
 * energy check-in dismissal state.
 */
@Singleton
class CoachingPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val BREAKDOWN_COUNT_KEY = intPreferencesKey("ai_breakdown_count")
        private val BREAKDOWN_DATE_KEY = stringPreferencesKey("ai_breakdown_date")
        private val LAST_APP_OPEN_KEY = longPreferencesKey("last_app_open")
        private val ENERGY_CHECKIN_DATE_KEY = stringPreferencesKey("energy_checkin_date")
        private val ENERGY_LEVEL_KEY = stringPreferencesKey("energy_level")
        private val WELCOME_BACK_DISMISSED_DATE_KEY = stringPreferencesKey("welcome_back_dismissed_date")

        const val FREE_BREAKDOWN_DAILY_LIMIT = 3
    }

    private fun todayString(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    // region Free-tier breakdown counter

    /**
     * Returns how many AI breakdowns the free user has used today.
     * Resets automatically when the date rolls over.
     */
    suspend fun getBreakdownCountToday(): Int {
        val prefs = context.coachingDataStore.data.first()
        val storedDate = prefs[BREAKDOWN_DATE_KEY] ?: ""
        return if (storedDate == todayString()) {
            prefs[BREAKDOWN_COUNT_KEY] ?: 0
        } else {
            0
        }
    }

    /**
     * Returns the number of free breakdowns remaining today.
     */
    suspend fun getRemainingBreakdowns(): Int = (FREE_BREAKDOWN_DAILY_LIMIT - getBreakdownCountToday()).coerceAtLeast(0)

    /**
     * Increments the daily breakdown counter. Returns the new count.
     */
    suspend fun incrementBreakdownCount(): Int {
        val today = todayString()
        var newCount = 0
        context.coachingDataStore.edit { prefs ->
            val storedDate = prefs[BREAKDOWN_DATE_KEY] ?: ""
            val currentCount = if (storedDate == today) {
                prefs[BREAKDOWN_COUNT_KEY] ?: 0
            } else {
                0
            }
            newCount = currentCount + 1
            prefs[BREAKDOWN_DATE_KEY] = today
            prefs[BREAKDOWN_COUNT_KEY] = newCount
        }
        return newCount
    }

    /**
     * Returns true if the free user has reached the daily breakdown limit.
     */
    suspend fun hasReachedBreakdownLimit(): Boolean = getBreakdownCountToday() >= FREE_BREAKDOWN_DAILY_LIMIT

    // endregion

    // region Last app open (for welcome-back detection)

    suspend fun getLastAppOpen(): Long = context.coachingDataStore.data
        .map { prefs ->
            prefs[LAST_APP_OPEN_KEY] ?: 0L
        }.first()

    suspend fun setLastAppOpen(timestamp: Long) {
        context.coachingDataStore.edit { prefs -> prefs[LAST_APP_OPEN_KEY] = timestamp }
    }

    /**
     * Returns the number of days since the user last opened the app,
     * or null if there is no prior record.
     */
    suspend fun getDaysSinceLastOpen(): Int? {
        val last = getLastAppOpen()
        if (last == 0L) return null
        val elapsed = System.currentTimeMillis() - last
        return java.util.concurrent.TimeUnit.MILLISECONDS
            .toDays(elapsed)
            .toInt()
    }

    // endregion

    // region Energy check-in

    /**
     * Returns the energy level selected today, or null if no check-in yet.
     */
    suspend fun getTodayEnergyLevel(): String? {
        val prefs = context.coachingDataStore.data.first()
        val storedDate = prefs[ENERGY_CHECKIN_DATE_KEY] ?: ""
        return if (storedDate == todayString()) {
            prefs[ENERGY_LEVEL_KEY]
        } else {
            null
        }
    }

    suspend fun setTodayEnergyLevel(level: String) {
        val today = todayString()
        context.coachingDataStore.edit { prefs ->
            prefs[ENERGY_CHECKIN_DATE_KEY] = today
            prefs[ENERGY_LEVEL_KEY] = level
        }
    }

    /**
     * Returns true if the energy check-in card should be shown today
     * (user hasn't done the check-in yet).
     */
    suspend fun shouldShowEnergyCheckIn(): Boolean = getTodayEnergyLevel() == null

    // endregion

    // region Welcome-back dismissal

    suspend fun isWelcomeBackDismissedToday(): Boolean {
        val prefs = context.coachingDataStore.data.first()
        return prefs[WELCOME_BACK_DISMISSED_DATE_KEY] == todayString()
    }

    suspend fun dismissWelcomeBack() {
        context.coachingDataStore.edit { prefs ->
            prefs[WELCOME_BACK_DISMISSED_DATE_KEY] = todayString()
        }
    }

    // endregion
}
