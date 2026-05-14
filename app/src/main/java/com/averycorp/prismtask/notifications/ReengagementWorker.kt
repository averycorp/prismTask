package com.averycorp.prismtask.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.R
import com.averycorp.prismtask.data.diagnostics.DiagnosticLogger
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.ReengagementRequest
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.RecentMoodSignal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

internal val Context.reengagementStore by preferencesDataStore(name = "reengagement_prefs")

/**
 * Fires as a push notification when the user has been absent for at least
 * [com.averycorp.prismtask.data.preferences.ReengagementConfig.absenceDays]
 * days (default 2). Sends at most
 * [com.averycorp.prismtask.data.preferences.ReengagementConfig.maxNudges]
 * notifications per absence period (default 1) — once the cap is reached,
 * no more nudges fire until the user opens the app and the count resets.
 *
 * Tier: Premium (AI_REENGAGEMENT)
 */
@HiltWorker
class ReengagementWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: PrismTaskApi,
    private val taskDao: TaskDao,
    private val proFeatureGate: ProFeatureGate,
    private val notificationPreferences: NotificationPreferences,
    private val advancedTuningPreferences: AdvancedTuningPreferences,
    private val notificationPauseGate: NotificationPauseGate,
    private val recentMoodSignal: RecentMoodSignal,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_REENGAGEMENT)) return Result.success()
        if (!notificationPreferences.reengagementEnabled.first()) return Result.success()
        // MH-first G4: pause-all silences re-engagement nudges. The
        // counter is intentionally NOT incremented while paused so the
        // user doesn't burn their daily nudge quota during the pause.
        if (notificationPauseGate.isPausedNow()) return Result.success()
        // Mental-Health-First § G7 — re-engagement is the textbook case
        // the audit calls out: a user who logged a 1/5 mood yesterday
        // does not need an "are you still there?" nudge. Silent
        // deferral; the next periodic firing re-evaluates. Like the
        // pause gate above, the daily quota counter is NOT incremented
        // while gated so it isn't burned during a low-mood stretch.
        if (recentMoodSignal.isLowMoodWithin()) {
            diagnosticLogger.info(
                tag = "MoodGate",
                message = "ReengagementWorker skipped — recent low mood within 48h"
            )
            return Result.success()
        }

        val config = advancedTuningPreferences.getReengagementConfig().first()

        val store = applicationContext.reengagementStore
        val prefs = store.data.first()

        val nudgeCount = prefs[KEY_REENGAGEMENT_SENT_COUNT] ?: 0
        if (nudgeCount >= config.maxNudges) return Result.success()

        val lastOpenTime = prefs[KEY_LAST_OPEN_TIME] ?: System.currentTimeMillis()
        val daysSinceOpen = TimeUnit.MILLISECONDS
            .toDays(
                System.currentTimeMillis() - lastOpenTime
            ).toInt()

        if (daysSinceOpen < config.absenceDays) return Result.success()

        return try {
            // Get last completed task title
            val lastCompletedTask = taskDao.getLastCompletedTask()
            val lastTaskTitle = lastCompletedTask?.title

            // Get total pending count (but we won't show it)
            val totalPending = taskDao.getIncompleteTaskCount()

            val response = api.getReengagementNudge(
                ReengagementRequest(
                    daysAbsent = daysSinceOpen,
                    lastTaskTitle = lastTaskTitle,
                    totalPending = totalPending
                )
            )

            showNotification(applicationContext, response.nudge)

            store.edit {
                it[KEY_REENGAGEMENT_SENT_COUNT] = nudgeCount + 1
            }

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(context: Context, nudge: String) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gentle Nudges",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Gentle re-engagement nudges" }
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
            .setContentTitle("PrismTask")
            .setContentText(nudge)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — drop silently.
        }
    }

    companion object {
        private const val WORK_NAME = "reengagement_nudge"
        private const val CHANNEL_ID = "prismtask_reengagement"
        private const val NOTIFICATION_ID = 9003

        private val KEY_REENGAGEMENT_SENT_COUNT = intPreferencesKey("reengagement_sent_count")
        private val KEY_LAST_OPEN_TIME = longPreferencesKey("last_open_time")

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReengagementWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()

            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Call this when the user opens the app to reset the re-engagement flag
         * and record the last open time.
         */
        suspend fun onAppOpened(context: Context) {
            context.reengagementStore.edit {
                it[KEY_REENGAGEMENT_SENT_COUNT] = 0
                it[KEY_LAST_OPEN_TIME] = System.currentTimeMillis()
            }
        }
    }
}
