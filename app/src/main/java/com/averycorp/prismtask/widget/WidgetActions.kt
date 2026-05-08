package com.averycorp.prismtask.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.averycorp.prismtask.notifications.PomodoroTimerService

/**
 * Action callbacks invoked by the Glance widgets. Each callback mutates
 * the local database through [WidgetDataProvider] and then nudges the
 * relevant widget to refresh so the UI reflects the new state.
 */
object WidgetActionKeys {
    val TASK_ID: ActionParameters.Key<Long> =
        ActionParameters.Key("prismtask-widget-task-id")
    val HABIT_ID: ActionParameters.Key<Long> =
        ActionParameters.Key("prismtask-widget-habit-id")

    /**
     * One of [TIMER_CONTROL_PAUSE], [TIMER_CONTROL_RESUME], or
     * [TIMER_CONTROL_STOP]. Read by [TimerControlFromWidgetAction] to pick
     * the right [PomodoroTimerService] action to dispatch.
     */
    val TIMER_CONTROL: ActionParameters.Key<String> =
        ActionParameters.Key("prismtask-widget-timer-control")

    const val TIMER_CONTROL_PAUSE = "pause"
    const val TIMER_CONTROL_RESUME = "resume"
    const val TIMER_CONTROL_STOP = "stop"
}

/** Toggles a task's completion state from a widget checkbox tap. */
class ToggleTaskFromWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val taskId = parameters[WidgetActionKeys.TASK_ID] ?: return
        try {
            WidgetDataProvider.toggleTaskCompletion(context, taskId)
        } catch (_: Exception) {
            // fail silently — widget will redraw next tick
        }
        // Refresh every widget that might show this task. Mirrors the
        // widget set covered by [WidgetUpdateManager.updateTaskWidgets].
        val updaters = listOf<suspend () -> Unit>(
            { TodayWidget().updateAll(context) },
            { UpcomingWidget().updateAll(context) },
            { CalendarWidget().updateAll(context) },
            { ProductivityWidget().updateAll(context) },
            { EisenhowerWidget().updateAll(context) },
            { FocusWidget().updateAll(context) },
            { InboxWidget().updateAll(context) },
            { StatsSparklineWidget().updateAll(context) }
        )
        updaters.forEach { update ->
            try {
                update()
            } catch (_: Exception) {
            }
        }
    }
}

/** Toggles a habit's completion for today from a widget cell tap. */
class ToggleHabitFromWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val habitId = parameters[WidgetActionKeys.HABIT_ID] ?: return
        try {
            WidgetDataProvider.toggleHabitCompletion(context, habitId)
        } catch (_: Exception) {
            return
        }
        WidgetUpdateManager.refreshHabitWidgets(context)
    }
}

/**
 * Routes Pause/Resume/Stop taps from [TimerWidget] to
 * [PomodoroTimerService]. The widget can't observe the new
 * service-driven timer state directly, but the service emits owner-tagged
 * broadcasts that `TimerViewModel` consumes; the next widget refresh
 * triggered by `TimerViewModel.syncWidgetState` then reflects the new
 * state. Failures are swallowed because the widget is a fire-and-forget
 * surface — surfacing an error here would just leave a stuck widget
 * tile.
 */
class TimerControlFromWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val control = parameters[WidgetActionKeys.TIMER_CONTROL] ?: return
        try {
            when (control) {
                WidgetActionKeys.TIMER_CONTROL_PAUSE ->
                    PomodoroTimerService.pause(context)
                WidgetActionKeys.TIMER_CONTROL_RESUME ->
                    PomodoroTimerService.resume(context)
                WidgetActionKeys.TIMER_CONTROL_STOP ->
                    PomodoroTimerService.stop(context)
            }
        } catch (_: Exception) {
            // Service may already be stopped, or we're in a context that
            // can't dispatch start/startService (test harness, locked
            // device early in boot, etc.). The next user-driven action
            // will retry.
        }
    }
}

/** Helper for call sites that need to build a parameter bundle inline. */
fun taskIdParams(taskId: Long): ActionParameters =
    actionParametersOf(WidgetActionKeys.TASK_ID to taskId)

fun habitIdParams(habitId: Long): ActionParameters =
    actionParametersOf(WidgetActionKeys.HABIT_ID to habitId)

fun timerControlParams(control: String): ActionParameters =
    actionParametersOf(WidgetActionKeys.TIMER_CONTROL to control)
