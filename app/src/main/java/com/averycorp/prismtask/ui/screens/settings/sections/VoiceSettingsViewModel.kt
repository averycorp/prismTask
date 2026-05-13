package com.averycorp.prismtask.ui.screens.settings.sections

import com.averycorp.prismtask.data.preferences.VoicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Voice-input Settings state and setters.
 *
 * Extracted from [com.averycorp.prismtask.ui.screens.settings.SettingsViewModel]
 * as part of T1.2 of REFACTOR_TIERS_1_3_AUDIT.
 */
class VoiceSettingsViewModel @Inject constructor(
    private val voicePreferences: VoicePreferences
) {
    private lateinit var scope: CoroutineScope

    lateinit var voiceInputEnabled: StateFlow<Boolean>
        private set
    lateinit var voiceFeedbackEnabled: StateFlow<Boolean>
        private set
    lateinit var continuousModeEnabled: StateFlow<Boolean>
        private set

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        val started = SharingStarted.WhileSubscribed(5000)
        voiceInputEnabled = voicePreferences.getVoiceInputEnabled().stateIn(scope, started, true)
        voiceFeedbackEnabled =
            voicePreferences.getVoiceFeedbackEnabled().stateIn(scope, started, true)
        continuousModeEnabled =
            voicePreferences.getContinuousModeEnabled().stateIn(scope, started, true)
    }

    fun setVoiceInputEnabled(enabled: Boolean) {
        scope.launch { voicePreferences.setVoiceInputEnabled(enabled) }
    }

    fun setVoiceFeedbackEnabled(enabled: Boolean) {
        scope.launch { voicePreferences.setVoiceFeedbackEnabled(enabled) }
    }

    fun setContinuousModeEnabled(enabled: Boolean) {
        scope.launch { voicePreferences.setContinuousModeEnabled(enabled) }
    }
}
