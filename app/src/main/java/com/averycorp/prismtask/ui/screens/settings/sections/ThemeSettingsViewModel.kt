package com.averycorp.prismtask.ui.screens.settings.sections

import com.averycorp.prismtask.data.preferences.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Theme-related Settings state and setters.
 *
 * Extracted from [com.averycorp.prismtask.ui.screens.settings.SettingsViewModel]
 * as part of T1.2 of REFACTOR_TIERS_1_3_AUDIT. Not annotated with `@HiltViewModel`
 * — instead constructor-injected into `SettingsViewModel` and attached to its
 * `viewModelScope` via [attach]. This keeps the public API of `SettingsViewModel`
 * stable (no consumers need to change) while moving theme logic out of the
 * 1.6k-line coordinator file.
 */
class ThemeSettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences
) {
    private lateinit var scope: CoroutineScope

    lateinit var themeMode: StateFlow<String>
        private set
    lateinit var accentColor: StateFlow<String>
        private set
    lateinit var recentCustomColors: StateFlow<List<String>>
        private set
    lateinit var backgroundColor: StateFlow<String>
        private set
    lateinit var surfaceColor: StateFlow<String>
        private set
    lateinit var errorColor: StateFlow<String>
        private set
    lateinit var fontScale: StateFlow<Float>
        private set
    lateinit var priorityColorNone: StateFlow<String>
        private set
    lateinit var priorityColorLow: StateFlow<String>
        private set
    lateinit var priorityColorMedium: StateFlow<String>
        private set
    lateinit var priorityColorHigh: StateFlow<String>
        private set
    lateinit var priorityColorUrgent: StateFlow<String>
        private set

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        val started = SharingStarted.WhileSubscribed(5000)
        themeMode = themePreferences.getThemeMode().stateIn(scope, started, "system")
        accentColor = themePreferences.getAccentColor().stateIn(scope, started, "#2563EB")
        recentCustomColors = themePreferences.getRecentCustomColors().stateIn(scope, started, emptyList())
        backgroundColor = themePreferences.getBackgroundColor().stateIn(scope, started, "")
        surfaceColor = themePreferences.getSurfaceColor().stateIn(scope, started, "")
        errorColor = themePreferences.getErrorColor().stateIn(scope, started, "")
        fontScale = themePreferences.getFontScale().stateIn(scope, started, 1.0f)
        priorityColorNone = themePreferences.getPriorityColorNone().stateIn(scope, started, "")
        priorityColorLow = themePreferences.getPriorityColorLow().stateIn(scope, started, "")
        priorityColorMedium = themePreferences.getPriorityColorMedium().stateIn(scope, started, "")
        priorityColorHigh = themePreferences.getPriorityColorHigh().stateIn(scope, started, "")
        priorityColorUrgent = themePreferences.getPriorityColorUrgent().stateIn(scope, started, "")
    }

    fun setThemeMode(mode: String) {
        scope.launch { themePreferences.setThemeMode(mode) }
    }

    fun setAccentColor(hex: String) {
        scope.launch { themePreferences.setAccentColor(hex) }
    }

    fun setCustomAccentColor(hex: String) {
        scope.launch {
            if (ThemePreferences.isValidHex(hex)) {
                themePreferences.setAccentColor(hex)
                themePreferences.addRecentCustomColor(hex)
            }
        }
    }

    fun setBackgroundColor(hex: String) {
        scope.launch { themePreferences.setBackgroundColor(hex) }
    }

    fun setSurfaceColor(hex: String) {
        scope.launch { themePreferences.setSurfaceColor(hex) }
    }

    fun setErrorColor(hex: String) {
        scope.launch { themePreferences.setErrorColor(hex) }
    }

    fun setFontScale(scale: Float) {
        scope.launch { themePreferences.setFontScale(scale) }
    }

    fun setPriorityColor(level: Int, hex: String) {
        scope.launch { themePreferences.setPriorityColor(level, hex) }
    }

    fun resetColorOverrides() {
        scope.launch { themePreferences.resetColorOverrides() }
    }
}
