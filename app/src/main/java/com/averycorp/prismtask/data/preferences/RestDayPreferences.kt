package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.restDayPrefsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "rest_day_ui_prefs")

/**
 * UI-state for rest-day affordances. The actual rest-day marking lives
 * in [com.averycorp.prismtask.data.repository.RestDayRepository] on top
 * of [com.averycorp.prismtask.data.local.dao.RestDayDao] — the streak
 * core reads from there. This class stores only:
 *
 * - **Pause window** (`pauseFrom`..`pauseTo`) — remembered so the Today
 *   banner can say "Resting until <date>" without inferring contiguous
 *   ranges from the DAO. Set when the user starts a pause; cleared when
 *   the pause ends or is cancelled.
 * - **Low-energy filter** — when the user marks today as a rest day via
 *   the Today low-energy toggle, this boolean controls whether Today
 *   also hides non-EASY tasks for the rest of the day.
 */
data class RestDayUiSnapshot(
    val pauseFrom: LocalDate?,
    val pauseTo: LocalDate?,
    val lowEnergyFilterEnabled: Boolean
) {
    fun isPaused(on: LocalDate): Boolean {
        val from = pauseFrom ?: return false
        val to = pauseTo ?: return false
        return !on.isBefore(from) && !on.isAfter(to)
    }
}

@Singleton
class RestDayPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val PAUSE_FROM_KEY = stringPreferencesKey("pause_from")
        private val PAUSE_TO_KEY = stringPreferencesKey("pause_to")
        private val LOW_ENERGY_FILTER_KEY = booleanPreferencesKey("low_energy_filter_enabled")
    }

    fun observe(): Flow<RestDayUiSnapshot> = combine(
        context.restDayPrefsDataStore.data.map { prefs ->
            prefs[PAUSE_FROM_KEY]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        },
        context.restDayPrefsDataStore.data.map { prefs ->
            prefs[PAUSE_TO_KEY]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        },
        context.restDayPrefsDataStore.data.map { prefs -> prefs[LOW_ENERGY_FILTER_KEY] ?: false }
    ) { from, to, filter ->
        RestDayUiSnapshot(pauseFrom = from, pauseTo = to, lowEnergyFilterEnabled = filter)
    }

    /** Whether the Today low-energy filter (EASY tasks only) is currently active. Default off (Principle 7). */
    fun observeLowEnergyFilterEnabled(): Flow<Boolean> =
        context.restDayPrefsDataStore.data.map { prefs -> prefs[LOW_ENERGY_FILTER_KEY] ?: false }

    suspend fun setPause(from: LocalDate?, to: LocalDate?) {
        context.restDayPrefsDataStore.edit { prefs ->
            if (from == null) prefs.remove(PAUSE_FROM_KEY) else prefs[PAUSE_FROM_KEY] = from.toString()
            if (to == null) prefs.remove(PAUSE_TO_KEY) else prefs[PAUSE_TO_KEY] = to.toString()
        }
    }

    suspend fun clearPause() = setPause(null, null)

    suspend fun setLowEnergyFilterEnabled(enabled: Boolean) {
        context.restDayPrefsDataStore.edit { prefs -> prefs[LOW_ENERGY_FILTER_KEY] = enabled }
    }

    suspend fun clearForTest() {
        context.restDayPrefsDataStore.edit { it.clear() }
    }
}
