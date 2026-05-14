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
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.CognitiveLoad
import com.averycorp.prismtask.domain.usecase.CognitiveLoadBalanceConfig
import com.averycorp.prismtask.domain.usecase.CognitiveLoadBalanceTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily cognitive-load imbalance check worker.
 *
 * Mirrors [OverloadCheckWorker] but for the orthogonal start-friction
 * dimension. **Bidirectional**: warns the user when *either* side of the
 * load split has run away — too-easy = procrastination via avoidance of
 * hard work; too-hard = burnout via no recovery wins (per
 * `docs/COGNITIVE_LOAD.md` § Descriptive, not prescriptive — except this
 * worker is the prescriptive escape hatch when the visual signal alone
 * isn't enough).
 *
 * Runs once per day via WorkManager. Recomputes the user's load balance
 * state and, if any single tier exceeds the imbalance threshold, fires a
 * single non-judgmental notification inviting the user to open the
 * weekly balance report. Once-per-day is enforced by the WorkManager
 * scheduling constraint in the caller.
 *
 * Respects [NotificationPreferences.overloadAlertsEnabled] as a proxy for
 * "user wants the prescriptive surface" — if it's off the worker is a
 * no-op and the user only sees the visual descriptive bar.
 */
@HiltWorker
class CognitiveLoadOverloadCheckWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskRepository: TaskRepository,
    private val notificationPreferences: NotificationPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!notificationPreferences.overloadAlertsEnabled.first()) return Result.success()

        val tasks = taskRepository.getAllTasksOnce()
        val sod = taskBehaviorPreferences.getStartOfDay().first()
        val balance = CognitiveLoadBalanceTracker().compute(
            tasks,
            CognitiveLoadBalanceConfig(),
            dayStartHour = sod.hour,
            dayStartMinute = sod.minute
        )

        if (balance.totalTracked == 0) return Result.success()

        val easy = balance.currentRatios[CognitiveLoad.EASY] ?: 0f
        val hard = balance.currentRatios[CognitiveLoad.HARD] ?: 0f

        // Bidirectional thresholds: 80% on either side flags the run-away.
        // Stricter than LifeCategory's overload (configurable workTarget +
        // overloadThreshold) because cognitive-load tier targets default to
        // an even split — anything past 80% on a single tier is a clear
        // signal regardless of user-set target ratios.
        val tooEasy = easy >= IMBALANCE_THRESHOLD
        val tooHard = hard >= IMBALANCE_THRESHOLD
        if (!tooEasy && !tooHard) return Result.success()

        val (title, body) = if (tooEasy) {
            "Easy tasks were ${(easy * 100).toInt()}% of this week" to
                "Tap to see the breakdown across cognitive load tiers."
        } else {
            "Hard tasks were ${(hard * 100).toInt()}% of this week" to
                "Tap to see the breakdown across cognitive load tiers."
        }

        ensureChannel(applicationContext)
        val notification = NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
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
                    "Load Balance Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Daily cognitive-load imbalance alerts (bidirectional)"
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "cognitive_load_alerts"
        const val NOTIFICATION_ID = 9402
        const val UNIQUE_WORK_NAME = "cognitive_load_overload_check_daily"

        // Single-tier dominance threshold (0.0 .. 1.0). 80% felt right as a
        // default because the even three-way split is 33%; a single tier
        // hitting 80% is a clear "run-away" rather than normal week-to-week
        // variation.
        const val IMBALANCE_THRESHOLD: Float = 0.80f

        /**
         * Schedules the daily load imbalance check. Aligns the first run with
         * [hourOfDay] (default 4 PM, matching [OverloadCheckWorker]) so the
         * notification lands at a predictable late-afternoon time.
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

            val request = PeriodicWorkRequestBuilder<CognitiveLoadOverloadCheckWorker>(1, TimeUnit.DAYS)
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
