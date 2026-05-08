package com.averycorp.prismtask.widget

import android.content.BroadcastReceiver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Per-instance configuration for home screen widgets.
 *
 * Keys are namespaced as `widget_{appWidgetId}_{key}` so each placed widget
 * has its own isolated settings. When a widget is removed, call
 * [clearForWidget] from the widget receiver's onDeleted override to avoid
 * leaking orphaned config.
 *
 * Added in v1.3.0 (P10).
 */
private val Context.widgetConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_config")

object WidgetConfigDataStore {
    // ---- Today widget ----
    data class TodayConfig(
        val showProgress: Boolean = true,
        val showTaskList: Boolean = true,
        val showHabitSummary: Boolean = true,
        val maxTasks: Int = 8,
        val showOverdueBadge: Boolean = true,
        val backgroundOpacityPercent: Int = 100
    )

    fun todayConfigFlow(context: Context, appWidgetId: Int): Flow<TodayConfig> =
        context.widgetConfigDataStore.data.map { prefs ->
            TodayConfig(
                showProgress = prefs[booleanPreferencesKey("widget_${appWidgetId}_show_progress")] ?: true,
                showTaskList = prefs[booleanPreferencesKey("widget_${appWidgetId}_show_task_list")] ?: true,
                showHabitSummary = prefs[booleanPreferencesKey("widget_${appWidgetId}_show_habit_summary")] ?: true,
                maxTasks = prefs[intPreferencesKey("widget_${appWidgetId}_max_tasks")]?.coerceIn(MAX_TASKS_RANGE) ?: 8,
                showOverdueBadge = prefs[booleanPreferencesKey("widget_${appWidgetId}_show_overdue_badge")] ?: true,
                backgroundOpacityPercent = prefs[intPreferencesKey("widget_${appWidgetId}_bg_opacity")]
                    ?.coerceIn(OPACITY_RANGE) ?: 100
            )
        }

