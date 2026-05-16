package com.averycorp.prismtask.ui.screens.tasklist.components

import com.averycorp.prismtask.core.time.DayBoundary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Structural repro for the SoD-boundary bug.
 *
 * Setup: wall-clock = 2026-04-29 02:00 UTC, SoD = 04:00.
 * Logical day = 2026-04-28 (we're still before SoD on Apr 29).
 *
 * The bug: `DayBounds.calendar(...)` snaps to calendar midnight, putting the
 * user one logical day ahead. The fix: card labels read SoD-anchored bounds
 * via `DayBounds.logical(logicalDate, ...)`.
 */
class TaskCardDueDateBoundsTest {
    private val zone = ZoneId.of("UTC")
    private val sodHour = 4
    private val sodMinute = 0
    private val now = millis(LocalDate.of(2026, 4, 29), 2, 0)
    private val logicalDate =
        DayBoundary.logicalDate(Instant.ofEpochMilli(now), sodHour, sodMinute, zone)
    private val logicalBounds = DayBounds.logical(logicalDate, zone)
    private val calendarBounds = DayBounds.calendar(now, zone)

    @Test
    fun `task due Apr 28 23-00 classifies as TODAY under logical bounds`() {
        val task = millis(LocalDate.of(2026, 4, 28), 23, 0)
        assertEquals(DueDateBucket.TODAY, classifyDueDate(task, logicalBounds))
    }

    @Test
    fun `task due Apr 29 09-00 classifies as TOMORROW under logical bounds`() {
        val task = millis(LocalDate.of(2026, 4, 29), 9, 0)
        assertEquals(DueDateBucket.TOMORROW, classifyDueDate(task, logicalBounds))
    }

    @Test
    fun `task due Apr 29 00-00 classifies as TOMORROW under logical bounds`() {
        val task = millis(LocalDate.of(2026, 4, 29), 0, 0)
        assertEquals(DueDateBucket.TOMORROW, classifyDueDate(task, logicalBounds))
    }

    @Test
    fun `bug repro -- calendar bounds mislabel Apr 29 09-00 as TODAY`() {
        // This is the buggy pre-fix shape. Calendar midnight thinks Apr 29 is
        // "today" because the wall-clock date is Apr 29, even though SoD=04:00
        // means the user is logically still on Apr 28.
        val task = millis(LocalDate.of(2026, 4, 29), 9, 0)
        assertEquals(DueDateBucket.TODAY, classifyDueDate(task, calendarBounds))
    }

    @Test
    fun `bug repro -- calendar bounds mislabel Apr 28 23-00 as PAST`() {
        // Same shape: calendar bounds say Apr 28 is "yesterday" so the task
        // looks overdue, even though it's still inside the user's logical
        // Today window (logical Apr 28 spans Apr 28 04:00 -> Apr 29 04:00).
        val task = millis(LocalDate.of(2026, 4, 28), 23, 0)
        assertEquals(DueDateBucket.PAST, classifyDueDate(task, calendarBounds))
    }

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
