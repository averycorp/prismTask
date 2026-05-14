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
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.domain.usecase.WeeklyReviewGenerationOutcome
import com.averycorp.prismtask.domain.usecase.WeeklyReviewGenerator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Fires Sunday evening and generates an AI-backed weekly review for the
 * just-ended week. Runs AFTER the existing habit summary (19:00) and the
 * upcoming task summary (19:30) so the three notifications don't collide.
 *
 * Mirrors [WeeklyHabitSummaryWorker]'s scheduling pattern: periodic work,
 * Sunday-aligned initial delay, [ExistingPeriodicWorkPolicy.UPDATE] under a
 * stable unique work name. The generator is responsible for Pro gating,
 * aggregation, API call, and persistence; this worker just orchestrates
 * the user-visible pieces (preference gate, notification post).
 */
@HiltWorker
class WeeklyReviewWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationPreferences: NotificationPreferences,
    private val weeklyReviewGenerator: WeeklyReviewGenerator,
    private val notificationPauseGate: NotificationPauseGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!notificationPreferences.weeklyReviewAutoGenerateEnabled.first()) {
            return Result.success()
        }

        return when (val outcome = weeklyReviewGenerator.generateReview()) {
            is WeeklyReviewGenerationOutcome.Generated -> {
                // MH-first G4: pause-all silences the weekly review nudge,
                // but the review itself is still generated and persisted so
                // the user can read it whenever they un-pause.
                if (notificationPreferences.weeklyReviewNotificationEnabled.first() &&
                    !notificationPauseGate.isPausedNow()
                ) {
                    postNotification(applicationContext)
                }
                Result.success()
            }
            // NoActivity, NotEligible: nothing to do this week, don't
            // retry and don't notify. WorkManager will pick us up again
            // next Sunday via the periodic schedule.
            WeeklyReviewGenerationOutcome.NoActivity,
            WeeklyReviewGenerationOutcome.NotEligible -> Result.success()

            // Transient backend failure — let WorkManager retry. Default
            // exponential backoff (30s → ~5h) naturally caps attempts
            // well before next Sunday's fresh run.
            is WeeklyReviewGenerationOutcome.BackendUnavailable -> Result.retry()

            // Unexpected DB / serialization failure — also retry; if it
            // persists across the week, the next Sunday run gets a
            // clean shot.
            is WeeklyReviewGenerationOutcome.Error -> {
                if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
            }
        }
    }

    private fun postNotification(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Your weekly reflection" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WEEKLY_REVIEWS, true)
        }
        val tapPending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Your Weekly Review Is Ready")
            .setContentText("Tap to read this week's recap and plan ahead.")
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
        const val CHANNEL_ID = "prismtask_weekly_review"
        private const val CHANNEL_NAME = "Weekly Review"
        private const val NOTIFICATION_ID = 9010
        const val WORK_NAME = "weekly_review"
        const val EXTRA_OPEN_WEEKLY_REVIEWS = "open_weekly_reviews"

        /** Cap transient-error retries. Periodic re-run every 7 days is
         *  the real backstop; short-horizon retries just smooth transient
         *  network hiccups. */
        private const val MAX_ATTEMPTS = 3

        fun schedule(
            context: Context,
            dayOfWeek: Int = Calendar.SUNDAY,
            hourOfDay: Int = 20,
            minute: Int = 0
        ) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, dayOfWeek)
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
            }

            val delay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<WeeklyReviewWorker>(7, TimeUnit.DAYS)
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
