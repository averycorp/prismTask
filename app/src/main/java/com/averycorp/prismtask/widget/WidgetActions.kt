package com.averycorp.prismtask.widget

import android.content.Context
import android.os.SystemClock
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.notifications.PomodoroTimerService
import kotlinx.coroutines.flow.first

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
    val MEDICATION_SLOT_ID: ActionParameters.Key<Long> =
        ActionParameters.Key("prismtask-widget-medication-slot-id")

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

/**
 * Starts a fresh focus session from the [TimerWidget] without first opening
 * the app. Mirrors what `TimerViewModel.start()` does for an idle WORK
 * session: read the user's Pomodoro toggle + work duration + sessions-per-
 * cycle from [TimerPreferences], pre-write a running [TimerWidgetState] so
 * the widget flips out of "Ready to Focus" immediately, then hand off to
 * [PomodoroTimerService] as [PomodoroTimerService.OWNER_TIMER].
 *
 * Pre-writing the structural fields (mode, session counts, pomodoro flag)
 * matters because the service's per-tick `pushWidgetRunState` only touches
 * the run flags + deadline; without this seed write the widget would render
 * an "isRunning" pill against stale structural fields from the last in-app
 * session.
 *
 * When the user later opens the app, `TimerViewModel`'s broadcast receiver
 * picks up the service's ACTION_TICK / ACTION_PAUSED / ACTION_COMPLETE
 * stream and resyncs in-app UI to the widget-started session.
 */
class TimerStartFromWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            val prefs = TimerPreferences(context.applicationContext)
            val workSeconds = prefs.getWorkDurationSeconds().first()
            val pomodoroEnabled = prefs.getPomodoroEnabled().first()
            val sessionsUntilLongBreak = prefs.getSessionsUntilLongBreak().first()
            val sessionEnd = SystemClock.elapsedRealtime() + workSeconds * 1000L

            TimerStateDataStore.write(
                context = context,
                state = TimerWidgetState(
                    isRunning = true,
                    isPaused = false,
                    currentTaskTitle = null,
                    remainingSeconds = workSeconds,
                    totalSeconds = workSeconds,
                    sessionType = "work",
                    currentSession = 1,
                    totalSessions = sessionsUntilLongBreak,
                    isLongBreak = false,
                    pomodoroEnabled = pomodoroEnabled,
                    sessionEndElapsedRealtime = sessionEnd
                )
            )
            TimerWidget().updateAll(context)

            PomodoroTimerService.start(
                context = context,
                durationSeconds = workSeconds,
                sessionIndex = 0,
                sessionType = PomodoroTimerService.SESSION_TYPE_WORK,
                owner = PomodoroTimerService.OWNER_TIMER
            )
        } catch (_: Exception) {
            // Test contexts (mocked Context, no DataStore disk) and edge
            // cases (locked device early in boot) may reject either the
            // prefs read or the foreground service start. The widget will
            // redraw on the next refresh; the user can retry.
        }
    }
}

/**
 * Logs one outstanding dose against the slot the user tapped on
 * [MedicationWidget]. Mirrors the in-app "mark slot taken" affordance: every
 * active medication wired to the slot that does not yet have a non-synthetic
 * dose on today's local date receives a fresh dose row. Idempotent — tapping
 * a fully-filled slot is a no-op (the inner provider filters by remaining
 * count), and the widget refresh is dispatched regardless so a stale snapshot
 * gets cleared. Failures are swallowed because the widget is a fire-and-
 * forget surface — surfacing an error here would just leave a stuck tile.
 */
class MarkDoseTakenFromWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val slotId = parameters[WidgetActionKeys.MEDICATION_SLOT_ID] ?: return
        try {
            WidgetDataProvider.markMedicationSlotTaken(context, slotId)
        } catch (_: Exception) {
            // fail silently — widget will redraw on next refresh
        }
        try {
            MedicationWidget().updateAll(context)
        } catch (_: Exception) {
        }
    }
}

/** Helper for call sites that need to build a parameter bundle inline. */
fun taskIdParams(taskId: Long): ActionParameters =
    actionParametersOf(WidgetActionKeys.TASK_ID to taskId)

fun habitIdParams(habitId: Long): ActionParameters =
    actionParametersOf(WidgetActionKeys.HABIT_ID to habitId)

fun medicationSlotIdParams(slotId: Long): ActionParameters =
    actionParametersOf(WidgetActionKeys.MEDICATION_SLOT_ID to slotId)

fun timerControlParams(control: String): ActionParameters =
    actionParametersOf(WidgetActionKeys.TIMER_CONTROL to control)
