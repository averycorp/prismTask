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
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.reflectionDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "end_of_day_reflection_prefs")

/**
 * F4 Item 1 — End-of-day Reflection persistence.
 *
 * Stores per-day reflection entries plus the user-controlled "enable this
 * feature" toggle. Date-keyed entries let the user revisit yesterday's
 * note without overwriting. Storage shape: one entry per ISO date, all
 * entries concatenated into a single newline-delimited string.
 *
 * Anti-patterns enforced upstream by the screen — this store is pure
 * persistence:
 *  - never logs reflection text to Crashlytics or analytics (Principle 5);
 *  - default off so the feature is opt-in (Principle 7);
 *  - no judgment language is added by this layer (Principle 1) — copy
 *    lives in [com.averycorp.prismtask.ui.screens.reflection.ReflectionScreen].
 */
data class ReflectionEntry(val date: LocalDate, val text: String)

@Singleton
class ReflectionPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val ENABLED_KEY = booleanPreferencesKey("reflection_enabled")
        private val ENTRIES_KEY = stringPreferencesKey("reflection_entries")
        // ASCII Unit Separator (0x1F). Spelt as a unicode escape so future
        // file rewrites don't accidentally strip the raw byte (this file
        // shipped briefly with an empty char literal in PR #1506 because
        // of exactly that hazard).
        private const val FIELD_SEP = '\u001F'
        private const val LINE_SEP = '\n'

        fun encode(entries: List<ReflectionEntry>): String =
            entries.joinToString(LINE_SEP.toString()) { e -> "${e.date}$FIELD_SEP${e.text}" }

        fun decode(raw: String): List<ReflectionEntry> =
            if (raw.isBlank()) emptyList()
            else raw.split(LINE_SEP).mapNotNull { line ->
                val parts = line.split(FIELD_SEP, limit = 2)
                if (parts.size < 2) return@mapNotNull null
                val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull()
                    ?: return@mapNotNull null
                ReflectionEntry(date = date, text = parts[1])
            }
    }

    fun observeEnabled(): Flow<Boolean> =
        context.reflectionDataStore.data.map { prefs -> prefs[ENABLED_KEY] ?: false }

    fun observeEntries(): Flow<List<ReflectionEntry>> =
        context.reflectionDataStore.data.map { prefs -> decode(prefs[ENTRIES_KEY].orEmpty()) }

    fun observeEntryFor(date: LocalDate): Flow<ReflectionEntry?> =
        observeEntries().map { entries -> entries.firstOrNull { it.date == date } }

    suspend fun setEnabled(enabled: Boolean) {
        context.reflectionDataStore.edit { prefs -> prefs[ENABLED_KEY] = enabled }
    }

    suspend fun upsert(entry: ReflectionEntry) {
        context.reflectionDataStore.edit { prefs ->
            val existing = decode(prefs[ENTRIES_KEY].orEmpty())
            val updated = existing.filterNot { it.date == entry.date } + entry
            prefs[ENTRIES_KEY] = encode(updated.sortedByDescending { it.date })
        }
    }

    suspend fun delete(date: LocalDate) {
        context.reflectionDataStore.edit { prefs ->
            val existing = decode(prefs[ENTRIES_KEY].orEmpty())
            prefs[ENTRIES_KEY] = encode(existing.filterNot { it.date == date })
        }
    }

    suspend fun clearForTest() {
        context.reflectionDataStore.edit { it.clear() }
    }
}
