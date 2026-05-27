package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.dormancyDismissDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "dormancy_dismiss_prefs")

/**
 * Dormancy Re-Entry: local-only "Dismiss for today" flags for the
 * Ready-to-Resume section. Each entry is stored as `"<taskId>|<localDate>"`
 * so it auto-expires at the next local-midnight rollover — a stored date that
 * no longer equals today is simply ignored (and pruned on the next write).
 *
 * Local-only by design: never synced to Firestore (a dismissal is a per-device,
 * per-day UI preference, not canonical task state).
 */
@Singleton
class DormancyDismissPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val DISMISSED = stringSetPreferencesKey("dismissed_today")
        private const val SEP = "|"

        /** Task IDs whose stored dismissal date matches [today]. */
        fun dismissedIdsFor(entries: Set<String>, today: String): Set<Long> =
            entries.mapNotNullTo(mutableSetOf()) { entry ->
                val idx = entry.lastIndexOf(SEP)
                if (idx <= 0) return@mapNotNullTo null
                val date = entry.substring(idx + 1)
                if (date != today) return@mapNotNullTo null
                entry.substring(0, idx).toLongOrNull()
            }
    }

    /** Raw dismissal entries; combine with today's local date to resolve IDs. */
    val dismissedEntriesFlow: Flow<Set<String>> =
        context.dormancyDismissDataStore.data.map { it[DISMISSED] ?: emptySet() }

    /**
     * Marks [taskId] dismissed for [today] (a local date string from
     * `DayBoundary.currentLocalDateString`). Prunes any entries from earlier
     * days so the set never grows unbounded.
     */
    suspend fun dismissForToday(taskId: Long, today: String) {
        context.dormancyDismissDataStore.edit { prefs ->
            val current = prefs[DISMISSED] ?: emptySet()
            val pruned = current.filter { it.endsWith("$SEP$today") }.toMutableSet()
            pruned.add("$taskId$SEP$today")
            prefs[DISMISSED] = pruned
        }
    }
}
