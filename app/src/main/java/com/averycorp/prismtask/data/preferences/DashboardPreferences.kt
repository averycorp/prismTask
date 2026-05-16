package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        private val COMPLETION_COUNT_MODE = stringPreferencesKey("completion_count_mode")
        private val SHOW_PROGRESS_PERCENTAGE = booleanPreferencesKey("show_progress_percentage")
        private val RING_AS_COMPLETION_ARC = booleanPreferencesKey("ring_as_completion_arc")

        val DEFAULT_ORDER = listOf(
            "progress",
            "daily_essentials",
            "habits",
            "overdue",
            "today_tasks",
            "plan_more",
            "completed"
        )

        // Sections collapsed by default. Anything not in this set is expanded.
        val DEFAULT_COLLAPSED = setOf("planned", "completed")

        val DEFAULT_HIDDEN = emptySet<String>()

        val DEFAULT_COMPLETION_COUNT_MODE = CompletionCountMode.TASKS_AND_HABITS
    }

    fun getSectionOrder(): Flow<List<String>> = context.dashboardDataStore.data.map { prefs ->
        val stored = prefs[SECTION_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: return@map DEFAULT_ORDER
        // Append any sections added to DEFAULT_ORDER after the user customized their order,
        // so newly introduced toggles (e.g. "habits") still surface in settings.
        val missing = DEFAULT_ORDER.filterNot { it in stored }
        if (missing.isEmpty()) stored else stored + missing
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

    fun getCompletionCountMode(): Flow<CompletionCountMode> = context.dashboardDataStore.data.map { prefs ->
        CompletionCountMode.fromName(prefs[COMPLETION_COUNT_MODE])
    }

    suspend fun setCompletionCountMode(mode: CompletionCountMode) {
        context.dashboardDataStore.edit { prefs ->
            prefs[COMPLETION_COUNT_MODE] = mode.name
        }
    }

    fun getShowProgressPercentage(): Flow<Boolean> = context.dashboardDataStore.data.map { prefs ->
        prefs[SHOW_PROGRESS_PERCENTAGE] ?: false
    }

    suspend fun setShowProgressPercentage(show: Boolean) {
        context.dashboardDataStore.edit { prefs ->
            prefs[SHOW_PROGRESS_PERCENTAGE] = show
        }
    }

    fun getRingAsCompletionArc(): Flow<Boolean> = context.dashboardDataStore.data.map { prefs ->
        prefs[RING_AS_COMPLETION_ARC] ?: false
    }

    suspend fun setRingAsCompletionArc(enabled: Boolean) {
        context.dashboardDataStore.edit { prefs ->
            prefs[RING_AS_COMPLETION_ARC] = enabled
        }
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
            prefs.remove(COMPLETION_COUNT_MODE)
            prefs.remove(SHOW_PROGRESS_PERCENTAGE)
            prefs.remove(RING_AS_COMPLETION_ARC)
        }
    }
}

/**
 * Controls what counts toward the "X done" number shown in the Today screen
 * progress header.
 *
 * - [TASKS_ONLY]: only completed tasks contribute.
 * - [TASKS_AND_HABITS]: completed tasks + completed habits (legacy default).
 * - [TASKS_HABITS_AND_SELFCARE]: tasks + habits + completed self-care routines
 *   (morning / bedtime / housework / medication).
 */
enum class CompletionCountMode {
    TASKS_ONLY,
    TASKS_AND_HABITS,
    TASKS_HABITS_AND_SELFCARE;

    companion object {
        fun fromName(name: String?): CompletionCountMode = entries.firstOrNull { it.name == name }
            ?: TASKS_AND_HABITS
    }
}
