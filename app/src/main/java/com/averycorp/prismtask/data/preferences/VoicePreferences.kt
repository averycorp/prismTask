package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_prefs")

/** User-facing settings for voice input, commands, and hands-free mode. */
@Singleton
class VoicePreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val VOICE_INPUT_ENABLED = booleanPreferencesKey("voice_input_enabled")
        private val VOICE_FEEDBACK_ENABLED = booleanPreferencesKey("voice_feedback_enabled")
        private val CONTINUOUS_MODE_ENABLED = booleanPreferencesKey("continuous_mode_enabled")
    }

    fun getVoiceInputEnabled(): Flow<Boolean> = context.voiceDataStore.data.map { prefs ->
        prefs[VOICE_INPUT_ENABLED] ?: true
    }

    fun getVoiceFeedbackEnabled(): Flow<Boolean> = context.voiceDataStore.data.map { prefs ->
        prefs[VOICE_FEEDBACK_ENABLED] ?: true
    }

    fun getContinuousModeEnabled(): Flow<Boolean> = context.voiceDataStore.data.map { prefs ->
        prefs[CONTINUOUS_MODE_ENABLED] ?: true
    }

    suspend fun setVoiceInputEnabled(enabled: Boolean) {
        context.voiceDataStore.edit { it[VOICE_INPUT_ENABLED] = enabled }
    }

    suspend fun setVoiceFeedbackEnabled(enabled: Boolean) {
        context.voiceDataStore.edit { it[VOICE_FEEDBACK_ENABLED] = enabled }
    }

    suspend fun setContinuousModeEnabled(enabled: Boolean) {
        context.voiceDataStore.edit { it[CONTINUOUS_MODE_ENABLED] = enabled }
    }
}
