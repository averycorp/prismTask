package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.customBrainModeDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "custom_brain_modes")

/**
 * User-defined Brain Modes (F4 Item 5 — additive customization). The
 * existing 3 hardcoded modes (Quick-Start / Calm / Focus & Release) stay
 * unchanged — this store layers ADDITIONAL named modes the user can
 * define for themselves, so the 31 existing dispatch call sites that
 * read [NdPreferences.adhdModeEnabled] / etc. are not touched.
 *
 * v1 semantics: custom modes hold a display name + description and a
 * single coarse hint flag (`gentleNotifications`). The hint flag is
 * surfaced informationally on the BrainMode settings screen so the user
 * can think of the mode as having a behavioral character; the actual
 * dispatch wiring beyond the 3 built-in modes is intentionally out of
 * scope for this bundle (preserves the operator's hard constraint that
 * the existing BrainModePage core dispatch is not refactored).
 *
 * Stored as a single newline-delimited string for simplicity — each
 * line is `namedescriptiongentleNotifications` (the unit
 * separator avoids field/line collisions with user input).
 */
data class CustomBrainMode(
    val name: String,
    val description: String,
    val gentleNotifications: Boolean
)

@Singleton
class CustomBrainModePreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val MODES_KEY = stringPreferencesKey("custom_brain_modes")
        private const val FIELD_SEP = ''
        private const val LINE_SEP = '\n'

        fun encode(modes: List<CustomBrainMode>): String =
            modes.joinToString(LINE_SEP.toString()) { m ->
                "${m.name}$FIELD_SEP${m.description}$FIELD_SEP${m.gentleNotifications}"
            }

        fun decode(raw: String): List<CustomBrainMode> =
            if (raw.isBlank()) emptyList()
            else raw.split(LINE_SEP).mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size < 2) return@mapNotNull null
                val name = parts[0].trim()
                if (name.isEmpty()) return@mapNotNull null
                CustomBrainMode(
                    name = name,
                    description = parts.getOrNull(1)?.trim().orEmpty(),
                    gentleNotifications = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false
                )
            }
    }

    fun observe(): Flow<List<CustomBrainMode>> =
        context.customBrainModeDataStore.data.map { prefs -> decode(prefs[MODES_KEY].orEmpty()) }

    suspend fun add(mode: CustomBrainMode) {
        context.customBrainModeDataStore.edit { prefs ->
            val current = decode(prefs[MODES_KEY].orEmpty())
            if (current.any { it.name.equals(mode.name, ignoreCase = true) }) return@edit
            prefs[MODES_KEY] = encode(current + mode)
        }
    }

    suspend fun remove(name: String) {
        context.customBrainModeDataStore.edit { prefs ->
            val current = decode(prefs[MODES_KEY].orEmpty())
            prefs[MODES_KEY] = encode(current.filterNot { it.name.equals(name, ignoreCase = true) })
        }
    }

    suspend fun clearForTest() {
        context.customBrainModeDataStore.edit { it.clear() }
    }
}
