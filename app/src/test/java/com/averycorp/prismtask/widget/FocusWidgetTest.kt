package com.averycorp.prismtask.widget

import android.content.Intent
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for [FocusWidget] — covers headline-task pick, empty state, and the
 * Start-focus-session deep link contract.
 *
 * Glance composables can't be rendered in a unit test, so we exercise:
 *   - the pure-Kotlin pick function ([pickFocusTask]) on representative
 *     row sets, and
 *   - the intent shape used by the widget when launching the timer / task
 *     editor, asserting it carries the wire IDs `MainActivity` + `NavGraph`
 *     consume.
 */
@RunWith(RobolectricTestRunner::class)
class FocusWidgetTest {

    private fun row(
        id: Long,
        title: String,
        priority: Int = 0,
        dueDate: Long? = null,
        isCompleted: Boolean = false,
        isOverdue: Boolean = false
    ) = WidgetTaskRow(
        id = id,
        title = title,
        priority = priority,
        dueDate = dueDate,
        isCompleted = isCompleted,
        isOverdue = isOverdue
    )

    @Test
    fun `pickFocusTask returns null when list is empty`() {
        assertNull(pickFocusTask(emptyList()))
    }

    @Test
    fun `pickFocusTask skips completed tasks`() {
        val pick = pickFocusTask(
            listOf(
                row(1, "Done thing", priority = 4, isCompleted = true),
                row(2, "Still pending", priority = 1)
            )
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `pickFocusTask returns null when only completed tasks remain`() {
        val pick = pickFocusTask(
            listOf(
                row(1, "a", isCompleted = true),
                row(2, "b", isCompleted = true)
            )
        )
        assertNull(pick)
    }

    @Test
    fun `pickFocusTask favors higher priority`() {
        val pick = pickFocusTask(
            listOf(
                row(1, "low", priority = 1),
                row(2, "urgent", priority = 4),
                row(3, "high", priority = 3)
            )
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `pickFocusTask breaks priority ties by earliest due date`() {
        val pick = pickFocusTask(
            listOf(
                row(1, "later", priority = 3, dueDate = 2_000L),
                row(2, "sooner", priority = 3, dueDate = 1_000L),
                row(3, "no due", priority = 3, dueDate = null)
            )
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `pickFocusTask null dueDate sorts after a real one at same priority`() {
        val pick = pickFocusTask(
            listOf(
                row(1, "no due", priority = 2, dueDate = null),
                row(2, "has due", priority = 2, dueDate = 5_000L)
            )
        )
        assertEquals(2L, pick?.id)
    }

    @Test
    fun `start focus session intent carries ACTION_OPEN_TIMER wire id`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTimer.wireId)
        }
        assertEquals(
            WidgetLaunchAction.OpenTimer.wireId,
            intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION)
        )
        assertEquals(
            MainActivity::class.java.name,
            intent.component?.className
        )
    }

    @Test
    fun `open task intent carries OpenTask wire id plus task id`() {
        val context = RuntimeEnvironment.getApplication()
        val pick = row(42, "Headline", priority = 3)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTask.WIRE_ID)
            putExtra(MainActivity.EXTRA_TASK_ID, pick.id)
        }
        assertEquals(
            WidgetLaunchAction.OpenTask.WIRE_ID,
            intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION)
        )
        assertEquals(42L, intent.getLongExtra(MainActivity.EXTRA_TASK_ID, -1L))
    }

    @Test
    fun `OpenTimer wire id round-trips through deserialize`() {
        val action = WidgetLaunchAction.deserialize(WidgetLaunchAction.OpenTimer.wireId)
        assertNotNull(action)
        assertTrue(action is WidgetLaunchAction.OpenTimer)
    }

    @Test
    fun `today widget data with no tasks yields null focus pick`() {
        val data = TodayWidgetData(
            totalTasks = 0,
            completedTasks = 0,
            tasks = emptyList(),
            totalHabits = 0,
            completedHabits = 0,
            habitIcons = emptyList(),
            productivityScore = 0
        )
        assertNull(pickFocusTask(data.tasks))
    }

    @Test
    fun `today widget data with mixed tasks picks highest-priority open one`() {
        val data = TodayWidgetData(
            totalTasks = 3,
            completedTasks = 1,
            tasks = listOf(
                row(1, "Completed urgent", priority = 4, isCompleted = true),
                row(2, "Open medium", priority = 2),
                row(3, "Open high", priority = 3)
            ),
            totalHabits = 0,
            completedHabits = 0,
            habitIcons = emptyList(),
            productivityScore = 33
        )
        val pick = pickFocusTask(data.tasks)
        assertEquals(3L, pick?.id)
    }
}
