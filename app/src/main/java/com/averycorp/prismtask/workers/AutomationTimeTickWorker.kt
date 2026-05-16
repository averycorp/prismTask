package com.averycorp.prismtask.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.averycorp.prismtask.domain.automation.AutomationEvent
import com.averycorp.prismtask.domain.automation.AutomationEventBus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Per-minute self-rescheduling worker that emits an
 * [AutomationEvent.TimeTick] every minute boundary. The engine's matcher
 * checks `tick.hour == trigger.hour && tick.minute == trigger.minute`,
 * so a [com.averycorp.prismtask.domain.automation.AutomationTrigger.TimeOfDay]
 * fires within ~1 minute of its target time on awake devices.
 *
 * Per-minute cadence is achieved with the [DailyResetWorker]-style
 * OneTimeWork chain: each `doWork` re-enqueues the next run aligned to
 * the next minute boundary. WorkManager's minimum periodic interval is
 * 15 minutes, which is why a `PeriodicWorkRequest` cannot deliver this
 * cadence; the chain is the canonical idiom for sub-15-min wake-ups
 * (see also `DailyResetWorker`, `OverloadCheckWorker`,
 * `WeeklyAnalyticsWorker`, etc.).
 *
 * **Doze caveat.** Android Doze rate-limits background work in
 * maintenance windows (~9 min in light Doze, hours in deep Doze). On
 * sleeping devices, rule firings may be deferred to the next maintenance
 * window. This is platform behavior, not a bug; the cadence change
 * delivers <1-min latency only on awake devices.
 *
 */
@HiltWorker
class AutomationTimeTickWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val bus: AutomationEventBus
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val cal = Calendar.getInstance()
        bus.emit(
            AutomationEvent.TimeTick(
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE)
            )
        )
        // Self-reschedule for the next minute boundary. Mirrors
        // DailyResetWorker.doWork() — see § B.2 of the audit doc.
        schedule(appContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "automation_time_tick"

        /**
         * Pure delay-arithmetic helper extracted so the boundary math is
         * unit-testable without WorkManager. Returns the milliseconds
         * from [now] until the next wall-clock minute boundary — always
         * in `(0, 60_000]` so the worker does not re-enqueue with a zero
         * delay (which WorkManager treats as "run immediately"). At
         * `now` exactly on a minute boundary we still wait a full minute
         * — same idiom as [DailyResetWorker.computeNextDelayMs] when
         * `now` lands on the SoD instant.
         *
         * Pattern mirrors `DailyResetWorker.computeNextDelayMs`
         * (minute-cadence Phase 2 § B.2).
         */
        @JvmStatic
        @JvmOverloads
        fun computeNextMinuteBoundaryDelayMs(
            now: Long = System.currentTimeMillis()
        ): Long {
            val msIntoMinute = now % 60_000L
            val msUntilNext = 60_000L - msIntoMinute
            // msUntilNext is naturally in (0, 60_000]: when msIntoMinute
            // is 0 (exactly on a boundary) we return 60_000, never 0.
            return msUntilNext
        }

        /**
         * Schedules the next run for the next wall-clock minute boundary.
         * Replaces any previously-scheduled work under
         * [UNIQUE_WORK_NAME] — including the legacy
         * `PeriodicWorkRequest(15min)` registration from before the
         * cadence change. WorkManager treats unique names as the dedup
         * key regardless of underlying request type, so the swap is
         * transparent on first launch after update.
         */
        @JvmStatic
        @JvmOverloads
        fun schedule(
            context: Context,
            now: Long = System.currentTimeMillis()
        ) {
            val delay = computeNextMinuteBoundaryDelayMs(now)
            val request = OneTimeWorkRequestBuilder<AutomationTimeTickWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
