package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.customBrainModeDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "custom_brain_modes")

/**
 * User-defined Brain Modes. F4 Item 5 — v2 generic dispatch.
 *
 * v1 (PR #1506) stored a display name + description + one informational
 * "gentle notifications" hint flag, and explicitly deferred any dispatch
 * wiring beyond informational display. v2 (this PR) lifts that deferral:
 * each custom mode carries optional per-field overrides for the
 * [NdPreferences] toggle surface, and a separately tracked "active"
 * pointer designates one mode at a time whose overrides are layered on
 * top of the base ND preferences via [BrainModeResolver]. Dispatch sites
 * that previously read `ndPreferencesFlow` directly now read the
 * resolver-driven effective flow when the user's behavior should reflect
 * the active mode (`BatchPreviewViewModel.simplifiedUi`,
 * `WidgetDataProvider.getQuietMode`). Settings, export, and onboarding
 * intentionally stay on the base flow — the user edits and inspects
 * what's actually persisted on the data class, not the resolved view.
 *
 * Each override field is `Boolean?` where `null` means "inherit from
 * base NdPreferences" and a non-null value means "force this state when
 * the mode is active." This makes a mode a sparse overlay rather than a
 * full replacement, so users can adopt a mode for a single concern (e.g.
 * quiet mode while traveling) without disturbing unrelated settings.
 *
 * Persistence uses a positional FIELD_SEP-delimited line format for
 * forward compatibility — fields appended in later versions decode as
 * default values on older builds; old single-line records (name + desc
 * + gentleNotifications) decode cleanly into the v2 shape with all
 * overrides null.
 */
data class CustomBrainMode(
    val name: String,
    val description: String,
    val gentleNotifications: Boolean = false,
    // v2 overlay — null means "inherit base"
    val adhdModeEnabledOverride: Boolean? = null,
    val calmModeEnabledOverride: Boolean? = null,
    val focusReleaseModeEnabledOverride: Boolean? = null,
    val reduceAnimationsOverride: Boolean? = null,
    val mutedColorPaletteOverride: Boolean? = null,
    val quietModeOverride: Boolean? = null,
    val reduceHapticsOverride: Boolean? = null,
    val softContrastOverride: Boolean? = null,
    val completionAnimationsOverride: Boolean? = null,
    val streakCelebrationsOverride: Boolean? = null,
    val showProgressBarsOverride: Boolean? = null,
    val goodEnoughTimersEnabledOverride: Boolean? = null,
    val antiReworkEnabledOverride: Boolean? = null,
    val shipItCelebrationsEnabledOverride: Boolean? = null
)

