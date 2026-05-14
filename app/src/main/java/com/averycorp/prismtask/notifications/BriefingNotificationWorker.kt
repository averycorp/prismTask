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
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.remote.api.DailyBriefingRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.repository.RestDayRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

@HiltWorker
class BriefingNotificationWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: PrismTaskApi,
    private val proFeatureGate: ProFeatureGate,
    private val notificationPreferences: NotificationPreferences,
    private val notificationPauseGate: NotificationPauseGate,
    private val restDayRepository: RestDayRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_BRIEFING)) return Result.success()
        if (!notificationPreferences.dailyBriefingEnabled.first()) return Result.success()
        // MH-first G4: pause-all silences the daily briefing.
        if (notificationPauseGate.isPausedNow()) return Result.success()
        // Rest-day suppression (MH-First audit § G3). Daily briefing /
        // digest is a non-medication notification — pause for the user's
        // logical rest day.
        if (restDayRepository.isRestDayToday()) return Result.success()

        return try {
            val response = api.getDailyBriefing(DailyBriefingRequest())
            val taskCount = response.suggestedOrder.size
            showBriefingNotification(
                applicationContext,
                dayType = response.dayType,
                taskCount = taskCount
            )
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showBriefingNotification(context: Context, dayType: String, taskCount: Int) {
        val channelId = "prismtask_briefing"
        val channel = NotificationChannel(
            channelId,
            "Daily Briefing",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Morning briefing notifications"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_screen", "daily_briefing")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Good Morning")
            .setContentText(
                if (taskCount <=
                    1
                ) {
                    "You've got one thing today. Start whenever you're ready."
                } else {
                    "You've got a few things today. Start with just one."
                }
            ).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // POST_NOTIFICATIONS may be denied on API 33+; let the worker finish
        // its data-gathering run and silently drop the notification rather
        // than crashing the worker.
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val WORK_NAME = "daily_briefing_notification"
        private const val NOTIFICATION_ID = 9001

        fun schedule(context: Context, hourOfDay: Int = 8, minute: Int = 0) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }

            val delay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<BriefingNotificationWorker>(1, TimeUnit.DAYS)
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
