package com.averycorp.prismtask.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed persistence for Neurodivergent Mode preferences.
 *
 * Takes a [DataStore] in its constructor so it can be unit-tested without an Android
 * Context; production wiring lives in [com.averycorp.prismtask.di.PreferencesModule].
 *
 * ## Mode activation logic
 * - When [setAdhdMode] is called with `true`: all ADHD sub-settings flip ON.
 * - When [setCalmMode] is called with `true`: all Calm sub-settings flip ON.
 * - When [setFocusReleaseMode] is called with `true`: all F&R sub-settings flip ON.
 * - When a mode is toggled OFF: all its sub-settings flip OFF. The three modes have
 *   zero overlap so toggling one off never affects the others.
 * - Individual sub-setting changes do NOT auto-disable the parent mode toggle.
 */
class NdPreferencesDataStore(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        // Top-level mode toggles
        val KEY_ADHD_MODE = booleanPreferencesKey("nd_adhd_mode_enabled")
        val KEY_CALM_MODE = booleanPreferencesKey("nd_calm_mode_enabled")

        // Calm Mode sub-settings
        val KEY_REDUCE_ANIMATIONS = booleanPreferencesKey("nd_reduce_animations")
        val KEY_MUTED_COLOR_PALETTE = booleanPreferencesKey("nd_muted_color_palette")
        val KEY_QUIET_MODE = booleanPreferencesKey("nd_quiet_mode")
        val KEY_REDUCE_HAPTICS = booleanPreferencesKey("nd_reduce_haptics")
        val KEY_SOFT_CONTRAST = booleanPreferencesKey("nd_soft_contrast")

        // ADHD Mode sub-settings
        val KEY_CHECK_IN_INTERVAL = intPreferencesKey("nd_check_in_interval_minutes")
        val KEY_COMPLETION_ANIMATIONS = booleanPreferencesKey("nd_completion_animations")
        val KEY_STREAK_CELEBRATIONS = booleanPreferencesKey("nd_streak_celebrations")
        val KEY_SHOW_PROGRESS_BARS = booleanPreferencesKey("nd_show_progress_bars")
        // The `nd_forgiveness_streaks` boolean key used to live here and was
        // removed in the mental-health-first audit § R6. Any existing
        // installs carry an orphaned value at that key; DataStore reads it
        // as part of the proto but no codepath consumes it, so it has no
        // effect. Forgiveness-first streak behavior is owned by the global
        // `UserPreferencesDataStore.ForgivenessPrefs.enabled` (default true).

        // Focus & Release Mode toggle
        val KEY_FOCUS_RELEASE_MODE = booleanPreferencesKey("nd_focus_release_mode_enabled")

        // Good Enough Timers
        val KEY_GOOD_ENOUGH_TIMERS = booleanPreferencesKey("nd_good_enough_timers_enabled")
        val KEY_DEFAULT_GOOD_ENOUGH_MINUTES = intPreferencesKey("nd_default_good_enough_minutes")
        val KEY_GOOD_ENOUGH_ESCALATION = stringPreferencesKey("nd_good_enough_escalation")

        // Anti-Rework Guards
        val KEY_ANTI_REWORK = booleanPreferencesKey("nd_anti_rework_enabled")
        val KEY_SOFT_WARNING = booleanPreferencesKey("nd_soft_warning_enabled")
        val KEY_COOLING_OFF = booleanPreferencesKey("nd_cooling_off_enabled")
        val KEY_COOLING_OFF_MINUTES = intPreferencesKey("nd_cooling_off_minutes")
        val KEY_REVISION_COUNTER = booleanPreferencesKey("nd_revision_counter_enabled")
        val KEY_MAX_REVISIONS = intPreferencesKey("nd_max_revisions")

        // Ship-It Celebrations
        val KEY_SHIP_IT_CELEBRATIONS = booleanPreferencesKey("nd_ship_it_celebrations_enabled")
        val KEY_CELEBRATION_INTENSITY = stringPreferencesKey("nd_celebration_intensity")
    }

    // region Flow ---------------------------------------------------------------

    // Product framing (operator decision 2026-05-14): for an ADHD-focused
    // app, the three ND modes default ON for first-time users. "Presume
    // neurodivergent baseline; let users opt out." Cascading sub-settings
    // mirror the `setAdhdMode(true)` / `setCalmMode(true)` cascade so a
    // fresh install reads self-consistent state. Explicit-false stored by
    // a returning user is preserved (the `?:` fallback only fires when the
    // key is absent).
    val ndPreferencesFlow: Flow<NdPreferences> = dataStore.data.map { prefs ->
        NdPreferences(
            adhdModeEnabled = prefs[KEY_ADHD_MODE] ?: true,
            calmModeEnabled = prefs[KEY_CALM_MODE] ?: true,
            focusReleaseModeEnabled = prefs[KEY_FOCUS_RELEASE_MODE] ?: true,
            reduceAnimations = prefs[KEY_REDUCE_ANIMATIONS] ?: true,
            mutedColorPalette = prefs[KEY_MUTED_COLOR_PALETTE] ?: true,
            quietMode = prefs[KEY_QUIET_MODE] ?: true,
            reduceHaptics = prefs[KEY_REDUCE_HAPTICS] ?: true,
            softContrast = prefs[KEY_SOFT_CONTRAST] ?: true,
            checkInIntervalMinutes = (prefs[KEY_CHECK_IN_INTERVAL] ?: 25).coerceIn(10, 60),
            completionAnimations = prefs[KEY_COMPLETION_ANIMATIONS] ?: true,
            streakCelebrations = prefs[KEY_STREAK_CELEBRATIONS] ?: true,
            showProgressBars = prefs[KEY_SHOW_PROGRESS_BARS] ?: true,
            goodEnoughTimersEnabled = prefs[KEY_GOOD_ENOUGH_TIMERS] ?: true,
            defaultGoodEnoughMinutes = (prefs[KEY_DEFAULT_GOOD_ENOUGH_MINUTES] ?: 30).coerceIn(5, 120),
            goodEnoughEscalation = prefs[KEY_GOOD_ENOUGH_ESCALATION]
                ?.let { runCatching { GoodEnoughEscalation.valueOf(it) }.getOrNull() }
                ?: GoodEnoughEscalation.NUDGE,
            antiReworkEnabled = prefs[KEY_ANTI_REWORK] ?: true,
            softWarningEnabled = prefs[KEY_SOFT_WARNING] ?: true,
            coolingOffEnabled = prefs[KEY_COOLING_OFF] ?: false,
            coolingOffMinutes = (prefs[KEY_COOLING_OFF_MINUTES] ?: 30).coerceIn(15, 120),
            revisionCounterEnabled = prefs[KEY_REVISION_COUNTER] ?: false,
            maxRevisions = (prefs[KEY_MAX_REVISIONS] ?: 3).coerceIn(1, 10),
            shipItCelebrationsEnabled = prefs[KEY_SHIP_IT_CELEBRATIONS] ?: true,
            celebrationIntensity = prefs[KEY_CELEBRATION_INTENSITY]
                ?.let { runCatching { CelebrationIntensity.valueOf(it) }.getOrNull() }
                ?: CelebrationIntensity.MEDIUM
        )
    }

    // endregion

    // region Mode toggles -------------------------------------------------------

    /**
     * Enables or disables ADHD Mode. When enabled, flips ALL ADHD sub-settings to
     * true. When disabled, flips ALL ADHD sub-settings to false. Does not affect
     * Calm Mode settings.
     */
    suspend fun setAdhdMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ADHD_MODE] = enabled
            prefs[KEY_COMPLETION_ANIMATIONS] = enabled
            prefs[KEY_STREAK_CELEBRATIONS] = enabled
            prefs[KEY_SHOW_PROGRESS_BARS] = enabled
            // (forgiveness-first streak behavior is owned by the global
            // ForgivenessPrefs.enabled; the duplicate ND field was removed in
            // the mental-health-first audit § R6.)
        }
    }

    /**
     * Enables or disables Calm Mode. When enabled, flips ALL Calm sub-settings to
     * true. When disabled, flips ALL Calm sub-settings to false. Does not affect
     * ADHD Mode or Focus & Release Mode settings.
     */
    suspend fun setCalmMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_CALM_MODE] = enabled
            prefs[KEY_REDUCE_ANIMATIONS] = enabled
            prefs[KEY_MUTED_COLOR_PALETTE] = enabled
            prefs[KEY_QUIET_MODE] = enabled
            prefs[KEY_REDUCE_HAPTICS] = enabled
            prefs[KEY_SOFT_CONTRAST] = enabled
        }
    }

    /**
     * Enables or disables Focus & Release Mode. When enabled, flips ALL F&R
     * sub-settings to their default-on values. When disabled, flips all F&R
     * sub-settings off. Does not affect ADHD Mode or Calm Mode settings.
     */
    suspend fun setFocusReleaseMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_FOCUS_RELEASE_MODE] = enabled
            prefs[KEY_GOOD_ENOUGH_TIMERS] = enabled
            prefs[KEY_ANTI_REWORK] = enabled
            prefs[KEY_SOFT_WARNING] = enabled
            prefs[KEY_COOLING_OFF] = false // off by default even when F&R enabled
            prefs[KEY_REVISION_COUNTER] = false // off by default even when F&R enabled
            prefs[KEY_SHIP_IT_CELEBRATIONS] = enabled
        }
    }

    // endregion

    // region Individual sub-setting setters -------------------------------------

    suspend fun setReduceAnimations(enabled: Boolean) {
        dataStore.edit { it[KEY_REDUCE_ANIMATIONS] = enabled }
    }

    suspend fun setMutedColorPalette(enabled: Boolean) {
        dataStore.edit { it[KEY_MUTED_COLOR_PALETTE] = enabled }
    }

    suspend fun setQuietMode(enabled: Boolean) {
        dataStore.edit { it[KEY_QUIET_MODE] = enabled }
    }

    suspend fun setReduceHaptics(enabled: Boolean) {
        dataStore.edit { it[KEY_REDUCE_HAPTICS] = enabled }
    }

    suspend fun setSoftContrast(enabled: Boolean) {
        dataStore.edit { it[KEY_SOFT_CONTRAST] = enabled }
    }

    suspend fun setCheckInIntervalMinutes(minutes: Int) {
        dataStore.edit { it[KEY_CHECK_IN_INTERVAL] = minutes.coerceIn(10, 60) }
    }

    suspend fun setCompletionAnimations(enabled: Boolean) {
        dataStore.edit { it[KEY_COMPLETION_ANIMATIONS] = enabled }
    }

    suspend fun setStreakCelebrations(enabled: Boolean) {
        dataStore.edit { it[KEY_STREAK_CELEBRATIONS] = enabled }
    }

    suspend fun setShowProgressBars(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_PROGRESS_BARS] = enabled }
    }

    // Focus & Release Mode individual sub-setting setters

    suspend fun setGoodEnoughTimersEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_GOOD_ENOUGH_TIMERS] = enabled }
    }

    suspend fun setDefaultGoodEnoughMinutes(minutes: Int) {
        dataStore.edit { it[KEY_DEFAULT_GOOD_ENOUGH_MINUTES] = minutes.coerceIn(5, 120) }
    }

    suspend fun setGoodEnoughEscalation(escalation: GoodEnoughEscalation) {
        dataStore.edit { it[KEY_GOOD_ENOUGH_ESCALATION] = escalation.name }
    }

    suspend fun setAntiReworkEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ANTI_REWORK] = enabled }
    }

    suspend fun setSoftWarningEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SOFT_WARNING] = enabled }
    }

    suspend fun setCoolingOffEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_COOLING_OFF] = enabled }
    }

    suspend fun setCoolingOffMinutes(minutes: Int) {
        dataStore.edit { it[KEY_COOLING_OFF_MINUTES] = minutes.coerceIn(15, 120) }
    }

    suspend fun setRevisionCounterEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REVISION_COUNTER] = enabled }
    }

    suspend fun setMaxRevisions(max: Int) {
        dataStore.edit { it[KEY_MAX_REVISIONS] = max.coerceIn(1, 10) }
    }

    suspend fun setShipItCelebrationsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SHIP_IT_CELEBRATIONS] = enabled }
    }

    suspend fun setCelebrationIntensity(intensity: CelebrationIntensity) {
        dataStore.edit { it[KEY_CELEBRATION_INTENSITY] = intensity.name }
    }

    // endregion

    // region Generic setter -----------------------------------------------------

    /**
     * Updates a single ND preference by key name. Intended for use by the Settings
     * UI where toggle keys are passed dynamically.
     *
     * Uses safe `as?` casts: a mistyped value (e.g. from settings-import JSON
     * where an Int arrives as a Double) is logged and silently skipped rather
     * than throwing ClassCastException.
     *
     * @throws IllegalArgumentException if [key] is not a recognized ND preference key.
     */
    suspend fun updateNdPreference(key: String, value: Any) {
        fun bool(): Boolean? = value as? Boolean
        fun int(): Int? = (value as? Int) ?: (value as? Number)?.toInt()
        fun str(): String? = value as? String
        when (key) {
            "adhd_mode_enabled" -> bool()?.let { setAdhdMode(it) }
            "calm_mode_enabled" -> bool()?.let { setCalmMode(it) }
            "focus_release_mode_enabled" -> bool()?.let { setFocusReleaseMode(it) }
            "reduce_animations" -> bool()?.let { setReduceAnimations(it) }
            "muted_color_palette" -> bool()?.let { setMutedColorPalette(it) }
            "quiet_mode" -> bool()?.let { setQuietMode(it) }
            "reduce_haptics" -> bool()?.let { setReduceHaptics(it) }
            "soft_contrast" -> bool()?.let { setSoftContrast(it) }
            "check_in_interval_minutes" -> int()?.let { setCheckInIntervalMinutes(it) }
            "completion_animations" -> bool()?.let { setCompletionAnimations(it) }
            "streak_celebrations" -> bool()?.let { setStreakCelebrations(it) }
            "show_progress_bars" -> bool()?.let { setShowProgressBars(it) }
            // Legacy key from pre-R6 backups. The underlying field was removed
            // in the mental-health-first audit § R6 (it duplicated the global
            // ForgivenessPrefs.enabled). Accepted as a no-op so old config
            // restores don't throw IllegalArgumentException.
            "forgiveness_streaks" -> { /* no-op: removed in audit § R6 */ }
            "good_enough_timers_enabled" -> bool()?.let { setGoodEnoughTimersEnabled(it) }
            "default_good_enough_minutes" -> int()?.let { setDefaultGoodEnoughMinutes(it) }
            "good_enough_escalation" -> str()?.let { name ->
                runCatching { GoodEnoughEscalation.valueOf(name) }
                    .getOrNull()
                    ?.let { setGoodEnoughEscalation(it) }
            }
            "anti_rework_enabled" -> bool()?.let { setAntiReworkEnabled(it) }
            "soft_warning_enabled" -> bool()?.let { setSoftWarningEnabled(it) }
            "cooling_off_enabled" -> bool()?.let { setCoolingOffEnabled(it) }
            "cooling_off_minutes" -> int()?.let { setCoolingOffMinutes(it) }
            "revision_counter_enabled" -> bool()?.let { setRevisionCounterEnabled(it) }
            "max_revisions" -> int()?.let { setMaxRevisions(it) }
            "ship_it_celebrations_enabled" -> bool()?.let { setShipItCelebrationsEnabled(it) }
            "celebration_intensity" -> str()?.let { name ->
                runCatching { CelebrationIntensity.valueOf(name) }
                    .getOrNull()
                    ?.let { setCelebrationIntensity(it) }
            }
            else -> throw IllegalArgumentException("Unknown ND preference key: $key")
        }
    }

    // endregion
}
