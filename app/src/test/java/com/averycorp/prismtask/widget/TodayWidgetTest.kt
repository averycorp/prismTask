package com.averycorp.prismtask.widget

import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin assertions about the Today widget contract.
 *
 * Composable rendering and `ActionCallback.onAction` execution both
 * require a Glance host that isn't available under JVM unit tests, so we
 * exercise the testable seams: the data class shape consumed by the
 * widget body, the per-instance config defaults that drive the section
 * toggles, the wire-format launch-action identifiers stamped onto tap
 * intents, and the action-parameter bundles wired into the toggle
 * checkbox click target.
 */
class TodayWidgetTest {

    @Test
    fun `today config defaults match widget contract`() {
        // The TodayWidget body consumes these fields directly:
        // showProgress / showTaskList / showHabitSummary gate sections
        // showOverdueBadge gates the warning chip
        // maxTasks caps the rendered task list (1..20)
        val defaults = WidgetConfigDataStore.TodayConfig()
        assertTrue("Progress bar should be on by default", defaults.showProgress)
        assertTrue("Task list should be on by default", defaults.showTaskList)
        assertTrue("Habit summary should be on by default", defaults.showHabitSummary)
        assertTrue("Overdue badge should be on by default", defaults.showOverdueBadge)
        assertEquals(8, defaults.maxTasks)
    }

    @Test
    fun `today widget data preserves productivity score and tasks`() {
        // Header score badge reads productivityScore; the body iterates tasks
        // capped by config.maxTasks.
        val tasks = (1L..3L).map {
            WidgetTaskRow(
                id = it,
                title = "task $it",
                priority = 2,
                dueDate = null,
                isCompleted = false,
                isOverdue = false
            )
        }
        val data = TodayWidgetData(
            totalTasks = 5,
            completedTasks = 2,
            tasks = tasks,
            totalHabits = 3,
            completedHabits = 1,
            habitIcons = listOf("⭐", "🏃"),
            productivityScore = 73
        )
        assertEquals(73, data.productivityScore)
        assertEquals(3, data.tasks.size)
        assertEquals(2, data.habitIcons.size)
    }

    @Test
    fun `today widget empty task list triggers empty-state branch`() {
        // Empty `tasks` list is the trigger for WidgetEmptyState rendering
        // (when config.showTaskList is true).
        val data = TodayWidgetData(
            totalTasks = 0,
            completedTasks = 0,
            tasks = emptyList(),
            totalHabits = 0,
            completedHabits = 0,
            habitIcons = emptyList(),
            productivityScore = 0
        )
        assertTrue(data.tasks.isEmpty())
        assertEquals(0, data.totalTasks)
    }

    @Test
    fun `overdue tasks counted off the row flag the widget body reads`() {
        // TodayWidget computes `overdueCount = data.tasks.count { it.isOverdue }`
        // and uses it to gate the overdue badge — pin that contract here so
        // a future refactor of WidgetTaskRow can't silently break the badge.
        val rows = listOf(
            sampleRow(1, "ontime"),
            sampleRow(2, "late1", isOverdue = true),
            sampleRow(3, "late2", isOverdue = true),
            sampleRow(4, "done", isCompleted = true)
        )
        val overdueCount = rows.count { it.isOverdue }
        assertEquals(2, overdueCount)
    }

    @Test
    fun `view-all footer carries OpenToday launch action wire id`() {
        // Pin the wire id the widget puts on the View All / overdue-badge
        // intents. NavGraph routes off this string; renaming it on either
        // side without the other would silently break the deep link.
        assertEquals("open_today", WidgetLaunchAction.OpenToday.wireId)
    }

    @Test
    fun `task row tap carries OpenTask wire id and round-trips through deserialize`() {
        // The task-row title is wrapped in an intent stamped with
        // WidgetLaunchAction.OpenTask.WIRE_ID + a `task_id` extra. The
        // Activity rehydrates via WidgetLaunchAction.deserialize, so verify
        // the round-trip pinned at the same identity.
        val rehydrated = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.OpenTask.WIRE_ID,
            taskId = 314L
        )
        assertNotNull(rehydrated)
        assertEquals(WidgetLaunchAction.OpenTask(314L), rehydrated)
    }

    @Test
    fun `toggle checkbox action carries task id parameter`() {
        // The checkbox click target on each row binds a
        // `taskIdParams(task.id)` bundle; ToggleTaskFromWidgetAction reads
        // it back via WidgetActionKeys.TASK_ID. Pin that key contract.
        val params = taskIdParams(99L)
        assertEquals(99L, params[WidgetActionKeys.TASK_ID])
    }

    @Test
    fun `today config preserves maxTasks across supported range`() {
        // Widget body's task-cap math is `minOf(config.maxTasks, sizeTierCap)`
        // followed by `coerceIn(1, 20)`; pin that values inside 1..20 round-
        // trip through the data class verbatim so the cap math is stable.
        val low = WidgetConfigDataStore.TodayConfig(maxTasks = 1)
        val mid = WidgetConfigDataStore.TodayConfig(maxTasks = 10)
        val high = WidgetConfigDataStore.TodayConfig(maxTasks = 20)
        assertEquals(1, low.maxTasks)
        assertEquals(10, mid.maxTasks)
        assertEquals(20, high.maxTasks)
    }

    @Test
    fun `disabling sections is preserved through config copy`() {
        // Per-instance config supports independently disabling each of the
        // four boolean sections — verify the data class faithfully carries
        // a "minimum widget" config that just shows the header + score.
        val minimal = WidgetConfigDataStore.TodayConfig(
            showProgress = false,
            showTaskList = false,
            showHabitSummary = false,
            showOverdueBadge = false,
            maxTasks = 3
        )
        assertFalse(minimal.showProgress)
        assertFalse(minimal.showTaskList)
        assertFalse(minimal.showHabitSummary)
        assertFalse(minimal.showOverdueBadge)
        assertEquals(3, minimal.maxTasks)
    }

    private fun sampleRow(
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
}
