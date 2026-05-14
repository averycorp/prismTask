package com.averycorp.prismtask.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Aggregated counts for the weekly task summary. Kept separate from the
 * notification-building logic so [WeeklyTaskSummaryCalculator] can be
 * exercised in unit tests without a WorkManager harness.
 */
data class WeeklyTaskSummaryData(
    val completedCount: Int,
    val overdueClearedCount: Int,
    val stillOpenCount: Int
)

/**
 * Weekly task summary notification. Sibling of
 * [WeeklyHabitSummaryWorker] — runs once a week at Sunday 7:30 PM local
 * (a 30-minute offset after the habit summary) and posts a short recap
 * of task activity: completions this week, overdue-cleared count, and
 * the running open-work count.
 *
 * Reads exclusively from [TaskDao] — no Room schema changes. The
 * [CHANNEL_ID] is new (`prismtask_weekly_task_summary`) so users can
 * silence task summaries independently of habit summaries via OS
 * channel settings, and first-run scheduling is seeded by
 * [WeeklyTaskSummaryMigration] so existing installs pick it up on next
 * app start.
 */
@HiltWorker
class WeeklyTaskSummaryWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskDao: TaskDao,
    private val notificationPreferences: NotificationPreferences,
    private val notificationPauseGate: NotificationPauseGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (!notificationPreferences.weeklyTaskSummaryEnabled.first()) return Result.success()
        // MH-first G4: pause-all silences weekly task summary.
        if (notificationPauseGate.isPausedNow()) return Result.success()
        val data = WeeklyTaskSummaryCalculator.generateWeeklySummary(taskDao)
        showNotification(applicationContext, data)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    private fun showNotification(context: Context, data: WeeklyTaskSummaryData) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Weekly task summary" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = formatBody(data)

        val notification = NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Your Task Week in Review")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — drop silently.
        }
    }

    companion object {
        const val CHANNEL_ID = "prismtask_weekly_task_summary"
        private const val CHANNEL_NAME = "Weekly Task Summary"
        private const val NOTIFICATION_ID = 9998

        /**
         * Distinct from [WeeklyHabitSummaryWorker.WORK_NAME] so the two
         * periodic jobs coexist — WorkManager keys unique periodic work
         * by this name.
         */
        const val WORK_NAME = "weekly_task_summary"

        internal fun formatBody(data: WeeklyTaskSummaryData): String =
            "You finished ${data.completedCount} tasks this week, " +
                "${data.overdueClearedCount} overdue cleared, " +
                "${data.stillOpenCount} still open"

        fun schedule(
            context: Context,
            dayOfWeek: Int = Calendar.SUNDAY,
            hourOfDay: Int = 19,
            minute: Int = 30
        ) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, dayOfWeek)
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
            }

            val delay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<WeeklyTaskSummaryWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/**
 * Pure data aggregation for the weekly task summary. Split out from
 * [WeeklyTaskSummaryWorker] so unit tests can exercise the counting
 * logic against a mocked [TaskDao] without needing a WorkManager test
 * harness.
 *
 * The week runs from the most recent Monday 00:00 local to the current
 * instant (Sunday evening when fired on schedule). "Overdue cleared" =
 * completed this week with a due date that landed before the completion
 * timestamp. "Still open" = the top-level, non-archived incomplete task
 * count as of the report time (same shape as
 * [TaskDao.getIncompleteTaskCount]).
 */
internal object WeeklyTaskSummaryCalculator {
    suspend fun generateWeeklySummary(taskDao: TaskDao): WeeklyTaskSummaryData {
        val now = System.currentTimeMillis()
        val weekStart = mostRecentMondayMidnight(now)
        val completed = taskDao.getCompletedTasksInRange(weekStart, now)
        val overdueCleared = completed.count { task -> wasOverdueAtCompletion(task) }
        val stillOpen = taskDao.getIncompleteTaskCount()
        return WeeklyTaskSummaryData(
            completedCount = completed.size,
            overdueClearedCount = overdueCleared,
            stillOpenCount = stillOpen
        )
    }

    internal fun mostRecentMondayMidnight(now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Calendar.MONDAY = 2, SUNDAY = 1, so rollback is (dow + 5) % 7.
        val rollback = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        cal.add(Calendar.DAY_OF_YEAR, -rollback)
        return cal.timeInMillis
    }

    private fun wasOverdueAtCompletion(task: TaskEntity): Boolean {
        val due = task.dueDate ?: return false
        // completed_at is null on rare legacy rows; fall back to
        // updated_at which markCompleted() sets to the same timestamp.
        val completedAt = task.completedAt ?: task.updatedAt
        return due < completedAt
    }
}
