package com.averycorp.prismtask.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the last-selected sort mode (and direction) for each screen that
 * supports sorting. Keys are stable strings defined in [ScreenKeys] plus the
 * dynamic per-project key returned by [projectKey].
 *
 * The stored sort-mode values match the lowercase tokens expected by callers:
 * "due_date", "priority", "urgency", "alphabetical", "date_created", "custom".
 *
 * The class takes a [DataStore] in its constructor so it can be unit-tested
 * without an Android [android.content.Context]; production wiring lives in
 * [com.averycorp.prismtask.di.PreferencesModule].
 */
class SortPreferences(private val dataStore: DataStore<Preferences>) {
    object ScreenKeys {
        const val TASK_LIST = "sort_task_list"
        const val TODAY = "sort_today"
        const val WEEK_VIEW = "sort_week_view"
        const val MONTH_VIEW = "sort_month_view"
        const val TIMELINE = "sort_timeline"
        const val ARCHIVE = "sort_archive"

        fun project(projectId: Long): String = "sort_project_$projectId"
    }

    object SortModes {
        const val DUE_DATE = "due_date"
        const val PRIORITY = "priority"
        const val URGENCY = "urgency"
        const val ALPHABETICAL = "alphabetical"
        const val DATE_CREATED = "date_created"
        const val CUSTOM = "custom"

        val ALL = setOf(DUE_DATE, PRIORITY, URGENCY, ALPHABETICAL, DATE_CREATED, CUSTOM)

        const val DEFAULT = DUE_DATE
    }

    /**
     * Returns the saved sort mode for the given screen key, or
     * [SortModes.DEFAULT] if the user has not yet made a selection.
     */
    suspend fun getSortMode(screenKey: String): String =
        observeSortMode(screenKey).first()

    /**
     * Returns the saved sort mode for [screenKey], or `null` if the user has
     * not yet made a selection. Lets callers distinguish "explicitly chose the
     * default" from "never set" so they can fall back to an app-wide default.
     */
    suspend fun getSortModeOrNull(screenKey: String): String? =
        dataStore.data.first()[stringPreferencesKey(screenKey)]

    /**
     * Persists [sortMode] for [screenKey]. Unknown values are stored as-is so
     * that per-screen custom modes keep working, but typical callers should
     * pass a value from [SortModes].
     */
    suspend fun setSortMode(screenKey: String, sortMode: String) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(screenKey)] = sortMode
            prefs[PREFS_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    /**
     * Reactive stream of the sort mode for [screenKey]. Emits
     * [SortModes.DEFAULT] until the user changes it.
     */
    fun observeSortMode(screenKey: String): Flow<String> =
        dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(screenKey)] ?: SortModes.DEFAULT
        }

    // --- Optional sort direction memory ---------------------------------

    /**
     * Returns the saved sort direction for the given screen, or the type-aware
     * default derived from [defaultDirectionFor] when unset.
     */
    suspend fun getSortDirection(screenKey: String, sortMode: String): SortDirection =
        observeSortDirection(screenKey, sortMode).first()

    /**
     * Persists [direction] for [screenKey].
     */
    suspend fun setSortDirection(screenKey: String, direction: SortDirection) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(directionKey(screenKey))] = direction.name
            prefs[PREFS_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    /**
     * Reactive stream of the sort direction for [screenKey]. Falls back to the
     * type-aware default from [defaultDirectionFor] when no value is stored.
     */
    fun observeSortDirection(screenKey: String, sortMode: String): Flow<SortDirection> =
        dataStore.data.map { prefs ->
            val raw = prefs[stringPreferencesKey(directionKey(screenKey))]
            raw?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() }
                ?: defaultDirectionFor(sortMode)
        }

    private fun directionKey(screenKey: String): String = "sort_direction_${screenKey.removePrefix("sort_")}"

    // --- Sync support -----------------------------------------------------------

    /** Emits Unit whenever any sort preference (or sync metadata) changes. */
    val flowOfChanges: Flow<Unit> = dataStore.data.map { }

    /** Returns all currently-stored sort keys as a flat map, excluding internal sync metadata. */
    suspend fun snapshot(): Map<String, Any> =
        dataStore.data.first().asMap()
            .mapKeys { (key, _) -> key.name }
            .filterKeys { it.startsWith("sort_") }

    suspend fun getPrefsUpdatedAt(): Long = dataStore.data.first()[PREFS_UPDATED_AT_KEY] ?: 0L

    suspend fun getLastSyncedAt(): Long = dataStore.data.first()[LAST_SYNCED_AT_KEY] ?: 0L

    /** Sets LAST_SYNCED_AT without bumping PREFS_UPDATED_AT (used after a successful push). */
    internal suspend fun setLastSyncedAt(timestamp: Long) {
        dataStore.edit { prefs -> prefs[LAST_SYNCED_AT_KEY] = timestamp }
    }

    /**
     * Writes a remote snapshot to DataStore atomically, setting both
     * PREFS_UPDATED_AT and LAST_SYNCED_AT to [updatedAt] so the push observer
     * sees them as equal and skips an unnecessary round-trip.
     */
    internal suspend fun applyRemoteSnapshot(sortKeys: Map<String, String>, updatedAt: Long) {
        dataStore.edit { prefs ->
            for ((key, value) in sortKeys) {
                prefs[stringPreferencesKey(key)] = value
            }
            prefs[PREFS_UPDATED_AT_KEY] = updatedAt
            prefs[LAST_SYNCED_AT_KEY] = updatedAt
        }
    }

    companion object {
        private val PREFS_UPDATED_AT_KEY = longPreferencesKey("prefs_updated_at")
        private val LAST_SYNCED_AT_KEY = longPreferencesKey("prefs_last_synced_at")

        /**
         * Default sort direction for a given sort mode. Descending for
         * ranking-style modes (priority, urgency, date_created), ascending for
         * ordered modes (due_date, alphabetical).
         */
        fun defaultDirectionFor(sortMode: String): SortDirection = when (sortMode) {
            SortModes.PRIORITY,
            SortModes.URGENCY,
            SortModes.DATE_CREATED -> SortDirection.DESCENDING
            else -> SortDirection.ASCENDING
        }
    }
}

enum class SortDirection { ASCENDING, DESCENDING }
