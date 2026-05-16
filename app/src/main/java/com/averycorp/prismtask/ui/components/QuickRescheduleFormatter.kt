package com.averycorp.prismtask.ui.components

import com.averycorp.prismtask.core.time.DayBoundary
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Formats dates for the quick-reschedule snackbar. Extracted so ViewModels can
 * depend on it without pulling in any Compose types.
 *
 * SoD-aware: when [sodHour]/[sodMinute] are passed, "Today"/"Tomorrow" track
 * the user's configured Start of Day rather than raw calendar midnight. The
 * default `sodHour=0, sodMinute=0` collapses to calendar-day comparison and
 * preserves pre-fix behavior for any caller that hasn't yet been plumbed.
 */
object QuickRescheduleFormatter {
    private val formatter = SimpleDateFormat("MMM d", Locale.getDefault())

    /**
     * Returns a short human-readable label for a due date, using "Today" /
     * "Tomorrow" / "No Date" where appropriate and falling back to "MMM d".
     */
    fun describe(
        millis: Long?,
        now: Long = System.currentTimeMillis(),
        sodHour: Int = 0,
        sodMinute: Int = 0,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        if (millis == null) return "No Date"
        val nowInstant = Instant.ofEpochMilli(now)
        val taskInstant = Instant.ofEpochMilli(millis)
        val logicalToday = DayBoundary.logicalDate(nowInstant, sodHour, sodMinute, zone)
        val taskLogicalDate = DayBoundary.logicalDate(taskInstant, sodHour, sodMinute, zone)
        return when (taskLogicalDate) {
            logicalToday -> "Today"
            logicalToday.plusDays(1) -> "Tomorrow"
            else -> formatter.format(Date(millis))
        }
    }
}
