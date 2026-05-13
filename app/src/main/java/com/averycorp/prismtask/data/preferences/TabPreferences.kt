package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.tabDataStore: DataStore<Preferences> by preferencesDataStore(name = "tab_prefs")

@Singleton
class TabPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val TAB_ORDER = stringPreferencesKey("tab_order")
        private val HIDDEN_TABS = stringSetPreferencesKey("hidden_tabs")

        val DEFAULT_ORDER = listOf(
            "today",
            "task_list",
            "habit_list",
            "leisure",
            "timer",
            "medication",
            "settings"
        )
    }

    fun getTabOrder(): Flow<List<String>> = context.tabDataStore.data.map { prefs ->
        prefs[TAB_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_ORDER
    }

    fun getHiddenTabs(): Flow<Set<String>> = context.tabDataStore.data.map { prefs ->
        prefs[HIDDEN_TABS] ?: emptySet()
    }

    suspend fun setTabOrder(order: List<String>) {
        context.tabDataStore.edit { prefs ->
            prefs[TAB_ORDER] = order.joinToString(",")
        }
    }

    suspend fun setHiddenTabs(hidden: Set<String>) {
        context.tabDataStore.edit { prefs ->
            prefs[HIDDEN_TABS] = hidden
        }
    }

    suspend fun resetToDefaults() {
        context.tabDataStore.edit { prefs ->
            prefs.remove(TAB_ORDER)
            prefs.remove(HIDDEN_TABS)
        }
    }
}
