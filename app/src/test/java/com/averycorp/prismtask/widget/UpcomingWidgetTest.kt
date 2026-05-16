package com.averycorp.prismtask.widget

import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-tests for [UpcomingWidget]. Glance composables can't run on the JVM,
 * so these tests cover the data-shape invariants the widget body relies on:
 *
 *  - Section grouping (Today / Tomorrow / This Week) via [UpcomingWidgetData]
 *    fields the widget renders as section headers.
 *  - Empty state — totalCount == 0 triggers the "Nothing Upcoming" branch.
 *  - Row toggle action carries the correct task id through
 *    [taskIdParams] / [WidgetActionKeys.TASK_ID].
 *  - Row click opens task detail — the deep-link wire id matches
 *    [WidgetLaunchAction.OpenTask].
 */
class UpcomingWidgetTest {

    private fun row(
        id: Long,
        title: String,
        priority: Int = 0,
        isCompleted: Boolean = false,
        isOverdue: Boolean = false
    ) = WidgetTaskRow(
        id = id,
        title = title,
        priority = priority,
        dueDate = null,
        isCompleted = isCompleted,
        isOverdue = isOverdue
    )

    @Test
    fun `section grouping exposes today tomorrow and this-week buckets`() {
        val data = UpcomingWidgetData(
            overdue = emptyList(),
            today = listOf(row(1, "Today task")),
            tomorrow = listOf(row(2, "Tomorrow task"), row(3, "Other tomorrow")),
            dayAfter = listOf(row(4, "Week task"))
        )
        // The widget renders these three buckets as section headers; verify
        // the data class exposes them independently so the section-grouped
        // layout can read them without merging.
        assertEquals(1, data.today.size)
        assertEquals(2, data.tomorrow.size)
        assertEquals(1, data.dayAfter.size)
        assertEquals(4, data.totalCount)
    }

    @Test
    fun `empty state triggers when no upcoming tasks in any bucket`() {
        val data = UpcomingWidgetData(emptyList(), emptyList(), emptyList(), emptyList())
        // totalCount == 0 is the branch that swaps the section list for
        // WidgetEmptyState("Nothing Upcoming") in the widget body.
        assertEquals(0, data.totalCount)
        assertTrue(data.today.isEmpty())
        assertTrue(data.tomorrow.isEmpty())
        assertTrue(data.dayAfter.isEmpty())
        assertTrue(data.overdue.isEmpty())
    }

    @Test
    fun `row toggle action carries correct task id`() {
        // Each row's checkbox dispatches ToggleTaskFromWidgetAction with a
        // parameter bundle keyed by WidgetActionKeys.TASK_ID. Verify the
        // helper produces a bundle the action callback can read back.
        val task = row(id = 4242L, title = "Toggle me")
        val params = taskIdParams(task.id)
        val readBack = params[WidgetActionKeys.TASK_ID]
        assertNotNull(readBack)
        assertEquals(4242L, readBack)
    }

    @Test
    fun `row click opens task detail deep link`() {
        // The widget stamps WidgetLaunchAction.OpenTask.WIRE_ID + task id onto
        // the intent it hands to actionStartActivity. Verify the sealed wire
        // contract rehydrates the same OpenTask with the same id.
        val taskId = 1234L
        val rehydrated = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.OpenTask.WIRE_ID,
            taskId = taskId
        )
        assertTrue(rehydrated is WidgetLaunchAction.OpenTask)
        assertEquals(taskId, (rehydrated as WidgetLaunchAction.OpenTask).taskId)
    }

    @Test
    fun `open-task wire id without task id rehydrates to null`() {
        // Defensive: if the widget ever stamps OpenTask without an id,
        // deserialize must return null rather than a malformed action.
        val rehydrated = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.OpenTask.WIRE_ID,
            taskId = null
        )
        assertNull(rehydrated)
    }

    @Test
    fun `overdue tasks surface in dedicated bucket separate from sections`() {
        // The overdue banner renders above the section list; overdue rows
        // must not leak into the today/tomorrow/dayAfter buckets.
        val overdue = listOf(row(1, "Past due", isOverdue = true))
        val data = UpcomingWidgetData(
            overdue = overdue,
            today = listOf(row(2, "Today")),
            tomorrow = emptyList(),
            dayAfter = emptyList()
        )
        assertEquals(1, data.overdue.size)
        assertTrue(data.overdue.first().isOverdue)
        assertEquals(2, data.totalCount)
    }

    @Test
    fun `completed tasks remain in their bucket with isCompleted flag`() {
        // The widget renders a strikethrough row when isCompleted is true;
        // the data shape carries the flag rather than filtering completed
        // rows out (so the row tap can flip them back).
        val mixed = listOf(
            row(1, "Done", isCompleted = true),
            row(2, "Not done")
        )
        val data = UpcomingWidgetData(
            overdue = emptyList(),
            today = mixed,
            tomorrow = emptyList(),
            dayAfter = emptyList()
        )
        assertEquals(2, data.today.size)
        assertTrue(data.today.first().isCompleted)
    }
}
