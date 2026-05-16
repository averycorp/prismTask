package com.averycorp.prismtask.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for [EisenhowerWidget] surface logic.
 *
 * Covers:
 *  - Quadrant headers + count rollup match [EisenhowerWidgetData.total];
 *  - [EisenhowerQuadrantSummary.topTaskId] surfaces through the data class
 *    so [ToggleTaskFromWidgetAction] can be wired on LARGE size;
 *  - Density rule: the LARGE_THRESHOLD constant must match the LARGE
 *    size bucket so MEDIUM cells stay checkbox-free;
 *  - [dueDateLabel] thresholds for the top-task hint (Today / Tmrw /
 *    relative future / overdue);
 *  - Empty-quadrant fallback (`topTaskTitle` null → no id surfaced).
 */
class EisenhowerWidgetTest {

    @Test
    fun `total sums each quadrant count`() {
        val data = EisenhowerWidgetData(
            q1 = EisenhowerQuadrantSummary(3, "Pay rent"),
            q2 = EisenhowerQuadrantSummary(5, "Plan trip"),
            q3 = EisenhowerQuadrantSummary(2, "Reply email"),
            q4 = EisenhowerQuadrantSummary(4, null)
        )
        assertEquals(14, data.total)
    }

    @Test
    fun `quadrant summary carries top task id when present`() {
        val q = EisenhowerQuadrantSummary(
            count = 3,
            topTaskTitle = "Pay rent",
            topTaskPriority = 4,
            topTaskDueDate = 1_700_000_000_000L,
            topTaskId = 42L
        )
        assertEquals(42L, q.topTaskId)
        assertEquals("Pay rent", q.topTaskTitle)
        assertEquals(4, q.topTaskPriority)
    }

    @Test
    fun `empty quadrant surfaces null id`() {
        val q = EisenhowerQuadrantSummary(count = 0, topTaskTitle = null)
        assertNull(q.topTaskId)
        assertNull(q.topTaskTitle)
        assertNull(q.topTaskPriority)
        assertNull(q.topTaskDueDate)
    }

    @Test
    fun `large threshold matches the documented LARGE bucket`() {
        // Per density rule from WIDGET_TAB_PARITY_AUDIT.md § 2.2, the
        // per-quadrant checkbox renders only when size.width >=
        // LARGE_THRESHOLD. Lock the threshold so a future bump can't
        // silently make MEDIUM checkbox-bearing.
        assertEquals(350.dp, EisenhowerWidget.LARGE_THRESHOLD)
    }

    @Test
    fun `due date label tags overdue tasks`() {
        val now = 1_700_000_000_000L
        val twoDaysAgo = now - 2 * 24 * 60 * 60 * 1000L
        val label = dueDateLabel(twoDaysAgo, now = now)
        assertTrue(label.overdue)
        assertEquals("2d ago", label.text)
    }

    @Test
    fun `due date label reads Today for same day`() {
        val now = 1_700_000_000_000L
        val label = dueDateLabel(now + 10 * 60 * 1000L, now = now)
        assertEquals("Today", label.text)
        assertFalse(label.overdue)
    }

    @Test
    fun `due date label reads Tmrw for tomorrow`() {
        val now = 1_700_000_000_000L
        val tomorrow = now + 30L * 60 * 60 * 1000L // 30h ahead -> 1 day bucket
        val label = dueDateLabel(tomorrow, now = now)
        assertEquals("Tmrw", label.text)
    }

    @Test
    fun `due date label collapses to Nd in future`() {
        val now = 1_700_000_000_000L
        val fiveDaysAhead = now + 5L * 24 * 60 * 60 * 1000L + 60 * 1000L
        val label = dueDateLabel(fiveDaysAhead, now = now)
        assertEquals("5d", label.text)
        assertFalse(label.overdue)
    }

    @Test
    fun `task id params round-trip through ActionParameters`() {
        // Wired contract: the LARGE-only checkbox fires
        // [ToggleTaskFromWidgetAction] with [taskIdParams]. Confirm the
        // helper produces the key the action reads.
        val params = taskIdParams(99L)
        assertNotNull(params[WidgetActionKeys.TASK_ID])
        assertEquals(99L, params[WidgetActionKeys.TASK_ID])
    }
}
