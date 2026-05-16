package com.averycorp.prismtask.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Repro for the SoD-boundary bug in `QuickRescheduleFormatter`.
 */
class QuickRescheduleFormatterSoDTest {
    private val zone = ZoneId.of("UTC")

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `with SoD 04-00 at 02-00 Apr 29 task at Apr 29 09-00 is Tomorrow`() {
        val now = millis(LocalDate.of(2026, 4, 29), 2, 0)
        val task = millis(LocalDate.of(2026, 4, 29), 9, 0)
        val result = QuickRescheduleFormatter.describe(task, now, sodHour = 4, sodMinute = 0, zone = zone)
        assertEquals("Tomorrow", result)
    }

    @Test
    fun `with SoD 04-00 at 02-00 Apr 29 task at Apr 28 23-00 is Today`() {
        val now = millis(LocalDate.of(2026, 4, 29), 2, 0)
        val task = millis(LocalDate.of(2026, 4, 28), 23, 0)
        val result = QuickRescheduleFormatter.describe(task, now, sodHour = 4, sodMinute = 0, zone = zone)
        assertEquals("Today", result)
    }

    @Test
    fun `bug repro -- with calendar-default SoD task at Apr 29 09-00 mislabels as Today`() {
        // Without SoD plumbed in, the formatter falls back to calendar-day
        // comparison (sod=00:00). This documents the pre-fix shape and proves
        // the back-compat default still matches the legacy behavior.
        val now = millis(LocalDate.of(2026, 4, 29), 2, 0)
        val task = millis(LocalDate.of(2026, 4, 29), 9, 0)
        val result = QuickRescheduleFormatter.describe(task, now, zone = zone)
        assertEquals("Today", result)
    }

    @Test
    fun `null millis returns No Date`() {
        assertEquals("No Date", QuickRescheduleFormatter.describe(null))
    }

    @Test
    fun `with SoD 04-00 task more than two logical days out is formatted MMM d`() {
        val now = millis(LocalDate.of(2026, 4, 29), 2, 0)
        val task = millis(LocalDate.of(2026, 5, 5), 12, 0)
        val result = QuickRescheduleFormatter.describe(task, now, sodHour = 4, sodMinute = 0, zone = zone)
        // MMM d format is locale-sensitive but always non-empty and not "Today"/"Tomorrow".
        assertEquals(false, result == "Today" || result == "Tomorrow" || result == "No Date")
    }
}
