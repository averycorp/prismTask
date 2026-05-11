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

internal val Context.a11yDataStore: DataStore<Preferences> by preferencesDataStore(name = "a11y_prefs")

/** App-level accessibility toggles layered on top of system settings. */
@Singleton
class A11yPreferences
@Inject
constructor(@ApplicationContext private val context: Context) {
    companion object {
        private val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        private val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        private val LARGE_TOUCH_TARGETS = booleanPreferencesKey("large_touch_targets")
    }

    fun getReduceMotion(): Flow<Boolean> = context.a11yDataStore.data.map { prefs ->
        prefs[REDUCE_MOTION] ?: false
    }

    fun getHighContrast(): Flow<Boolean> = context.a11yDataStore.data.map { prefs ->
        prefs[HIGH_CONTRAST] ?: false
    }

    fun getLargeTouchTargets(): Flow<Boolean> = context.a11yDataStore.data.map { prefs ->
        prefs[LARGE_TOUCH_TARGETS] ?: false
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        context.a11yDataStore.edit { it[REDUCE_MOTION] = enabled }
    }

    suspend fun setHighContrast(enabled: Boolean) {
        context.a11yDataStore.edit { it[HIGH_CONTRAST] = enabled }
    }

    suspend fun setLargeTouchTargets(enabled: Boolean) {
        context.a11yDataStore.edit { it[LARGE_TOUCH_TARGETS] = enabled }
    }
}
