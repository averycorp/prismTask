package com.averycorp.prismtask.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.domain.usecase.HabitDailyTaskGenerator
import com.averycorp.prismtask.notifications.ProductiveStreakNotifier
import com.averycorp.prismtask.util.DayBoundary
import com.averycorp.prismtask.widget.WidgetUpdateManager
import com.averycorp.prismtask.workers.streak.ProductiveStreakResolver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Wakes up at the user's configured "day start hour" each day. The vast majority
 * of "today" data is computed at query time using [DayBoundary], so as soon as
 * the boundary passes, ViewModels observing day-start preference flows already
 * re-emit. The job of this worker is to:
 *
 *  1. Refresh home screen widgets so they reflect the new day immediately,
 *     even if the app is not in the foreground.
 *  2. Re-schedule itself for the next boundary, since [androidx.work.PeriodicWorkRequest]
 *     does not let you anchor work to an exact wall-clock time.
 *
 * Tasks/habits are not destructively reset — instead, the "today" window
 * advances and previously-completed items naturally fall out of the
 * "today" view, while pending ones become the new day's work.
 */
@HiltWorker
class DailyResetWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val productiveStreakResolver: ProductiveStreakResolver,
    private val productiveStreakNotifier: ProductiveStreakNotifier,
    private val habitDailyTaskGenerator: HabitDailyTaskGenerator
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        // Refresh widgets so the new day's tasks/habits are visible immediately.
        if (BuildConfig.WIDGETS_ENABLED) {
            try {
                widgetUpdateManager.updateAllWidgets()
            } catch (_: Throwable) {
                // Best-effort: don't fail the worker if widget update throws.
            }
        }

        // Spawn a Today task per habit with "Create daily to-do" enabled
        // that's scheduled for the new day. Idempotent — re-runs are safe.
        try {
            habitDailyTaskGenerator.ensureTasksForToday()
        } catch (_: Throwable) {
            // Best-effort: never fail the day-boundary worker on generator failure.
        }

        // Roll the productive-day streak forward for the day that just ended.
        // Piggybacks on this worker (which already fires on SoD) rather than
        // scheduling its own alarm — see audit § "Productive streak rollover"
        // critical implementation rule.
        try {
            val outcome = productiveStreakResolver.resolveYesterday()
            if (outcome.brokenStreakLength > 0) {
                productiveStreakNotifier.notifyBrokenStreak(outcome.brokenStreakLength)
            }
        } catch (_: Throwable) {
            // Best-effort: streak update should never fail the day-boundary worker.
        }

        // Reschedule for the next day boundary using hour + minute.
        val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
        val dayStartMinute = taskBehaviorPreferences.getDayStartMinute().first()
        schedule(appContext, dayStartHour, dayStartMinute)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "daily_reset"

        /**
         * Pure delay-arithmetic helper extracted from [schedule] so the
         * boundary math is unit-testable without WorkManager. Returns the
         * milliseconds from [now] until the next configured boundary
         * crossing — never negative.
         *
         * Per audit `docs/audits/AUTOMATED_EDGE_CASE_TESTING_AUDIT.md`
         * (Tier A2), threading [now] makes this deterministic; production
         * callers continue to use `System.currentTimeMillis()` via the
         * default.
         */
        @JvmStatic
        @JvmOverloads
        fun computeNextDelayMs(
            dayStartHour: Int,
            dayStartMinute: Int = 0,
            now: Long = System.currentTimeMillis()
        ): Long {
            val nextBoundary = DayBoundary.nextBoundary(
                dayStartHour = dayStartHour,
                now = now,
                dayStartMinute = dayStartMinute
            )
            return (nextBoundary - now).coerceAtLeast(0L)
        }

        /**
         * Schedules the next run for the next occurrence of the configured
         * start of day (hour + minute). Replaces any previously scheduled run
         * so a settings change immediately takes effect.
         *
         * The `dayStartMinute` parameter defaults to 0 for back-compat with
         * callers that still only know about the hour.
         */
        @JvmOverloads
        fun schedule(
            context: Context,
            dayStartHour: Int,
            dayStartMinute: Int = 0,
            now: Long = System.currentTimeMillis()
        ) {
            val delay = computeNextDelayMs(dayStartHour, dayStartMinute, now)

            val request = OneTimeWorkRequestBuilder<DailyResetWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
