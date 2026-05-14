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
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.domain.usecase.ProductivityScoreCalculator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Sunday-evening summary notification — "📊 Weekly Score: 78 — 24 tasks
 * completed, 71% habit rate". Computed locally by the existing
 * [ProductivityScoreCalculator] over the last seven days, so no backend
 * round-trip is required.
 *
 * Mirrors [WeeklyReviewWorker]'s scheduling shape but offsets the time
 * to 19:00 (review fires at 20:00) so the two notifications don't
 * collide. Toggleable via the user-facing
 * [NotificationPreferences.weeklyAnalyticsNotificationEnabled] flag.
 */
@HiltWorker
class WeeklyAnalyticsWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notificationPreferences: NotificationPreferences,
    private val taskDao: TaskDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val habitRepository: HabitRepository,
    private val habitCompletionDao: HabitCompletionDao,
    private val productivityScoreCalculator: ProductivityScoreCalculator,
    private val notificationPauseGate: NotificationPauseGate,
    private val clock: Clock = Clock.systemDefaultZone()
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!notificationPreferences.weeklyAnalyticsNotificationEnabled.first()) {
            return Result.success()
        }
        // MH-first G4: pause-all silences weekly analytics summary.
        if (notificationPauseGate.isPausedNow()) return Result.success()

        val zone: ZoneId = clock.zone
        val today = LocalDate.now(clock)
        val weekStart = today.minusDays(6)
        val startMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillisExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val tasks = taskDao.getTasksForAnalyticsRange(startMillis, endMillisExclusive).first()
        val habits = habitRepository.getActiveHabits().first()
        val habitCompletions = habitCompletionDao
            .getAllCompletionsInRange(startMillis, endMillisExclusive - 1).first()
        val taskCompletions = taskCompletionDao
            .getCompletionsInRange(startMillis, endMillisExclusive - 1).first()

        val response = productivityScoreCalculator.compute(
            startDate = weekStart,
            endDate = today,
            zone = zone,
            tasks = tasks,
            activeHabitsCount = habits.size,
            habitCompletions = habitCompletions
        )

        val score = response.averageScore.roundToInt()
        val tasksCompleted = taskCompletions.size
        val habitRate = if (habits.isEmpty()) {
            null
        } else {
            // distinct (habit, day) buckets so two completions of the
            // same habit on one day don't double-count toward the rate
            val distinct = habitCompletions.asSequence()
                .map {
                    val day = java.time.Instant.ofEpochMilli(it.completedDate)
                        .atZone(zone)
                        .toLocalDate()
                    it.habitId to day
                }
                .toSet()
                .size
            val possible = habits.size * 7
            (distinct.toDouble() / possible.toDouble()).coerceIn(0.0, 1.0)
        }

        val title = "Weekly Score: $score"
        val habitFragment = habitRate?.let { ", ${"%.0f".format(it * 100)}% habit rate" }.orEmpty()
        val text = "$tasksCompleted tasks completed$habitFragment"

        postNotification(applicationContext, title = title, text = text)
        return Result.success()
    }

    private fun postNotification(context: Context, title: String, text: String) {
        ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — silent no-op.
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Weekly productivity score summary" }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "prismtask_weekly_analytics"
        private const val CHANNEL_NAME = "Weekly Analytics"
        const val NOTIFICATION_ID = 9520
        const val WORK_NAME = "weekly_analytics"

        /**
         * Schedules the weekly analytics summary. Sunday 19:00 by default
         * — earlier than the [WeeklyReviewWorker] (20:00) so the two
         * Sunday notifications stagger.
         */
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
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
            }
            val delay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<WeeklyAnalyticsWorker>(7, TimeUnit.DAYS)
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
