package com.averycorp.prismtask.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.domain.model.notifications.QuietHoursWindow
import com.averycorp.prismtask.domain.model.notifications.UrgencyTier
import com.averycorp.prismtask.domain.usecase.QuietHoursDeferrer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler
@Inject
constructor(@ApplicationContext private val context: Context, private val taskDao: TaskDao) {
    // Android's AlarmManager service is platform-defined as non-null in
    // practice, but the framework API returns a nullable getSystemService.
    // Stripped-down OEM ROMs have been observed returning null. Guard here
    // so downstream scheduler code can no-op instead of NPE'ing.
    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    fun scheduleReminder(
        taskId: Long,
        taskTitle: String,
        taskDescription: String?,
        dueDate: Long,
        reminderOffset: Long
    ) {
        scheduleReminder(
            taskId = taskId,
            taskTitle = taskTitle,
            taskDescription = taskDescription,
            dueDate = dueDate,
            reminderOffset = reminderOffset,
            quietHours = QuietHoursWindow.DISABLED,
            urgencyTier = UrgencyTier.MEDIUM
        )
    }

    /**
     * Profile-aware scheduling that honors a [QuietHoursWindow]. If the
     * computed trigger falls inside the window and the [urgencyTier] is
     * NOT on the quiet-hours break-through allowlist, the alarm is
     * deferred to the end of the quiet period.
     *
     * Back-compat wrapper: the zero-arg [scheduleReminder] above uses
     * [QuietHoursWindow.DISABLED] so existing call sites keep their
     * behavior.
     */
    fun scheduleReminder(
        taskId: Long,
        taskTitle: String,
        taskDescription: String?,
        dueDate: Long,
        reminderOffset: Long,
        quietHours: QuietHoursWindow,
        urgencyTier: UrgencyTier
    ) {
        val triggerTime = dueDate - reminderOffset
        val now = System.currentTimeMillis()
        val effectiveTrigger = computeEffectiveTrigger(triggerTime, now) ?: return
        val finalTrigger = applyQuietHours(effectiveTrigger, quietHours, urgencyTier)

        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra("taskId", taskId)
            putExtra("taskTitle", taskTitle)
            putExtra("taskDescription", taskDescription)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        ExactAlarmHelper.scheduleExact(context, finalTrigger, pendingIntent)
    }

    fun cancelReminder(taskId: Long) {
        val intent = Intent(context, ReminderBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.cancel(pendingIntent)
    }

    suspend fun rescheduleAllReminders() {
        val tasks = taskDao.getIncompleteTasksWithReminders()
        for (task in tasks) {
            val dueDate = task.dueDate ?: continue
            val offset = task.reminderOffset ?: continue
            val effective = combineDateAndTime(dueDate, task.dueTime)
            scheduleReminder(task.id, task.title, task.description, effective, offset)
        }
    }

    companion object {
        /**
         * Defers [trigger] until the end of the quiet-hours window when
         * appropriate. If the window doesn't apply today, the trigger
         * isn't inside the window, or the task's urgency tier is on the
         * break-through allowlist, [trigger] is returned unchanged.
         */
        fun applyQuietHours(
            trigger: Long,
            window: QuietHoursWindow,
            urgencyTier: UrgencyTier,
            zone: ZoneId = ZoneId.systemDefault()
        ): Long {
            if (!window.enabled) return trigger
            if (window.canBreakThrough(urgencyTier)) return trigger
            val day = java.time.Instant.ofEpochMilli(trigger)
                .atZone(zone).toLocalDate().dayOfWeek
            if (!window.appliesOn(day)) return trigger
            return QuietHoursDeferrer.defer(
                fireAtMillis = trigger,
                quietStart = window.start,
                quietEnd = window.end,
                zone = zone
            )
        }

        /**
         * Pure helper: compute the wall-clock time at which a reminder should
         * fire given a task's due date and how far in advance the user wants
         * to be nudged. Returned timestamp may be in the past — callers should
         * use [isInFuture] to decide whether to actually schedule an alarm.
         */
        fun computeTriggerTime(dueDate: Long, reminderOffset: Long): Long =
            dueDate - reminderOffset

        /**
         * Pure helper: the alarm should only be registered when the computed
         * trigger time is strictly in the future. Mirrors the guard clause in
         * [scheduleReminder].
         */
        fun isInFuture(triggerTime: Long, now: Long): Boolean = triggerTime > now

        /**
         * Pure helper: decide what time the alarm should actually fire at,
         * given a desired [triggerTime] and the current wall-clock [now].
         *
         * - Future trigger → schedule it as-is.
         * - Past trigger within the last 24h → fall-forward to `now + 5s`
         *   so the user still gets nudged for a same-day reminder we just
         *   missed (this is the bug fix in commit 43c70b6).
         * - Past trigger older than 24h → return null so the caller drops
         *   the alarm; reminders that stale aren't worth firing anymore.
         */
        fun computeEffectiveTrigger(triggerTime: Long, now: Long): Long? = when {
            triggerTime > now -> triggerTime
            now - triggerTime < 24 * 60 * 60 * 1000L -> now + 5_000L
            else -> null
        }

        /**
         * Combine a task's stored [dueDate] (midnight of the due day) with
         * its optional [dueTime] (a timestamp whose HH:mm:ss.SSS is the
         * user-selected time-of-day) into a single absolute instant. When
         * [dueTime] is null the caller has not chosen a specific time, so
         * the raw [dueDate] is returned unchanged.
         *
         * Reminders previously passed just [dueDate] to [scheduleReminder],
         * so a 10-minute reminder on a task due today at 3pm would compute
         * a trigger time of 11:50pm *yesterday* — in the past, silently
         * dropped by the `triggerTime <= now` guard. This helper lets
         * callers pass the correctly combined instant so the alarm actually
         * fires at the expected time.
         */
        fun combineDateAndTime(dueDate: Long, dueTime: Long?): Long {
            if (dueTime == null) return dueDate
            val timeOfDay = Calendar.getInstance().apply { timeInMillis = dueTime }
            return Calendar.getInstance().apply {
                timeInMillis = dueDate
                set(Calendar.HOUR_OF_DAY, timeOfDay.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, timeOfDay.get(Calendar.MINUTE))
                set(Calendar.SECOND, timeOfDay.get(Calendar.SECOND))
                set(Calendar.MILLISECOND, timeOfDay.get(Calendar.MILLISECOND))
            }.timeInMillis
        }
    }
}
