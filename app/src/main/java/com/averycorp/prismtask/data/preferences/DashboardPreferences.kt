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

internal val Context.dashboardDataStore: DataStore<Preferences> by preferencesDataStore(name = "dashboard_prefs")

@Singleton
class DashboardPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val SECTION_ORDER = stringPreferencesKey("section_order")
        private val HIDDEN_SECTIONS = stringSetPreferencesKey("hidden_sections")
        private val PROGRESS_STYLE = stringPreferencesKey("progress_style")
        private val COLLAPSED_SECTIONS = stringSetPreferencesKey("collapsed_sections")

        val DEFAULT_ORDER = listOf(
            "progress",
            "habits",
            "daily_essentials",
            "overdue",
            "today_tasks",
            "plan_more",
            "completed"
        )

        // Sections collapsed by default. Anything not in this set is expanded.
        val DEFAULT_COLLAPSED = setOf("planned", "completed")

        // Sections hidden by default. The habit bar is opt-in; users who want
        // habit reminders surfaced as Today-screen tasks enable per-habit
        // "Create daily to-do" instead.
        val DEFAULT_HIDDEN = setOf("habits")
    }

    fun getSectionOrder(): Flow<List<String>> = context.dashboardDataStore.data.map { prefs ->
        prefs[SECTION_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_ORDER
    }

    fun getHiddenSections(): Flow<Set<String>> = context.dashboardDataStore.data.map { prefs ->
        prefs[HIDDEN_SECTIONS] ?: DEFAULT_HIDDEN
    }

    fun getProgressStyle(): Flow<String> = context.dashboardDataStore.data.map { prefs ->
        prefs[PROGRESS_STYLE] ?: "ring"
    }

    fun getCollapsedSections(): Flow<Set<String>> = context.dashboardDataStore.data.map { prefs ->
        prefs[COLLAPSED_SECTIONS] ?: DEFAULT_COLLAPSED
    }

    suspend fun setSectionCollapsed(sectionKey: String, collapsed: Boolean) {
        context.dashboardDataStore.edit { prefs ->
            val current = prefs[COLLAPSED_SECTIONS] ?: DEFAULT_COLLAPSED
            prefs[COLLAPSED_SECTIONS] = if (collapsed) current + sectionKey else current - sectionKey
        }
    }

    suspend fun setSectionOrder(order: List<String>) {
        context.dashboardDataStore.edit { prefs ->
            prefs[SECTION_ORDER] = order.joinToString(",")
        }
    }

    suspend fun setHiddenSections(hidden: Set<String>) {
        context.dashboardDataStore.edit { prefs ->
            prefs[HIDDEN_SECTIONS] = hidden
        }
    }

    suspend fun setProgressStyle(style: String) {
        context.dashboardDataStore.edit { prefs ->
            prefs[PROGRESS_STYLE] = style
        }
    }

    suspend fun resetToDefaults() {
        context.dashboardDataStore.edit { prefs ->
            prefs.remove(SECTION_ORDER)
            prefs.remove(HIDDEN_SECTIONS)
            prefs.remove(PROGRESS_STYLE)
            prefs.remove(COLLAPSED_SECTIONS)
        }
    }
}
