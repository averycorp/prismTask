package com.averycorp.prismtask.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.averycorp.prismtask.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Glance widget refreshes across every registered widget.
 *
 * Each `update*()` method is debounced: when called multiple times within
 * [DEBOUNCE_MILLIS] (e.g. during batch task operations) the actual refresh
 * is coalesced into a single update, saving battery and avoiding flicker.
 */
@Singleton
class WidgetUpdateManager
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob())

    private var allWidgetsJob: Job? = null
    private var taskWidgetsJob: Job? = null
    private var habitWidgetsJob: Job? = null
    private var timerWidgetJob: Job? = null
    private var productivityWidgetJob: Job? = null
    private var projectWidgetJob: Job? = null
    private var medicationWidgetJob: Job? = null

    /** Refreshes every registered widget (debounced). */
    suspend fun updateAllWidgets() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        allWidgetsJob?.cancel()
        allWidgetsJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            safeUpdate { TodayWidget().updateAll(context) }
            safeUpdate { HabitStreakWidget().updateAll(context) }
            safeUpdate { QuickAddWidget().updateAll(context) }
            safeUpdate { CalendarWidget().updateAll(context) }
            safeUpdate { ProductivityWidget().updateAll(context) }
            safeUpdate { TimerWidget().updateAll(context) }
            safeUpdate { UpcomingWidget().updateAll(context) }
            safeUpdate { ProjectWidget().updateAll(context) }
            safeUpdate { EisenhowerWidget().updateAll(context) }
            safeUpdate { StreakCalendarWidget().updateAll(context) }
            safeUpdate { FocusWidget().updateAll(context) }
            safeUpdate { MedicationWidget().updateAll(context) }
            safeUpdate { StatsSparklineWidget().updateAll(context) }
            safeUpdate { InboxWidget().updateAll(context) }
        }
    }

    /**
     * Refreshes the [ProjectWidget] only (debounced). Called from project
     * / milestone / project-task mutations so widget instances track the
     * latest state without waiting on the 15-min periodic worker.
     */
    suspend fun updateProjectWidget() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        projectWidgetJob?.cancel()
        projectWidgetJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            safeUpdate { ProjectWidget().updateAll(context) }
        }
    }

    /**
     * Refreshes every widget that consumes task data (debounced):
     * Today, Upcoming, Calendar, Productivity, Eisenhower, Focus, Inbox,
     * StatsSparkline. Eisenhower / Inbox / StatsSparkline read live from
     * the tasks table; Focus tracks the user's "next" task; the original
     * four follow same-day / planned / overdue projections.
     */
    suspend fun updateTaskWidgets() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        taskWidgetsJob?.cancel()
        taskWidgetsJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            safeUpdate { TodayWidget().updateAll(context) }
            safeUpdate { UpcomingWidget().updateAll(context) }
            safeUpdate { CalendarWidget().updateAll(context) }
            safeUpdate { ProductivityWidget().updateAll(context) }
            safeUpdate { EisenhowerWidget().updateAll(context) }
            safeUpdate { FocusWidget().updateAll(context) }
            safeUpdate { InboxWidget().updateAll(context) }
            safeUpdate { StatsSparklineWidget().updateAll(context) }
        }
    }

    /**
     * Refreshes habit-related widgets (debounced): HabitStreak, Today
     * (habits appear on Today), and StreakCalendar (heatmap).
     */
    suspend fun updateHabitWidgets() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        habitWidgetsJob?.cancel()
        habitWidgetsJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            safeUpdate { HabitStreakWidget().updateAll(context) }
            safeUpdate { TodayWidget().updateAll(context) }
            safeUpdate { StreakCalendarWidget().updateAll(context) }
        }
    }

    /** Refreshes the MedicationWidget only (debounced). */
    suspend fun updateMedicationWidget() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        medicationWidgetJob?.cancel()
        medicationWidgetJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            safeUpdate { MedicationWidget().updateAll(context) }
        }
    }

    /** Refreshes the TimerWidget only (debounced). */
    /**
     * Push a fresh [TimerWidgetState] snapshot into the timer widget's
     * DataStore. Wraps the write in try/catch so a write failure (disk
     * pressure, file-system errors, mocked-Context unit tests) leaves the
     * widget showing the last successful snapshot but never takes the
     * caller down. Routed through [scope] (the manager's app-scoped
     * SupervisorJob) so the call is decoupled from the caller's
     * lifecycle — a unit test that resets `Dispatchers.Main` between
     * runs no longer leaks a half-completed DataStore continuation
     * back into the next test's `Dispatchers.Main` access.
     */
    fun writeTimerState(state: TimerWidgetState) {
        if (!BuildConfig.WIDGETS_ENABLED) return
        scope.launch {
            try {
                TimerStateDataStore.write(context, state)
            } catch (e: Exception) {
                Log.w(TAG, "TimerStateDataStore.write failed: ${e.message}")
            }
        }
    }

    suspend fun updateTimerWidget() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        timerWidgetJob?.cancel()
        timerWidgetJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            safeUpdate { TimerWidget().updateAll(context) }
        }
    }

    /**
     * Clears the TimerWidget DataStore and refreshes the widget on the
     * application-scoped [scope]. Called from `TimerViewModel.onCleared`,
     * where `viewModelScope` is already cancelled and cannot run cleanup
     * coroutines — without this entry point a "running" flag in the
     * DataStore would survive ViewModel teardown and leave the widget
     * showing a frozen clock.
     */
    fun clearTimerStateAndUpdate() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        scope.launch {
            try {
                TimerStateDataStore.clear(context)
            } catch (e: Exception) {
                Log.w(TAG, "TimerStateDataStore.clear failed: ${e.message}")
            }
            safeUpdate { TimerWidget().updateAll(context) }
        }
    }

    /** Refreshes the ProductivityWidget only (debounced). */
    suspend fun updateProductivityWidget() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        productivityWidgetJob?.cancel()
        productivityWidgetJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            safeUpdate { ProductivityWidget().updateAll(context) }
        }
    }

    /** Legacy aliases for backward compat with existing callers. */
    suspend fun updateTodayWidget() {
        safeUpdate { TodayWidget().updateAll(context) }
    }

    suspend fun updateHabitWidget() {
        safeUpdate { HabitStreakWidget().updateAll(context) }
    }

    private suspend fun safeUpdate(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Widget update failed (widget may not be placed): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WidgetUpdateManager"

        /** Debounce window: rapid calls within this period are coalesced. */
        const val DEBOUNCE_MILLIS = 500L

        /**
         * Refreshes habit-related widgets from a Glance ActionCallback
         * where Hilt injection is unavailable. Mirrors [updateHabitWidgets]
         * but runs synchronously without debounce. Includes StreakCalendar
         * so the heatmap reflects same-day completions immediately.
         */
        suspend fun refreshHabitWidgets(context: Context) {
            if (!BuildConfig.WIDGETS_ENABLED) return
            try {
                HabitStreakWidget().updateAll(context)
            } catch (e: Exception) {
                Log.w(TAG, "HabitStreakWidget refresh failed: ${e.message}")
            }
            try {
                TodayWidget().updateAll(context)
            } catch (e: Exception) {
                Log.w(TAG, "TodayWidget refresh failed: ${e.message}")
            }
            try {
                ProductivityWidget().updateAll(context)
            } catch (e: Exception) {
                Log.w(TAG, "ProductivityWidget refresh failed: ${e.message}")
            }
            try {
                StreakCalendarWidget().updateAll(context)
            } catch (e: Exception) {
                Log.w(TAG, "StreakCalendarWidget refresh failed: ${e.message}")
            }
        }
    }
}
