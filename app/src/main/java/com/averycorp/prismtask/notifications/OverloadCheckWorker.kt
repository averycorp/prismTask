package com.averycorp.prismtask.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.averycorp.prismtask.R
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.BalanceConfig
import com.averycorp.prismtask.domain.usecase.BalanceTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily overload check worker (v1.4.0 V2).
 *
 * Runs once per day via WorkManager. Recomputes the user's balance state
 * and, if still overloaded, fires a low-urgency notification inviting
 * them to open the balance report. Once-per-day is enforced by the
 * WorkManager scheduling constraint in the caller, so the worker just
 * fires the notification unconditionally when the balance state is hot.
 *
 * Respects the `showBalanceBar` toggle as a proxy for "user cares about
 * the feature" — if it's off the worker is a no-op.
 */
@HiltWorker
class OverloadCheckWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskRepository: TaskRepository,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val notificationPreferences: NotificationPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!notificationPreferences.overloadAlertsEnabled.first()) return Result.success()
        val prefs = userPreferencesDataStore.workLifeBalanceFlow.first()
        if (!prefs.showBalanceBar) return Result.success()

        val tasks = taskRepository.getAllTasksOnce()
        val config = BalanceConfig(
            workTarget = prefs.workTarget / 100f,
            personalTarget = prefs.personalTarget / 100f,
            selfCareTarget = prefs.selfCareTarget / 100f,
            healthTarget = prefs.healthTarget / 100f,
            overloadThreshold = prefs.overloadThresholdPct / 100f
        )
        val sod = taskBehaviorPreferences.getStartOfDay().first()
        val balance = BalanceTracker().compute(
            tasks,
            config,
            dayStartHour = sod.hour,
            dayStartMinute = sod.minute
        )

        if (!balance.isOverloaded || balance.totalTracked == 0) {
            return Result.success()
        }

        ensureChannel(applicationContext)
        val workPct = ((balance.currentRatios[com.averycorp.prismtask.domain.model.LifeCategory.WORK] ?: 0f) * 100).toInt()
        val notification = NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Work was $workPct% of this week")
            .setContentText("Tap to see the breakdown across all life categories.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silent no-op.
        }
        return Result.success()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Balance Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Daily work-life balance overload alerts"
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "balance_alerts"
        const val NOTIFICATION_ID = 9401
        const val UNIQUE_WORK_NAME = "overload_check_daily"

        /**
         * Schedules the daily overload check. Aligns the first run with
         * [hourOfDay] (default 4 PM) so the notification lands at a
         * predictable late-afternoon time rather than whenever the app
         * happened to start.
         */
        fun schedule(context: Context, hourOfDay: Int = 16, minute: Int = 0) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val delay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<OverloadCheckWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
