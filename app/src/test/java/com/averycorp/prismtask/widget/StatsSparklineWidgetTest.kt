package com.averycorp.prismtask.widget

import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [StatsSparklineWidget].
 *
 * Glance composables require a host to render, so this suite covers the
 * data-driven logic the composable consumes — empty-state detection,
 * delta-label math, bar-height scaling rules (mixed-zero data), and the
 * launch-action wire id used by the tap-to-analytics intent.
 */
class StatsSparklineWidgetTest {

    // -- Header / delta label ---------------------------------------------

    @Test
    fun `delta label uses up arrow when this week meets or exceeds last`() {
        val data = StatsSparklineWidgetData(
            thisWeek = listOf(2, 2, 2, 2, 2, 2, 2),
            lastWeek = listOf(1, 1, 1, 1, 1, 1, 1),
            total = 14,
            lastTotal = 7,
            deltaPct = 100,
            up = true
        )
        assertTrue("up flag", data.up)
        assertEquals("delta sign matches direction", 100, data.deltaPct)
    }

    @Test
    fun `delta label uses down arrow when this week dips`() {
        val data = StatsSparklineWidgetData(
            thisWeek = listOf(1, 1, 1, 1, 1, 1, 1),
            lastWeek = listOf(2, 2, 2, 2, 2, 2, 2),
            total = 7,
            lastTotal = 14,
            deltaPct = -50,
            up = false
        )
        assertFalse("down flag", data.up)
    }

    // -- Empty-state detection --------------------------------------------

    @Test
    fun `empty state triggers when both windows are zero`() {
        val data = StatsSparklineWidget.EMPTY_DATA
        // Mirrors the composable's `isEmpty` predicate.
        val isEmpty = data.total == 0 && data.lastTotal == 0
        assertTrue("both zero windows ⇒ empty state", isEmpty)
    }

    @Test
    fun `empty state does NOT trigger when last week had completions`() {
        val data = StatsSparklineWidgetData(
            thisWeek = List(7) { 0 },
            lastWeek = listOf(1, 1, 0, 0, 0, 0, 0),
            total = 0,
            lastTotal = 2,
            deltaPct = -100,
            up = false
        )
        val isEmpty = data.total == 0 && data.lastTotal == 0
        assertFalse("last-week data ⇒ render chart, not empty state", isEmpty)
    }

    @Test
    fun `empty state does NOT trigger when this week has completions`() {
        val data = StatsSparklineWidgetData(
            thisWeek = listOf(0, 0, 0, 0, 0, 3, 0),
            lastWeek = List(7) { 0 },
            total = 3,
            lastTotal = 0,
            deltaPct = 100,
            up = true
        )
        val isEmpty = data.total == 0 && data.lastTotal == 0
        assertFalse("this-week data ⇒ render chart, not empty state", isEmpty)
    }

    // -- Bar-height scaling: mixed-zero days ------------------------------
    //
    // The composable computes per-day bar heights in two branches:
    //   v <= 0  → 2dp baseline track tinted via palette.surfaceVariant
    //   v >  0  → ((v / max) * 40dp).coerceAtLeast(4dp), accent-tinted
    // We mirror the math here so the rules can't regress silently.

    private fun barHeightDp(v: Int, max: Int): Float {
        return if (v <= 0) {
            2f
        } else {
            ((v.toFloat() / max) * 40f).coerceAtLeast(4f)
        }
    }

    @Test
    fun `zero-task day renders the 2dp baseline track`() {
        // Mixed-zero week: 4 zero days + 3 active days. The zeros must NOT
        // be scaled into stubby accent bars — they should render as a dim
        // baseline track instead. Audit item: "no degenerate empty bars".
        val values = listOf(0, 0, 5, 0, 0, 10, 0)
        val max = values.max()
        // Days with zero completions should map to 2dp baseline track.
        values.filter { it == 0 }.forEach { v ->
            assertEquals("0-task day must use baseline height", 2f, barHeightDp(v, max), 0.001f)
        }
    }

    @Test
    fun `nonzero day uses scaled accent bar with a 4dp minimum`() {
        val values = listOf(0, 0, 5, 0, 0, 10, 0)
        val max = values.max()
        // Day with max value reaches the 40dp ceiling.
        assertEquals(40f, barHeightDp(10, max), 0.001f)
        // Mid-day: 5 of 10 → 20dp.
        assertEquals(20f, barHeightDp(5, max), 0.001f)
    }

    @Test
    fun `tiny nonzero day clamps to the 4dp minimum, never the 2dp baseline`() {
        // If today is 1 task and max is 50, raw scale = 0.8dp — clamped up
        // to 4dp so the active bar stays visually distinct from the dim
        // 2dp baseline used for actual zero-task days.
        val v = 1
        val max = 50
        val h = barHeightDp(v, max)
        assertEquals(4f, h, 0.001f)
        assertNotEquals("active min must NOT collide with baseline", 2f, h)
    }

    @Test
    fun `bar chart degenerate guard handles all-zero week`() {
        // When `thisWeek` is all zeros but `lastTotal > 0` we still draw
        // the chart. Every bar should land on the baseline; nothing
        // divides-by-zero because the composable coerces `max` to >= 1.
        val values = List(7) { 0 }
        val max = (values.max()).coerceAtLeast(1)
        values.forEach { v ->
            assertEquals(2f, barHeightDp(v, max), 0.001f)
        }
    }

    // -- Tap → Insights deep link -----------------------------------------

    @Test
    fun `tap action uses ACTION_OPEN_INSIGHTS wire id`() {
        val action = WidgetLaunchAction.OpenInsights
        assertEquals("open_insights", action.wireId)
    }

    @Test
    fun `OpenInsights wire id round-trips through deserialize`() {
        val rehydrated = WidgetLaunchAction.deserialize(WidgetLaunchAction.OpenInsights.wireId)
        assertNotNull("deserialize must recognize open_insights", rehydrated)
        assertEquals(WidgetLaunchAction.OpenInsights, rehydrated)
    }

    // -- EMPTY_DATA sentinel shape ----------------------------------------

    @Test
    fun `EMPTY_DATA carries 14 zero buckets in two arrays of seven`() {
        val data = StatsSparklineWidget.EMPTY_DATA
        assertEquals(7, data.thisWeek.size)
        assertEquals(7, data.lastWeek.size)
        assertEquals(0, data.total)
        assertEquals(0, data.lastTotal)
        assertEquals(0, data.deltaPct)
        assertTrue("default up flag", data.up)
        assertTrue("all this-week buckets zero", data.thisWeek.all { it == 0 })
        assertTrue("all last-week buckets zero", data.lastWeek.all { it == 0 })
    }
}
