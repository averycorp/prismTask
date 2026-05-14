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
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.toCalendarDayOfWeek
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Pure-data view of the week's habit completions, kept separate from the
 * notification-building logic so [doWork] can assemble the data once and
 * hand it off to [showNotification] (which pokes the
 * [NotificationManager]).
 */
data class WeeklySummaryData(
    val totalHabits: Int,
    val totalCompletions: Int,
    val completionRate: Float,
    val bestHabit: String?,
    val worstHabit: String?
)

/**
 * Weekly habit summary notification. Runs once a week (Sunday 7 PM
 * local by default) and posts a short recap of the user's habit
 * completions.
 *
 * Previously this lived as a `@Singleton` helper (`WeeklyHabitSummary`)
 * delegated to by an outer `WeeklySummaryWorker`. The helper-inside-
 * worker pattern was a vestigial split with no task-summary content to
 * justify it, so v1.4.0 promoted the helper into a proper worker and
 * deleted the intermediate wrapper. The unique work name
 * ("weekly_habit_summary") and the preference key
 * ([NotificationPreferences.weeklySummaryEnabled]) and the channel ID
 * ([CHANNEL_ID]) all stay as-is so existing scheduled work, persisted
 * toggles, and user-customized OS channel settings continue to apply.
 *
 * Task-summary sibling now lives in [WeeklyTaskSummaryWorker] (added
 * in v1.4.38). The two workers share channel styling but have
 * independent unique work names, OS channels, and preference toggles.
 */
@HiltWorker
class WeeklyHabitSummaryWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val habitDao: HabitDao,
    private val completionDao: HabitCompletionDao,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val notificationPreferences: NotificationPreferences,
    private val notificationPauseGate: NotificationPauseGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (!notificationPreferences.weeklySummaryEnabled.first()) return Result.success()
        // MH-first G4: pause-all silences weekly habit summary.
        if (notificationPauseGate.isPausedNow()) return Result.success()
        val data = WeeklyHabitSummaryCalculator.generateWeeklySummary(
            habitDao = habitDao,
            completionDao = completionDao,
            taskBehaviorPreferences = taskBehaviorPreferences
        )
        if (data.totalHabits > 0) {
            showNotification(applicationContext, data)
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    private fun showNotification(context: Context, data: WeeklySummaryData) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Weekly habit summary" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
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

        val body = buildString {
            append("This week you finished ${data.totalCompletions} things.")
            data.bestHabit?.let { append(" Here's what went well: $it.") }
        }

        val notification = NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Your Week in Review")
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
        // Channel ID stays `prismtask_weekly_summary` (NOT _habit_) so
        // existing users who customized OS channel settings don't lose
        // them across the rename. Only CHANNEL_NAME changes.
        const val CHANNEL_ID = "prismtask_weekly_summary"
        private const val CHANNEL_NAME = "Weekly Habit Summary"
        private const val LEGACY_CHANNEL_ID = "averytask_weekly_summary"
        private const val NOTIFICATION_ID = 9999

        /**
         * Unique work name preserved from the pre-rename worker so the
         * WorkManager DB row is overwritten (UPDATE policy) rather than
         * leaving a stale row pointing at the deleted class.
         */
        const val WORK_NAME = "weekly_habit_summary"

        fun schedule(
            context: Context,
            dayOfWeek: Int = Calendar.SUNDAY,
            hourOfDay: Int = 19,
            minute: Int = 0
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

            val request = PeriodicWorkRequestBuilder<WeeklyHabitSummaryWorker>(7, TimeUnit.DAYS)
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
 * Pure data aggregation for the weekly habit summary. Split out from
 * [WeeklyHabitSummaryWorker] so unit tests can exercise the counting
 * and ranking logic against mocked DAOs without needing a WorkManager
 * test harness. Must stay stateless — the worker constructs its data
 * by calling [generateWeeklySummary] and then hands the result to its
 * notification-posting method.
 */
internal object WeeklyHabitSummaryCalculator {
    suspend fun generateWeeklySummary(
        habitDao: HabitDao,
        completionDao: HabitCompletionDao,
        taskBehaviorPreferences: TaskBehaviorPreferences
    ): WeeklySummaryData {
        val habits = habitDao.getActiveHabitsOnce()
        val calendarDow = taskBehaviorPreferences.getFirstDayOfWeek().first().toCalendarDayOfWeek()
        val weekStart = HabitRepository.getWeekStart(
            HabitRepository.normalizeToMidnight(System.currentTimeMillis()),
            calendarDow
        )
        val weekEnd = HabitRepository.getWeekEnd(
            HabitRepository.normalizeToMidnight(System.currentTimeMillis()),
            calendarDow
        )

        var totalCompletions = 0
        var bestName: String? = null
        var bestCount = -1
        var worstName: String? = null
        var worstCount = Int.MAX_VALUE

        for (habit in habits) {
            val completions = completionDao.getCompletionsForHabitOnce(habit.id)
            val weekCount = completions.count { it.completedDate in weekStart..weekEnd }
            totalCompletions += weekCount

            if (weekCount > bestCount) {
                bestCount = weekCount
                bestName = habit.name
            }
            if (weekCount < worstCount) {
                worstCount = weekCount
                worstName = habit.name
            }
        }

        val totalPossible = habits.sumOf { it.targetFrequency * 7 }
        val rate = if (totalPossible > 0) totalCompletions.toFloat() / totalPossible else 0f

        return WeeklySummaryData(
            totalHabits = habits.size,
            totalCompletions = totalCompletions,
            completionRate = rate,
            bestHabit = bestName,
            worstHabit = worstName
        )
    }
}
