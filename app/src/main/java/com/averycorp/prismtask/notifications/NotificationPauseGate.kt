package com.averycorp.prismtask.notifications

import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.util.DayBoundary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mental-Health-First G4: single seam every non-medication scheduler /
 * receiver / worker consults before firing a notification.
 *
 * The "pause-all" affordance lives on the Today screen as a one-tap
 * "Pause for 1h / 4h / until tomorrow morning" sheet (see
 * `PauseAllNotificationsSheet`). When the user picks an option, we write
 * the expiry instant into [NotificationPreferences.pauseNotificationsUntilEpochMs];
 * everywhere a notification would fire, the calling code calls
 * [isPausedNow] just before posting and short-circuits if `true`.
 *
 * Composability with scheduled quiet hours: this gate is **additive**.
 * `QuietHoursDeferrer` still runs at *schedule* time to push fire instants
 * out of the user's recurring quiet window; this gate runs at *fire*
 * time to silence whatever's already been scheduled when the user just
 * needs a break. Either gate saying "no" drops the notification.
 *
 * **Medication exemption:** medication reminders MUST still fire because
 * the safety cost of a missed dose dominates the comfort win of a
 * pause-all. Medication call sites (`MedicationReminderReceiver`,
 * `MedStepReminderReceiver`, the medication branches of
 * `NotificationHelper`) deliberately bypass this gate. Habit follow-ups
 * piggyback on the medication notification channel for delivery, but
 * are still habit-class notifications and DO consult this gate.
 *
 * Stale expiry values are inert: an `epochMs` in the past compares as
 * "not paused", so we don't need a separate worker to zero it out.
 */
@Singleton
class NotificationPauseGate
@Inject
constructor(
    private val notificationPreferences: NotificationPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) {
    /**
     * Reactive view of "is the user currently paused", combining the
     * stored expiry with a `now()` ticker via [combine]. UI layers that
     * want to flip the bell icon / show a status pill collect from
     * this. Note: this flow only re-emits when the stored value
     * changes; for a clock-driven re-emission, callers should pair with
     * `LocalDateFlow` or similar.
     */
    val pauseUntilFlow: Flow<Long> = notificationPreferences.pauseNotificationsUntilEpochMs

    /**
     * Synchronous one-shot read: are notifications currently paused?
     * Compares the stored expiry against [now]. Defaults to false on
     * any read error (the gate is fail-open — if we can't read the
     * pause state, we'd rather notify than silently lose nudges).
     */
    suspend fun isPausedNow(now: Long = System.currentTimeMillis()): Boolean {
        val until = runCatching { notificationPreferences.getPauseNotificationsUntilEpochMsOnce() }
            .getOrDefault(0L)
        return isPaused(until, now)
    }

    /**
     * One-shot read of the raw expiry instant. UI layers that need
     * to render "Paused until 4:30 PM" call this. Returns the raw
     * stored value (which may be in the past — callers should treat
     * `<= now` as "not paused" via [isPaused]).
     */
    suspend fun pauseUntilOnce(): Long =
        runCatching { notificationPreferences.getPauseNotificationsUntilEpochMsOnce() }
            .getOrDefault(0L)

    /** Pause notifications for [durationMillis] from [now]. */
    suspend fun pauseFor(durationMillis: Long, now: Long = System.currentTimeMillis()) {
        require(durationMillis > 0L) { "durationMillis must be positive" }
        notificationPreferences.setPauseNotificationsUntilEpochMs(now + durationMillis)
    }

    /**
     * Pause until tomorrow morning, using the user's configured
     * Start-of-Day hour. Resolves to the next boundary crossing
     * via [DayBoundary.nextBoundary] so it lines up with how Today /
     * Habits / streaks define "tomorrow".
     */
    suspend fun pauseUntilTomorrowMorning(now: Long = System.currentTimeMillis()) {
        val sod = taskBehaviorPreferences.getStartOfDay().first()
        val target = DayBoundary.nextBoundary(
            dayStartHour = sod.hour,
            now = now,
            dayStartMinute = sod.minute
        )
        notificationPreferences.setPauseNotificationsUntilEpochMs(target)
    }

    /** Clear the current pause immediately ("Resume" action). */
    suspend fun resume() {
        notificationPreferences.clearPauseNotifications()
    }

    companion object {
        /** Pure helper for unit tests + direct comparison. */
        fun isPaused(pauseUntilEpochMs: Long, now: Long): Boolean =
            pauseUntilEpochMs > now
    }
}