    suspend fun setTodayConfig(context: Context, appWidgetId: Int, config: TodayConfig) {
        context.widgetConfigDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("widget_${appWidgetId}_show_progress")] = config.showProgress
            prefs[booleanPreferencesKey("widget_${appWidgetId}_show_task_list")] = config.showTaskList
            prefs[booleanPreferencesKey("widget_${appWidgetId}_show_habit_summary")] = config.showHabitSummary
            prefs[intPreferencesKey("widget_${appWidgetId}_max_tasks")] = config.maxTasks.coerceIn(MAX_TASKS_RANGE)
            prefs[booleanPreferencesKey("widget_${appWidgetId}_show_overdue_badge")] = config.showOverdueBadge
            prefs[intPreferencesKey("widget_${appWidgetId}_bg_opacity")] =
                config.backgroundOpacityPercent.coerceIn(OPACITY_RANGE)
        }
    }

    // ---- Inbox widget ----
    data class InboxConfig(
        val maxItems: Int = 5
    )

    fun inboxConfigFlow(context: Context, appWidgetId: Int): Flow<InboxConfig> =
        context.widgetConfigDataStore.data.map { prefs ->
            InboxConfig(
                maxItems = prefs[intPreferencesKey("widget_${appWidgetId}_inbox_max_items")]
                    ?.coerceIn(INBOX_MAX_RANGE) ?: 5
            )
        }

    suspend fun setInboxConfig(context: Context, appWidgetId: Int, config: InboxConfig) {
        context.widgetConfigDataStore.edit { prefs ->
            prefs[intPreferencesKey("widget_${appWidgetId}_inbox_max_items")] =
                config.maxItems.coerceIn(INBOX_MAX_RANGE)
        }
    }

    suspend fun snapshotInboxConfig(context: Context, appWidgetId: Int): InboxConfig =
        inboxConfigFlow(context, appWidgetId).first()

    // ---- Habit streak widget ----
    data class HabitStreakConfig(
        val selectedHabitIds: List<Long> = emptyList(),
        val showStreakCount: Boolean = true,
        val layoutGrid: Boolean = false,
        val maxItems: Int = 6
    )

    fun habitStreakConfigFlow(context: Context, appWidgetId: Int): Flow<HabitStreakConfig> =
        context.widgetConfigDataStore.data.map { prefs ->
            val cap = prefs[intPreferencesKey("widget_${appWidgetId}_habit_max_items")]
                ?.coerceIn(HABIT_STREAK_MAX_RANGE) ?: 6
            val csv = prefs[stringPreferencesKey("widget_${appWidgetId}_habit_ids")] ?: ""
            HabitStreakConfig(
                selectedHabitIds = csv.split(",").mapNotNull { it.trim().toLongOrNull() }.take(cap),
                showStreakCount = prefs[booleanPreferencesKey("widget_${appWidgetId}_show_streak_count")] ?: true,
                layoutGrid = prefs[booleanPreferencesKey("widget_${appWidgetId}_layout_grid")] ?: false,
                maxItems = cap
            )
        }

    suspend fun setHabitStreakConfig(context: Context, appWidgetId: Int, config: HabitStreakConfig) {
        val cap = config.maxItems.coerceIn(HABIT_STREAK_MAX_RANGE)
        context.widgetConfigDataStore.edit { prefs ->
            prefs[stringPreferencesKey("widget_${appWidgetId}_habit_ids")] =
                config.selectedHabitIds.take(cap).joinToString(",")
            prefs[booleanPreferencesKey("widget_${appWidgetId}_show_streak_count")] = config.showStreakCount
            prefs[booleanPreferencesKey("widget_${appWidgetId}_layout_grid")] = config.layoutGrid
            prefs[intPreferencesKey("widget_${appWidgetId}_habit_max_items")] = cap
        }
    }

    // ---- Project widget (v1.4.0 Projects feature Phase 3) ----
    data class ProjectConfig(
        /** `null` when the user hasn't picked a project yet — widget shows a "Tap to configure" state. */
        val projectId: Long? = null
    )

    fun projectConfigFlow(context: Context, appWidgetId: Int): Flow<ProjectConfig> =
        context.widgetConfigDataStore.data.map { prefs ->
            ProjectConfig(
                projectId = prefs[longPreferencesKey("widget_${appWidgetId}_project_id")]
                    ?.takeIf { it > 0 }
            )
        }

    suspend fun setProjectConfig(context: Context, appWidgetId: Int, config: ProjectConfig) {
        context.widgetConfigDataStore.edit { prefs ->
            prefs[longPreferencesKey("widget_${appWidgetId}_project_id")] = config.projectId ?: -1L
        }
    }

    suspend fun snapshotProjectConfig(context: Context, appWidgetId: Int): ProjectConfig =
        projectConfigFlow(context, appWidgetId).first()

    // ---- Quick add widget ----
    data class QuickAddConfig(
        val placeholder: String = "Add a task...",
        val defaultProjectId: Long? = null
    )

    fun quickAddConfigFlow(context: Context, appWidgetId: Int): Flow<QuickAddConfig> =
        context.widgetConfigDataStore.data.map { prefs ->
            QuickAddConfig(
                placeholder = prefs[stringPreferencesKey("widget_${appWidgetId}_placeholder")] ?: "Add a task\u2026",
                defaultProjectId = prefs[longPreferencesKey("widget_${appWidgetId}_default_project")]
                    ?.takeIf { it >= 0 }
            )
        }

    suspend fun setQuickAddConfig(context: Context, appWidgetId: Int, config: QuickAddConfig) {
        context.widgetConfigDataStore.edit { prefs ->
            prefs[stringPreferencesKey("widget_${appWidgetId}_placeholder")] = config.placeholder
            prefs[longPreferencesKey("widget_${appWidgetId}_default_project")] = config.defaultProjectId ?: -1L
        }
    }

    /** Removes every key whose name references the given appWidgetId. */
    suspend fun clearForWidget(context: Context, appWidgetId: Int) {
        context.widgetConfigDataStore.edit { prefs ->
            val prefix = "widget_${appWidgetId}_"
            val keysToRemove = prefs.asMap().keys.filter { it.name.startsWith(prefix) }
            keysToRemove.forEach { prefs.remove(it) }
        }
    }

    /** Snapshot helper used by the widget update path. */
    suspend fun snapshotTodayConfig(context: Context, appWidgetId: Int): TodayConfig =
        todayConfigFlow(context, appWidgetId).first()

    suspend fun snapshotHabitStreakConfig(context: Context, appWidgetId: Int): HabitStreakConfig =
        habitStreakConfigFlow(context, appWidgetId).first()

    suspend fun snapshotQuickAddConfig(context: Context, appWidgetId: Int): QuickAddConfig =
        quickAddConfigFlow(context, appWidgetId).first()

    private val MAX_TASKS_RANGE = 1..20

    /**
     * Lowered from `60..100` (D3): high-contrast wallpaper users may want
     * a fully transparent widget. Coerced bottom is now 0; the widget's
     * empty-state strip stays readable below ~30% in practice.
     */
    private val OPACITY_RANGE = 0..100

    /** D2 — inbox widget item cap (small ≈ 3, max 12 for tablet rows). */
    private val INBOX_MAX_RANGE = 1..12

    /** D5 — habit-streak widget item cap (default 6, up to 12 for large widgets). */
    private val HABIT_STREAK_MAX_RANGE = 1..12
}

private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Hook for `GlanceAppWidgetReceiver.onDeleted` so each widget instance's
 * per-instance config is purged from DataStore when the user removes the
 * widget from the home screen. Uses [BroadcastReceiver.goAsync] so the
 * receiver stays alive while the DataStore writes complete.
 *
 * `goAsync()` returns null when the receiver isn't currently dispatching a
 * broadcast — e.g. when `super.onDeleted()` (which `GlanceAppWidgetReceiver`
 * itself wraps in `goAsync`/`finish`) has already finished the result before
 * we get here. In that case the broadcast lifecycle is already over, so we
 * just run the cleanup on the background scope without keeping anything
 * alive.
 */
fun clearWidgetConfigOnDelete(
    receiver: BroadcastReceiver,
    context: Context,
    appWidgetIds: IntArray
) {
    val pending: BroadcastReceiver.PendingResult? = receiver.goAsync()
    cleanupScope.launch {
        try {
            appWidgetIds.forEach { WidgetConfigDataStore.clearForWidget(context, it) }
        } finally {
            pending?.finish()
        }
    }
}
