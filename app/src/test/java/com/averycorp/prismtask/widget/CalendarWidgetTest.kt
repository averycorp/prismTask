package com.averycorp.prismtask.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Calendar widget data — covers the [WidgetCalendarEvent]
 * model and the [buildMergedTimeline] task+event merge used by the widget
 * composable. Glance composables and click intents require an
 * instrumentation host, so this suite stays in the pure-Kotlin layer.
 */
class CalendarWidgetTest {
    // --- WidgetCalendarEvent shape ---

    @Test
    fun `calendar event preserves title and time`() {
        val event = WidgetCalendarEvent(
            title = "Team Standup",
            startTime = 1700000000000L,
            endTime = 1700003600000L,
            isAllDay = false,
            calendarColor = 0xFF1976D2.toInt()
        )
        assertEquals("Team Standup", event.title)
        assertEquals(1700000000000L, event.startTime)
        assertEquals(1700003600000L, event.endTime)
        assertFalse(event.isAllDay)
        assertEquals(0xFF1976D2.toInt(), event.calendarColor)
    }

    @Test
    fun `all-day event flag`() {
        val event = WidgetCalendarEvent(
            title = "Holiday",
            startTime = 1700000000000L,
            endTime = 1700086400000L,
            isAllDay = true,
            calendarColor = null
        )
        assertTrue(event.isAllDay)
    }

    @Test
    fun `calendar event with null color`() {
        val event = WidgetCalendarEvent(
            title = "Meeting",
            startTime = 1700000000000L,
            endTime = 1700003600000L,
            isAllDay = false,
            calendarColor = null
        )
        assertEquals(null, event.calendarColor)
    }

    // --- UpcomingWidgetData empty-state coverage ---

    @Test
    fun `empty calendar and empty tasks`() {
        val data = UpcomingWidgetData(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(0, data.today.size)
        assertEquals(0, data.totalCount)
    }

    // --- buildMergedTimeline ordering ---

    @Test
    fun `empty inputs yield empty timeline`() {
        val merged = buildMergedTimeline(emptyList(), emptyList())
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `tasks only timeline is sorted by due date`() {
        val later = sampleTaskRow(1, "later", dueDate = 200L)
        val earlier = sampleTaskRow(2, "earlier", dueDate = 100L)
        val noDue = sampleTaskRow(3, "no due", dueDate = null)
        val merged = buildMergedTimeline(listOf(later, earlier, noDue), emptyList())
        assertEquals(3, merged.size)
        // earlier (100) → later (200) → no-due (Long.MAX_VALUE)
        val titles = merged.map { (it as TimelineItem.Task).row.title }
        assertEquals(listOf("earlier", "later", "no due"), titles)
    }

    @Test
    fun `events only timeline is sorted by start time`() {
        val ev1 = WidgetCalendarEvent("Late Event", 500L, 600L, false, null)
        val ev2 = WidgetCalendarEvent("Early Event", 100L, 200L, false, null)
        val merged = buildMergedTimeline(emptyList(), listOf(ev1, ev2))
        assertEquals(2, merged.size)
        val titles = merged.map { (it as TimelineItem.Event).event.title }
        assertEquals(listOf("Early Event", "Late Event"), titles)
    }

    @Test
    fun `merged timeline interleaves tasks and events chronologically`() {
        val task9am = sampleTaskRow(1, "Standup task", dueDate = 900L)
        val task2pm = sampleTaskRow(2, "Code review", dueDate = 1400L)
        val event10am = WidgetCalendarEvent("Sync", 1000L, 1100L, false, null)
        val event3pm = WidgetCalendarEvent("Demo", 1500L, 1600L, false, null)
        val merged = buildMergedTimeline(
            listOf(task9am, task2pm),
            listOf(event10am, event3pm)
        )
        assertEquals(4, merged.size)
        val titles = merged.map {
            when (it) {
                is TimelineItem.Task -> it.row.title
                is TimelineItem.Event -> it.event.title
            }
        }
        assertEquals(listOf("Standup task", "Sync", "Code review", "Demo"), titles)
    }

    @Test
    fun `all-day events with start at midnight float to top of timeline`() {
        val midnightAllDay = WidgetCalendarEvent("Holiday", 0L, 86400000L, true, null)
        val taskNoon = sampleTaskRow(1, "Lunch", dueDate = 43200000L)
        val merged = buildMergedTimeline(listOf(taskNoon), listOf(midnightAllDay))
        assertEquals(2, merged.size)
        assertTrue(merged[0] is TimelineItem.Event)
        assertTrue(merged[1] is TimelineItem.Task)
    }

    @Test
    fun `tasks without due date sink to bottom even with later events`() {
        val noDue = sampleTaskRow(1, "Inbox task", dueDate = null)
        val ev = WidgetCalendarEvent("End-of-day sync", 1700000000000L, 1700003600000L, false, null)
        val merged = buildMergedTimeline(listOf(noDue), listOf(ev))
        assertEquals(2, merged.size)
        // Long.MAX_VALUE for no-due task sorts after any real timestamp
        assertTrue(merged[0] is TimelineItem.Event)
        assertTrue(merged[1] is TimelineItem.Task)
    }

    @Test
    fun `merged timeline preserves task row identity for click routing`() {
        val task = sampleTaskRow(42, "Original", dueDate = 100L)
        val merged = buildMergedTimeline(listOf(task), emptyList())
        val itemTask = merged.single() as TimelineItem.Task
        // assertSame guarantees the widget composable threads the same row
        // through to TaskTimelineRow — required for the task_id deep link
        // to reference the right task.
        assertSame(task, itemTask.row)
        assertEquals(42L, itemTask.row.id)
    }

    @Test
    fun `event row carries original color through merge`() {
        val color = 0xFFE91E63.toInt()
        val ev = WidgetCalendarEvent("Pink Cal", 1000L, 2000L, false, color)
        val merged = buildMergedTimeline(emptyList(), listOf(ev))
        val itemEvent = merged.single() as TimelineItem.Event
        assertEquals(color, itemEvent.event.calendarColor)
    }

    @Test
    fun `sortTime falls back to MAX_VALUE for null due date`() {
        val noDue = sampleTaskRow(1, "no due", dueDate = null)
        val itemTask = TimelineItem.Task(noDue)
        assertEquals(Long.MAX_VALUE, itemTask.sortTime)
    }

    @Test
    fun `sortTime for event equals its start time`() {
        val ev = WidgetCalendarEvent("Sync", 1700000000000L, 1700003600000L, false, null)
        val itemEvent = TimelineItem.Event(ev)
        assertEquals(1700000000000L, itemEvent.sortTime)
    }

    // --- Deep link intent extra ---

    @Test
    fun `widget date extra key is namespaced and stable`() {
        // Pin the wire key so launcher-installed widgets keep working after
        // upgrades — Glance intents survive app reinstall as PendingIntents
        // and a key rename would silently break the date deep link.
        assertEquals(
            "com.averycorp.prismtask.WIDGET_DATE_EPOCH_MILLIS",
            EXTRA_DATE_EPOCH_MILLIS
        )
    }

    // --- Helpers ---

    private fun sampleTaskRow(
        id: Long,
        title: String,
        priority: Int = 0,
        dueDate: Long? = null,
        isCompleted: Boolean = false,
        isOverdue: Boolean = false
    ): WidgetTaskRow = WidgetTaskRow(
        id = id,
        title = title,
        priority = priority,
        dueDate = dueDate,
        isCompleted = isCompleted,
        isOverdue = isOverdue
    )
}
