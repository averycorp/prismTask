package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.themePrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

// NOTE: Any new key added here that belongs in the sync payload must also be added to
//  ThemePreferencesSyncService.pushNow() (push) and applyRemoteSnapshot() (pull).
//  Full per-user DataStore scoping + unified AppearanceSettingsSyncService deferred to Option C.
@Singleton
class ThemePreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val ACCENT_COLOR_KEY = stringPreferencesKey("accent_color")
        private val BACKGROUND_COLOR_KEY = stringPreferencesKey("background_color")
        private val SURFACE_COLOR_KEY = stringPreferencesKey("surface_color")
        private val ERROR_COLOR_KEY = stringPreferencesKey("error_color")
        private val FONT_SCALE_KEY = floatPreferencesKey("font_scale")
        private val PRIORITY_COLOR_NONE_KEY = stringPreferencesKey("priority_color_none")
        private val PRIORITY_COLOR_LOW_KEY = stringPreferencesKey("priority_color_low")
        private val PRIORITY_COLOR_MEDIUM_KEY = stringPreferencesKey("priority_color_medium")
        private val PRIORITY_COLOR_HIGH_KEY = stringPreferencesKey("priority_color_high")
        private val PRIORITY_COLOR_URGENT_KEY = stringPreferencesKey("priority_color_urgent")
        private val RECENT_CUSTOM_COLORS_KEY = stringPreferencesKey("recent_custom_colors")
        private val PRISM_THEME_KEY = stringPreferencesKey("pref_prism_theme")

        // Optional override that lets home-screen widgets use a different
        // PrismTheme than the in-app theme. Empty / unset => widgets follow
        // the app theme. Stored as the enum name (e.g. "CYBERPUNK").
        private val WIDGET_THEME_OVERRIDE_KEY = stringPreferencesKey("pref_widget_theme_override")
        private val THEME_UPDATED_AT_KEY = longPreferencesKey("theme_updated_at")
        private val THEME_LAST_SYNCED_AT_KEY = longPreferencesKey("theme_last_synced_at")

        private const val MAX_RECENT_CUSTOM_COLORS = 5

        /** Default PrismTheme value stored when the user has not picked one. */
        const val DEFAULT_PRISM_THEME = "VOID"

        /** Returns true iff [hex] is a valid 6- or 8-digit hex color string. */
        fun isValidHex(hex: String): Boolean {
            val trimmed = hex.trim()
            if (!trimmed.startsWith("#")) return false
            val body = trimmed.drop(1)
            if (body.length != 6 && body.length != 8) return false
            return body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }

        /** Inserts [hex] at the head of [existing], capped at [MAX_RECENT_CUSTOM_COLORS]. */
        fun addToRecentColors(existing: List<String>, hex: String): List<String> {
            val upper = hex.uppercase()
            val dedup = existing.filterNot { it.equals(upper, ignoreCase = true) }
            return (listOf(upper) + dedup).take(MAX_RECENT_CUSTOM_COLORS)
        }
    }

    fun getThemeMode(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY] ?: "system"
    }

    suspend fun setThemeMode(mode: String) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[THEME_MODE_KEY] = mode
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    fun getAccentColor(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[ACCENT_COLOR_KEY] ?: "#2563EB"
    }

    suspend fun setAccentColor(hex: String) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[ACCENT_COLOR_KEY] = hex
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    fun getBackgroundColor(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[BACKGROUND_COLOR_KEY] ?: ""
    }

    suspend fun setBackgroundColor(hex: String) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[BACKGROUND_COLOR_KEY] = hex
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    fun getSurfaceColor(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[SURFACE_COLOR_KEY] ?: ""
    }

    suspend fun setSurfaceColor(hex: String) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[SURFACE_COLOR_KEY] = hex
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    fun getErrorColor(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[ERROR_COLOR_KEY] ?: ""
    }

    suspend fun setErrorColor(hex: String) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[ERROR_COLOR_KEY] = hex
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    fun getFontScale(): Flow<Float> = context.themePrefsDataStore.data.map { prefs ->
        prefs[FONT_SCALE_KEY] ?: 1.0f
    }

    suspend fun setFontScale(scale: Float) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[FONT_SCALE_KEY] = scale
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    fun getPriorityColorNone(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[PRIORITY_COLOR_NONE_KEY] ?: ""
    }

    fun getPriorityColorLow(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[PRIORITY_COLOR_LOW_KEY] ?: ""
    }

    fun getPriorityColorMedium(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[PRIORITY_COLOR_MEDIUM_KEY] ?: ""
    }

    fun getPriorityColorHigh(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[PRIORITY_COLOR_HIGH_KEY] ?: ""
    }

    fun getPriorityColorUrgent(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[PRIORITY_COLOR_URGENT_KEY] ?: ""
    }

    suspend fun setPriorityColor(level: Int, hex: String) {
        val key = when (level) {
            0 -> PRIORITY_COLOR_NONE_KEY
            1 -> PRIORITY_COLOR_LOW_KEY
            2 -> PRIORITY_COLOR_MEDIUM_KEY
            3 -> PRIORITY_COLOR_HIGH_KEY
            4 -> PRIORITY_COLOR_URGENT_KEY
            else -> return
        }
        context.themePrefsDataStore.edit { prefs ->
            prefs[key] = hex
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    fun getRecentCustomColors(): Flow<List<String>> = context.themePrefsDataStore.data.map { prefs ->
        prefs[RECENT_CUSTOM_COLORS_KEY]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && isValidHex(it) }
            ?: emptyList()
    }

    suspend fun addRecentCustomColor(hex: String) {
        if (!isValidHex(hex)) return
        context.themePrefsDataStore.edit { prefs ->
            val current = prefs[RECENT_CUSTOM_COLORS_KEY]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val updated = addToRecentColors(current, hex)
            prefs[RECENT_CUSTOM_COLORS_KEY] = updated.joinToString(",")
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    /**
     * Returns the currently selected [com.averycorp.prismtask.ui.theme.PrismTheme]
     * name, or [DEFAULT_PRISM_THEME] if the user has not chosen one yet.
     */
    fun getPrismTheme(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[PRISM_THEME_KEY] ?: DEFAULT_PRISM_THEME
    }

    /**
     * Persists the user's [com.averycorp.prismtask.ui.theme.PrismTheme]
     * selection. [themeName] should be the enum name (e.g. "VOID", "CYBERPUNK").
     */
    suspend fun setPrismTheme(themeName: String) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[PRISM_THEME_KEY] = themeName
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    /**
     * Returns the user's optional widget-only theme override.
     *
     * Emits the empty string when the user hasn't set a separate widget
     * theme — callers (e.g. [com.averycorp.prismtask.widget.loadWidgetPalette])
     * should treat empty as "follow the app theme".
     */
    fun getWidgetThemeOverride(): Flow<String> = context.themePrefsDataStore.data.map { prefs ->
        prefs[WIDGET_THEME_OVERRIDE_KEY] ?: ""
    }

    /**
     * Persists the widget-only theme override. Pass an empty string (or
     * `null`) to clear the override and let widgets follow the app theme.
     */
    suspend fun setWidgetThemeOverride(themeName: String?) {
        context.themePrefsDataStore.edit { prefs ->
            val trimmed = themeName?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                prefs.remove(WIDGET_THEME_OVERRIDE_KEY)
            } else {
                prefs[WIDGET_THEME_OVERRIDE_KEY] = trimmed
            }
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    suspend fun resetColorOverrides() {
        context.themePrefsDataStore.edit { prefs ->
            prefs.remove(BACKGROUND_COLOR_KEY)
            prefs.remove(SURFACE_COLOR_KEY)
            prefs.remove(ERROR_COLOR_KEY)
            prefs.remove(FONT_SCALE_KEY)
            prefs.remove(PRIORITY_COLOR_NONE_KEY)
            prefs.remove(PRIORITY_COLOR_LOW_KEY)
            prefs.remove(PRIORITY_COLOR_MEDIUM_KEY)
            prefs.remove(PRIORITY_COLOR_HIGH_KEY)
            prefs.remove(PRIORITY_COLOR_URGENT_KEY)
            prefs[THEME_UPDATED_AT_KEY] = System.currentTimeMillis()
        }
    }

    suspend fun clearAll() {
        context.themePrefsDataStore.edit { it.clear() }
    }

    // --- Sync support -----------------------------------------------------------

    /** Emits Unit whenever any theme preference changes. */
    val flowOfThemeChanges: Flow<Unit> = context.themePrefsDataStore.data.map { }

    suspend fun getThemeUpdatedAt(): Long =
        context.themePrefsDataStore.data.first()[THEME_UPDATED_AT_KEY] ?: 0L

    suspend fun getThemeLastSyncedAt(): Long =
        context.themePrefsDataStore.data.first()[THEME_LAST_SYNCED_AT_KEY] ?: 0L

    internal suspend fun setThemeLastSyncedAt(timestamp: Long) {
        context.themePrefsDataStore.edit { prefs -> prefs[THEME_LAST_SYNCED_AT_KEY] = timestamp }
    }

    /**
     * Returns all sync-payload fields as a flat map using Firestore field names as keys.
     * Absent optional fields (background_color, priority colors, etc.) fall back to their
     * defaults so the push always sends a complete document.
     */
    internal suspend fun snapshot(): Map<String, Any> {
        val prefs = context.themePrefsDataStore.data.first()
        return buildMap {
            put("prism_theme", prefs[PRISM_THEME_KEY] ?: DEFAULT_PRISM_THEME)
            put("theme_mode", prefs[THEME_MODE_KEY] ?: "system")
            put("accent_color", prefs[ACCENT_COLOR_KEY] ?: "#2563EB")
            put("background_color", prefs[BACKGROUND_COLOR_KEY] ?: "")
            put("surface_color", prefs[SURFACE_COLOR_KEY] ?: "")
            put("error_color", prefs[ERROR_COLOR_KEY] ?: "")
            put("font_scale", prefs[FONT_SCALE_KEY] ?: 1.0f)
            put("priority_color_none", prefs[PRIORITY_COLOR_NONE_KEY] ?: "")
            put("priority_color_low", prefs[PRIORITY_COLOR_LOW_KEY] ?: "")
            put("priority_color_medium", prefs[PRIORITY_COLOR_MEDIUM_KEY] ?: "")
            put("priority_color_high", prefs[PRIORITY_COLOR_HIGH_KEY] ?: "")
            put("priority_color_urgent", prefs[PRIORITY_COLOR_URGENT_KEY] ?: "")
            put("recent_custom_colors", prefs[RECENT_CUSTOM_COLORS_KEY] ?: "")
            put("widget_theme_override", prefs[WIDGET_THEME_OVERRIDE_KEY] ?: "")
        }
    }

    /**
     * Applies fields from a remote Firestore snapshot into DataStore atomically.
     * Only fields present in [remote] are written; absent fields preserve their local values.
     * Only [THEME_LAST_SYNCED_AT_KEY] is updated — never [THEME_UPDATED_AT_KEY].
     */
    internal suspend fun applyRemoteSnapshot(remote: Map<String, Any?>, updatedAt: Long) {
        context.themePrefsDataStore.edit { prefs ->
            (remote["prism_theme"] as? String)?.let { prefs[PRISM_THEME_KEY] = it }
            (remote["theme_mode"] as? String)?.let { prefs[THEME_MODE_KEY] = it }
            (remote["accent_color"] as? String)?.let { prefs[ACCENT_COLOR_KEY] = it }
            (remote["background_color"] as? String)?.let { prefs[BACKGROUND_COLOR_KEY] = it }
            (remote["surface_color"] as? String)?.let { prefs[SURFACE_COLOR_KEY] = it }
            (remote["error_color"] as? String)?.let { prefs[ERROR_COLOR_KEY] = it }
            (remote["font_scale"] as? Number)?.let { prefs[FONT_SCALE_KEY] = it.toFloat() }
            (remote["priority_color_none"] as? String)?.let { prefs[PRIORITY_COLOR_NONE_KEY] = it }
            (remote["priority_color_low"] as? String)?.let { prefs[PRIORITY_COLOR_LOW_KEY] = it }
            (remote["priority_color_medium"] as? String)?.let { prefs[PRIORITY_COLOR_MEDIUM_KEY] = it }
            (remote["priority_color_high"] as? String)?.let { prefs[PRIORITY_COLOR_HIGH_KEY] = it }
            (remote["priority_color_urgent"] as? String)?.let { prefs[PRIORITY_COLOR_URGENT_KEY] = it }
            (remote["recent_custom_colors"] as? String)?.let { prefs[RECENT_CUSTOM_COLORS_KEY] = it }
            (remote["widget_theme_override"] as? String)?.let { prefs[WIDGET_THEME_OVERRIDE_KEY] = it }
            // Do NOT touch THEME_UPDATED_AT_KEY here. That key is the local "user last changed
            // something" timestamp and is only written by ThemePreferences setters. Writing it
            // from the pull path would overwrite a device's local clock with a remote device's
            // clock on self-echoes, causing the push guard (updatedAt > lastSynced) to suppress
            // subsequent user changes whenever the other device's clock is slightly ahead.
            // Only THEME_LAST_SYNCED_AT_KEY moves forward on pull.
            prefs[THEME_LAST_SYNCED_AT_KEY] = updatedAt
        }
    }
}
