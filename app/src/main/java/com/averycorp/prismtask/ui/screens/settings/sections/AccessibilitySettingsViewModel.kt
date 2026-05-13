package com.averycorp.prismtask.ui.screens.settings.sections

import com.averycorp.prismtask.data.preferences.A11yPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Accessibility-related Settings state and setters.
 *
 * Extracted from [com.averycorp.prismtask.ui.screens.settings.SettingsViewModel]
 * as part of T1.2 of REFACTOR_TIERS_1_3_AUDIT.
 */
class AccessibilitySettingsViewModel @Inject constructor(
    private val a11yPreferences: A11yPreferences
) {
    private lateinit var scope: CoroutineScope

    lateinit var reduceMotionEnabled: StateFlow<Boolean>
        private set
    lateinit var highContrastEnabled: StateFlow<Boolean>
        private set
    lateinit var largeTouchTargetsEnabled: StateFlow<Boolean>
        private set

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        val started = SharingStarted.WhileSubscribed(5000)
        reduceMotionEnabled = a11yPreferences.getReduceMotion().stateIn(scope, started, false)
        highContrastEnabled = a11yPreferences.getHighContrast().stateIn(scope, started, false)
        largeTouchTargetsEnabled =
            a11yPreferences.getLargeTouchTargets().stateIn(scope, started, false)
    }

    fun setReduceMotion(enabled: Boolean) {
        scope.launch { a11yPreferences.setReduceMotion(enabled) }
    }

    fun setHighContrast(enabled: Boolean) {
        scope.launch { a11yPreferences.setHighContrast(enabled) }
    }

    fun setLargeTouchTargets(enabled: Boolean) {
        scope.launch { a11yPreferences.setLargeTouchTargets(enabled) }
    }
}