@Singleton
class CustomBrainModePreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val MODES_KEY = stringPreferencesKey("custom_brain_modes")
        private val ACTIVE_KEY = stringPreferencesKey("custom_brain_mode_active")

        // ASCII Unit Separator (0x1F). Spelt as a unicode escape so future
        // file rewrites don't accidentally strip the raw byte from the
        // source.
        private const val FIELD_SEP = '\u001F'
        private const val LINE_SEP = '\n'

        // Positional field indices. Appending only — never reorder or remove
        // (older installs read by position).
        private const val IDX_NAME = 0
        private const val IDX_DESCRIPTION = 1
        private const val IDX_GENTLE = 2
        private const val IDX_ADHD = 3
        private const val IDX_CALM = 4
        private const val IDX_FOCUS_RELEASE = 5
        private const val IDX_REDUCE_ANIMATIONS = 6
        private const val IDX_MUTED_COLORS = 7
        private const val IDX_QUIET_MODE = 8
        private const val IDX_REDUCE_HAPTICS = 9
        private const val IDX_SOFT_CONTRAST = 10
        private const val IDX_COMPLETION_ANIMATIONS = 11
        private const val IDX_STREAK_CELEBRATIONS = 12
        private const val IDX_SHOW_PROGRESS_BARS = 13
        private const val IDX_GOOD_ENOUGH = 14
        private const val IDX_ANTI_REWORK = 15
        private const val IDX_SHIP_IT = 16

        // Encoded marker for "no override." Empty string would collide with
        // user-blank fields on the front of the line; a sentinel keeps the
        // encoding unambiguous even when adjacent fields are blank.
        private const val NULL_MARKER = "~"

        private fun encodeNullableBool(value: Boolean?): String =
            value?.toString() ?: NULL_MARKER

        private fun decodeNullableBool(raw: String?): Boolean? = when (raw) {
            null, "", NULL_MARKER -> null
            else -> raw.toBooleanStrictOrNull()
        }

        fun encode(modes: List<CustomBrainMode>): String =
            modes.joinToString(LINE_SEP.toString()) { m ->
                listOf(
                    m.name,
                    m.description,
                    m.gentleNotifications.toString(),
                    encodeNullableBool(m.adhdModeEnabledOverride),
                    encodeNullableBool(m.calmModeEnabledOverride),
                    encodeNullableBool(m.focusReleaseModeEnabledOverride),
                    encodeNullableBool(m.reduceAnimationsOverride),
                    encodeNullableBool(m.mutedColorPaletteOverride),
                    encodeNullableBool(m.quietModeOverride),
                    encodeNullableBool(m.reduceHapticsOverride),
                    encodeNullableBool(m.softContrastOverride),
                    encodeNullableBool(m.completionAnimationsOverride),
                    encodeNullableBool(m.streakCelebrationsOverride),
                    encodeNullableBool(m.showProgressBarsOverride),
                    encodeNullableBool(m.goodEnoughTimersEnabledOverride),
                    encodeNullableBool(m.antiReworkEnabledOverride),
                    encodeNullableBool(m.shipItCelebrationsEnabledOverride)
                ).joinToString(FIELD_SEP.toString())
            }

        fun decode(raw: String): List<CustomBrainMode> =
            if (raw.isBlank()) {
                emptyList()
            } else {
                raw.split(LINE_SEP).mapNotNull { line ->
                    val parts = line.split(FIELD_SEP)
                    if (parts.size < 2) return@mapNotNull null
                    val name = parts.getOrNull(IDX_NAME)?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    CustomBrainMode(
                        name = name,
                        description = parts.getOrNull(IDX_DESCRIPTION)?.trim().orEmpty(),
                        gentleNotifications = parts.getOrNull(IDX_GENTLE)?.toBooleanStrictOrNull() ?: false,
                        adhdModeEnabledOverride = decodeNullableBool(parts.getOrNull(IDX_ADHD)),
                        calmModeEnabledOverride = decodeNullableBool(parts.getOrNull(IDX_CALM)),
                        focusReleaseModeEnabledOverride = decodeNullableBool(parts.getOrNull(IDX_FOCUS_RELEASE)),
                        reduceAnimationsOverride = decodeNullableBool(parts.getOrNull(IDX_REDUCE_ANIMATIONS)),
                        mutedColorPaletteOverride = decodeNullableBool(parts.getOrNull(IDX_MUTED_COLORS)),
                        quietModeOverride = decodeNullableBool(parts.getOrNull(IDX_QUIET_MODE)),
                        reduceHapticsOverride = decodeNullableBool(parts.getOrNull(IDX_REDUCE_HAPTICS)),
                        softContrastOverride = decodeNullableBool(parts.getOrNull(IDX_SOFT_CONTRAST)),
                        completionAnimationsOverride = decodeNullableBool(parts.getOrNull(IDX_COMPLETION_ANIMATIONS)),
                        streakCelebrationsOverride = decodeNullableBool(parts.getOrNull(IDX_STREAK_CELEBRATIONS)),
                        showProgressBarsOverride = decodeNullableBool(parts.getOrNull(IDX_SHOW_PROGRESS_BARS)),
                        goodEnoughTimersEnabledOverride = decodeNullableBool(parts.getOrNull(IDX_GOOD_ENOUGH)),
                        antiReworkEnabledOverride = decodeNullableBool(parts.getOrNull(IDX_ANTI_REWORK)),
                        shipItCelebrationsEnabledOverride = decodeNullableBool(parts.getOrNull(IDX_SHIP_IT))
                    )
                }
            }
    }

    fun observe(): Flow<List<CustomBrainMode>> =
        context.customBrainModeDataStore.data.map { prefs -> decode(prefs[MODES_KEY].orEmpty()) }

    /**
     * Name of the currently active custom mode, or `null` when no mode is
     * active (built-in [NdPreferences] dispatch in effect).
     */
    fun observeActiveName(): Flow<String?> =
        context.customBrainModeDataStore.data.map { prefs -> prefs[ACTIVE_KEY] }

    /**
     * Resolves the active custom mode to its full [CustomBrainMode] object,
     * or `null` if no mode is active or the persisted active name no longer
     * matches any defined mode (e.g. the user removed it without clearing
     * the active pointer — defensive null over surfacing a stale ghost).
     */
    fun observeActive(): Flow<CustomBrainMode?> =
        combine(observe(), observeActiveName()) { modes, activeName ->
            if (activeName.isNullOrBlank()) {
                null
            } else {
                modes.firstOrNull { it.name.equals(activeName, ignoreCase = true) }
            }
        }

    suspend fun add(mode: CustomBrainMode) {
        context.customBrainModeDataStore.edit { prefs ->
            val current = decode(prefs[MODES_KEY].orEmpty())
            if (current.any { it.name.equals(mode.name, ignoreCase = true) }) return@edit
            prefs[MODES_KEY] = encode(current + mode)
        }
    }

    suspend fun update(mode: CustomBrainMode) {
        context.customBrainModeDataStore.edit { prefs ->
            val current = decode(prefs[MODES_KEY].orEmpty())
            val replaced = current.map { m ->
                if (m.name.equals(mode.name, ignoreCase = true)) mode else m
            }
            prefs[MODES_KEY] = encode(replaced)
        }
    }

    suspend fun remove(name: String) {
        context.customBrainModeDataStore.edit { prefs ->
            val current = decode(prefs[MODES_KEY].orEmpty())
            prefs[MODES_KEY] = encode(current.filterNot { it.name.equals(name, ignoreCase = true) })
            // Clear the active pointer if we just removed the active mode —
            // otherwise observeActive() silently resolves to null forever
            // and the user has no UI hint that their active selection is
            // gone.
            val active = prefs[ACTIVE_KEY]
            if (active != null && active.equals(name, ignoreCase = true)) {
                prefs.remove(ACTIVE_KEY)
            }
        }
    }

    suspend fun setActive(name: String) {
        context.customBrainModeDataStore.edit { prefs ->
            val current = decode(prefs[MODES_KEY].orEmpty())
            // Only persist the active pointer if the named mode actually
            // exists — calling setActive("unknown") shouldn't leave a
            // dangling pointer that observeActive() silently swallows.
            val match = current.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (match != null) {
                prefs[ACTIVE_KEY] = match.name
            }
        }
    }

    suspend fun clearActive() {
        context.customBrainModeDataStore.edit { it.remove(ACTIVE_KEY) }
    }

    suspend fun clearForTest() {
        context.customBrainModeDataStore.edit { it.clear() }
    }
}
