package com.averycorp.prismtask.ui.screens.tasklist.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.averycorp.prismtask.core.time.DayBoundary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calendar-midnight bounds of the user's logical day, in epoch millis.
 *
 * `startOfToday` and `startOfTomorrow` mirror the contract used by
 * `TodayViewModel.dayStart`/`dayEnd`: the calendar 00:00 boundaries of
 * the *logical* date (the date returned by
 * [DayBoundary.logicalDate]), so display-side labels classify tasks
 * the same way the SoD-aware Today filter and TaskList grouping do.
 */
internal data class DayBounds(val startOfToday: Long, val startOfTomorrow: Long, val startOfDayAfter: Long) {
    companion object {
        fun calendar(
            now: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault()
        ): DayBounds {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return logical(today, zone)
        }

        fun logical(
            logicalDate: LocalDate,
            zone: ZoneId = ZoneId.systemDefault()
        ): DayBounds = DayBounds(
            startOfToday = logicalDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            startOfTomorrow = logicalDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            startOfDayAfter = logicalDate.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }
}

internal enum class DueDateBucket { PAST, TODAY, TOMORROW, FUTURE }

internal fun classifyDueDate(epochMillis: Long, bounds: DayBounds): DueDateBucket = when {
    epochMillis < bounds.startOfToday -> DueDateBucket.PAST
    epochMillis < bounds.startOfTomorrow -> DueDateBucket.TODAY
    epochMillis < bounds.startOfDayAfter -> DueDateBucket.TOMORROW
    else -> DueDateBucket.FUTURE
}

/**
 * Composition-local SoD-anchored day bounds. Wired in `MainActivity` from
 * `LocalDateFlow.observe(taskBehaviorPreferences.getStartOfDay())` so card
 * labels re-key at every logical-day boundary crossing. Default (when no
 * provider is installed — e.g. in isolated Compose previews or tests) is
 * calendar midnight, which matches the pre-fix behavior.
 */
internal val LocalDayBounds = staticCompositionLocalOf { DayBounds.calendar() }
