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
import com.averycorp.prismtask.R
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.remote.api.EveningSummaryRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Fires at the user's preferred evening time (default 8 PM). Generates a
 * non-judgmental, one-sentence summary of the day's accomplishments via
 * Claude Haiku. Only fires if the user completed at least 1 task that day.
 *
 * Tier: Pro+ (AI_EVENING_SUMMARY)
 */
@HiltWorker
class EveningSummaryWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: PrismTaskApi,
    private val taskDao: TaskDao,
    private val habitDao: HabitDao,
    private val completionDao: HabitCompletionDao,
    private val proFeatureGate: ProFeatureGate,
    private val notificationPreferences: NotificationPreferences,
    private val notificationPauseGate: NotificationPauseGate
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_EVENING_SUMMARY)) return Result.success()
        if (!notificationPreferences.eveningSummaryEnabled.first()) return Result.success()
        // MH-first G4: pause-all silences the evening summary.
        if (notificationPauseGate.isPausedNow()) return Result.success()

        return try {
            val now = System.currentTimeMillis()
            val startOfToday = HabitRepository.normalizeToMidnight(now)
            val endOfToday = startOfToday + 24 * 60 * 60 * 1000

            // Get completed tasks today
            val completedTasks = taskDao.getCompletedTasksInRange(startOfToday, endOfToday)
            if (completedTasks.isEmpty()) return Result.success() // Don't send if nothing was done

            val completedTitles = completedTasks.map { it.title }

            // Get remaining incomplete tasks
            val remainingCount = taskDao.getIncompleteTodayCount(endOfToday)

            // Get habit completions today
            val habits = habitDao.getActiveHabitsOnce()
            val todayDate = startOfToday
            var habitsDone = 0
            for (habit in habits) {
                val completions = completionDao.getCompletionsForHabitOnce(habit.id)
                if (completions.any { it.completedDate == todayDate }) {
                    habitsDone++
                }
            }

            val response = api.getEveningSummary(
                EveningSummaryRequest(
                    completedTasks = completedTitles,
                    remainingCount = remainingCount,
                    habitsDone = habitsDone,
                    habitsTotal = habits.size,
                    completedOverdue = false,
                    completedStalled = false
                )
            )

            showNotification(applicationContext, response.summary)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(context: Context, summary: String) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Evening Summary",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Daily evening summary of accomplishments" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Today's Wrap-Up")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — data gathering ran, notification dropped.
        }
    }

    companion object {
        private const val WORK_NAME = "evening_summary_notification"
        private const val CHANNEL_ID = "prismtask_evening_summary"
        private const val NOTIFICATION_ID = 9002

        fun schedule(context: Context, hourOfDay: Int = 20, minute: Int = 0) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }

            val delay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<EveningSummaryWorker>(1, TimeUnit.DAYS)
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
