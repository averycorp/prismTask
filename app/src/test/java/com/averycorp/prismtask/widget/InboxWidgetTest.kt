package com.averycorp.prismtask.widget

import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InboxWidget] data shape, deep-link contract, and triage
 * action plumbing. The Glance composables themselves require a running
 * Glance host to test, so we verify the load-bearing wiring — the intent
 * extras that route a row tap to the task detail screen, the parameter
 * bundle that toggles a task from a triage checkbox tap, and the data
 * model the widget renders.
 */
class InboxWidgetTest {

    @Test
    fun `inbox data preserves item fields`() {
        val item = InboxWidgetItem(
            id = 42L,
            title = "Restock olive oil",
            ageLabel = "2h",
            priority = 3
        )
        val data = InboxWidgetData(items = listOf(item))
        assertEquals(1, data.items.size)
        assertEquals(42L, data.items[0].id)
        assertEquals("Restock olive oil", data.items[0].title)
        assertEquals("2h", data.items[0].ageLabel)
        assertEquals(3, data.items[0].priority)
    }

    @Test
    fun `inbox empty when no items captured`() {
        val data = InboxWidgetData(items = emptyList())
        assertTrue(data.items.isEmpty())
    }

    @Test
    fun `inbox config default max items is 5`() {
        val config = WidgetConfigDataStore.InboxConfig()
        assertEquals(5, config.maxItems)
    }

    @Test
    fun `inbox config respects custom max items`() {
        val config = WidgetConfigDataStore.InboxConfig(maxItems = 8)
        assertEquals(8, config.maxItems)
    }

    @Test
    fun `triage action carries task id via shared parameter key`() {
        // The InboxWidget per-row checkbox dispatches
        // ToggleTaskFromWidgetAction with a parameter bundle built by
        // taskIdParams(). The key must match WidgetActionKeys.TASK_ID
        // (the same one TodayWidget and UpcomingWidget use).
        val params = taskIdParams(7L)
        assertEquals(7L, params[WidgetActionKeys.TASK_ID])
        assertEquals("prismtask-widget-task-id", WidgetActionKeys.TASK_ID.name)
    }

    @Test
    fun `open task wire id round trips via WidgetLaunchAction deserialize`() {
        // The row tap-target serializes WidgetLaunchAction.OpenTask.WIRE_ID
        // onto MainActivity.EXTRA_LAUNCH_ACTION and the task id onto
        // MainActivity.EXTRA_TASK_ID. MainActivity rehydrates via
        // WidgetLaunchAction.deserialize.
        val action = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.OpenTask.WIRE_ID,
            taskId = 123L
        )
        assertNotNull(action)
        assertTrue(action is WidgetLaunchAction.OpenTask)
        assertEquals(123L, (action as WidgetLaunchAction.OpenTask).taskId)
    }

    @Test
    fun `open task wire id without task id returns null`() {
        // Guards against a misconfigured deep link silently dropping the
        // user on Today instead of the requested task.
        val action = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.OpenTask.WIRE_ID,
            taskId = null
        )
        assertNull(action)
    }

    @Test
    fun `open inbox wire id round trips for header tap`() {
        // The outer widget click on the InboxWidget surface opens the
        // in-app inbox via WidgetLaunchAction.OpenInbox — the same wire
        // id MainActivity already routes today.
        val action = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.OpenInbox.wireId
        )
        assertEquals(WidgetLaunchAction.OpenInbox, action)
    }

    @Test
    fun `EXTRA_TASK_ID constant is stable wire format`() {
        // Pinned because three widgets (Inbox, Today, Focus) all stamp
        // this key onto deep-link intents and MainActivity reads it back.
        assertEquals("task_id", MainActivity.EXTRA_TASK_ID)
    }
}
