package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.dailyEssentialsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "daily_essentials_prefs")

/**
 * Pointers that identify which existing habits power the Housework and
 * Schoolwork cards on the Today screen's Daily Essentials section. Stored as
 * habit IDs so no new tables or columns are required.
 */
@Singleton
class DailyEssentialsPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val HOUSEWORK_HABIT_ID = longPreferencesKey("housework_habit_id")
        private val SCHOOLWORK_HABIT_ID = longPreferencesKey("schoolwork_habit_id")
        private val HAS_SEEN_HINT = booleanPreferencesKey("has_seen_daily_essentials_hint")
    }

    val houseworkHabitId: Flow<Long?> = context.dailyEssentialsDataStore.data.map { prefs ->
        prefs[HOUSEWORK_HABIT_ID]
    }

    val schoolworkHabitId: Flow<Long?> = context.dailyEssentialsDataStore.data.map { prefs ->
        prefs[SCHOOLWORK_HABIT_ID]
    }

    val hasSeenHint: Flow<Boolean> = context.dailyEssentialsDataStore.data.map { prefs ->
        prefs[HAS_SEEN_HINT] ?: false
    }

    suspend fun setHouseworkHabit(id: Long?) {
        context.dailyEssentialsDataStore.edit { prefs ->
            if (id == null) prefs.remove(HOUSEWORK_HABIT_ID) else prefs[HOUSEWORK_HABIT_ID] = id
        }
    }

    suspend fun setSchoolworkHabit(id: Long?) {
        context.dailyEssentialsDataStore.edit { prefs ->
            if (id == null) prefs.remove(SCHOOLWORK_HABIT_ID) else prefs[SCHOOLWORK_HABIT_ID] = id
        }
    }

    suspend fun markHintSeen() {
        context.dailyEssentialsDataStore.edit { prefs ->
            prefs[HAS_SEEN_HINT] = true
        }
    }
}
