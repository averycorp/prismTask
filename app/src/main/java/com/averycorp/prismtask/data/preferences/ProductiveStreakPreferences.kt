package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.productiveStreakDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "productive_streak_prefs")

/**
 * Snapshot of the user's productive-day streak — current run, longest run,
 * and the last date the streak ticked. A "productive day" is any day the
 * user's productivity score is at or above [PRODUCTIVE_DAY_SCORE_THRESHOLD]
 * (60 by default — matches the spec). The streak is updated on the SoD
 * boundary by piggybacking on the existing DailyResetWorker rather than
 * scheduling a new alarm.
 *
 * Forgiveness-first: when a streak is broken, the broken-streak
 * notification copy lives in [BROKEN_STREAK_NOTIFICATION_BODY] and is
 * intentionally empathetic ("Take care of yourself today — start fresh
 * tomorrow"), not punishing.
 */
data class ProductiveStreakSnapshot(val currentDays: Int, val longestDays: Int, val lastProductiveDate: LocalDate?) {
    val hasAnyHistory: Boolean get() = lastProductiveDate != null || currentDays > 0 || longestDays > 0
}

@Singleton
class ProductiveStreakPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val PRODUCTIVE_DAY_SCORE_THRESHOLD = 60
        const val BROKEN_STREAK_NOTIFICATION_TITLE = "Streak Reset"
        const val BROKEN_STREAK_NOTIFICATION_BODY =
            "Take care of yourself today — start fresh tomorrow."

        private val CURRENT_KEY = intPreferencesKey("current_productive_streak")
        private val LONGEST_KEY = intPreferencesKey("longest_productive_streak")
        private val LAST_DATE_KEY = stringPreferencesKey("last_productive_date")
    }

    fun observe(): Flow<ProductiveStreakSnapshot> = combine(
        context.productiveStreakDataStore.data.map { prefs -> prefs[CURRENT_KEY] ?: 0 },
        context.productiveStreakDataStore.data.map { prefs -> prefs[LONGEST_KEY] ?: 0 },
        context.productiveStreakDataStore.data.map { prefs ->
            prefs[LAST_DATE_KEY]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        }
    ) { current, longest, last ->
        ProductiveStreakSnapshot(currentDays = current, longestDays = longest, lastProductiveDate = last)
    }

    /**
     * Atomically advance the streak when [day] qualified as productive
     * (score ≥ [PRODUCTIVE_DAY_SCORE_THRESHOLD]). If [day] is the day
     * after [lastProductiveDate], the run grows by one. If it's the same
     * day, the snapshot is unchanged (idempotent — safe to call from a
     * worker that may run twice on the SoD boundary).
     */
    suspend fun recordProductiveDay(day: LocalDate) {
        context.productiveStreakDataStore.edit { prefs ->
            val current = prefs[CURRENT_KEY] ?: 0
            val longest = prefs[LONGEST_KEY] ?: 0
            val lastIso = prefs[LAST_DATE_KEY]
            val last = lastIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            val nextCurrent = when {
                last == null -> 1
                last == day -> current.coerceAtLeast(1)
                last.plusDays(1) == day -> current + 1
                else -> 1 // gap — restart the run
            }

            prefs[CURRENT_KEY] = nextCurrent
            prefs[LONGEST_KEY] = maxOf(longest, nextCurrent)
            prefs[LAST_DATE_KEY] = day.toString()
        }
    }

    /**
     * Reset the current run when [day] failed the productive-day bar.
     * Keeps the longest-streak record intact. Returns the broken-streak
     * length (zero if there was nothing to break), which callers use to
     * decide whether to fire the empathetic notification.
     */
    suspend fun resetCurrentStreakIfBroken(day: LocalDate): Int {
        var broken = 0
        context.productiveStreakDataStore.edit { prefs ->
            val current = prefs[CURRENT_KEY] ?: 0
            val lastIso = prefs[LAST_DATE_KEY]
            val last = lastIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // Only break the streak if the last productive day is strictly
            // before [day] AND the user had an active run. Same-day calls
            // are no-ops (could happen if the user dips below threshold
            // mid-day after already qualifying earlier).
            if (last != null && last.isBefore(day) && current > 0) {
                broken = current
                prefs[CURRENT_KEY] = 0
            }
        }
        return broken
    }

    suspend fun clearForTest() {
        context.productiveStreakDataStore.edit { it.clear() }
    }
}
