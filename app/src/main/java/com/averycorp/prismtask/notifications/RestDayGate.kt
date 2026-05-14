package com.averycorp.prismtask.notifications

import android.content.Context
import android.util.Log
import com.averycorp.prismtask.data.repository.RestDayRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Rest Day notification gate (Mental-Health-First audit § G3).
 *
 * On a logical rest day (resolved via Start-of-Day) all non-medication
 * notifications short-circuit before firing. Medication reminders are
 * explicitly **not** routed through this gate — the audit answer to the
 * open question "does marking a day as rest also defer medication
 * reminders?" is **no, medications still fire** (refill cadence breaks if
 * doses skip; that's a safety-of-life concern that overrides the rest-day
 * pause).
 *
 * Why the seam is at notification *firing*, not scheduling: alarms can be
 * registered hours or days in advance, and the user may mark today as a
 * rest day after that scheduling window. Checking at fire time is the
 * only way to honor "rest today" for alarms that were already in flight.
 *
 * Why an EntryPoint instead of Hilt injection: the static
 * [NotificationHelper] object and several BroadcastReceivers don't have
 * a constructor to inject into. Pulling the repository through the
 * SingletonComponent matches the existing
 * [NotificationHelperEntryPoint] pattern in this package.
 */
internal object RestDayGate {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RestDayGateEntryPoint {
        fun restDayRepository(): RestDayRepository
    }

    /**
     * Returns `true` if today (logical, SoD-aware) is marked as a rest
     * day and the calling code is *not* on the medication path.
     *
     * Soft fail: any exception (DB closed, EntryPoint resolution issue,
     * test harness without a full Hilt graph) returns `false` so a
     * scheduler bug never silently suppresses every notification. The
     * trade-off is correct — a stuck-on rest-day flag is much worse for
     * the user than the rest-day gate occasionally no-opping.
     */
    suspend fun shouldSuppress(context: Context): Boolean {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                RestDayGateEntryPoint::class.java
            )
            entryPoint.restDayRepository().isRestDayToday()
        } catch (t: Throwable) {
            Log.w("RestDayGate", "Suppression check failed; falling through.", t)
            false
        }
    }
}
