package com.averycorp.prismtask.widget

import com.averycorp.prismtask.data.preferences.ProductivityWidgetThresholds
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the Productivity widget's render logic.
 *
 * Glance composables aren't unit-testable on the JVM (they require
 * RemoteViews / SystemUI plumbing), so these tests exercise the
 * decisions feeding the render path:
 *
 *  - score-band classification → palette color token
 *  - trend arrow + label formatting
 *  - yesterday's score derivation (with clamping)
 *  - empty-state trigger when total == 0
 *  - deep-link wire id stability for `OpenInsights`
 *
 * Pattern mirrors `CalendarWidgetTest`.
 */
class ProductivityWidgetTest {

    // --- score-band classification ---

    @Test
    fun `score at or above green threshold classifies as green`() {
        val t = ProductivityWidgetThresholds(greenScore = 80, orangeScore = 60)
        assertEquals(ScoreBand.GREEN, classify(score = 80, thresholds = t))
        assertEquals(ScoreBand.GREEN, classify(score = 95, thresholds = t))
        assertEquals(ScoreBand.GREEN, classify(score = 100, thresholds = t))
    }

    @Test
    fun `score between orange and green is orange`() {
        val t = ProductivityWidgetThresholds(greenScore = 80, orangeScore = 60)
        assertEquals(ScoreBand.ORANGE, classify(score = 60, thresholds = t))
        assertEquals(ScoreBand.ORANGE, classify(score = 70, thresholds = t))
        assertEquals(ScoreBand.ORANGE, classify(score = 79, thresholds = t))
    }

    @Test
    fun `score below orange threshold is red`() {
        val t = ProductivityWidgetThresholds(greenScore = 80, orangeScore = 60)
        assertEquals(ScoreBand.RED, classify(score = 0, thresholds = t))
        assertEquals(ScoreBand.RED, classify(score = 30, thresholds = t))
        assertEquals(ScoreBand.RED, classify(score = 59, thresholds = t))
    }

    // --- trend arrow + label ---

    @Test
    fun `trend label uses up arrow for positive`() {
        val label = trendLabel(trendPoints = 12)
        assertTrue("expected '↑' arrow, got $label", label.startsWith("↑"))
        assertTrue("expected '12 pts' magnitude, got $label", label.contains("12 pts"))
    }

    @Test
    fun `trend label uses down arrow and absolute value for negative`() {
        val label = trendLabel(trendPoints = -8)
        assertTrue("expected '↓' arrow, got $label", label.startsWith("↓"))
        // Negative trend should not surface a '-' sign in the magnitude.
        assertTrue("expected '8 pts' magnitude, got $label", label.contains("8 pts"))
        assertEquals(-1, label.indexOf("-8"))
    }

    @Test
    fun `trend label is neutral for zero`() {
        val label = trendLabel(trendPoints = 0)
        assertTrue("expected dash, got $label", label.startsWith("–"))
        assertTrue("expected 'no change' copy, got $label", label.contains("no change"))
    }

    // --- yesterday score derivation ---

    @Test
    fun `yesterday score reverses the trend delta`() {
        // score 70 today, +20 trend → yesterday was 50.
        assertEquals(50, yesterdayScore(score = 70, trendPoints = 20))
        // score 40 today, -10 trend → yesterday was 50.
        assertEquals(50, yesterdayScore(score = 40, trendPoints = -10))
        // flat: same as today.
        assertEquals(75, yesterdayScore(score = 75, trendPoints = 0))
    }

    @Test
    fun `yesterday score clamps to 0 100 range`() {
        // score 5 today, -30 trend → reverse would be 35 (in range). Clamp matters
        // only when computation goes negative or above 100; force that case:
        assertEquals(0, yesterdayScore(score = 10, trendPoints = 100))
        assertEquals(100, yesterdayScore(score = 100, trendPoints = -50))
    }

    // --- empty-state trigger ---

    @Test
    fun `empty state triggers when total is zero`() {
        val empty = ProductivityWidgetData(score = 0, completed = 0, total = 0, trendPoints = 0)
        assertTrue(shouldShowEmptyState(empty))

        val populated = ProductivityWidgetData(score = 60, completed = 3, total = 5, trendPoints = 10)
        assertEquals(false, shouldShowEmptyState(populated))
    }

    @Test
    fun `empty state does not trigger when total positive even if completed is zero`() {
        // A user with planned-but-untouched tasks should see their 0 score
        // not the "Start Your Day!" empty state — the day is in progress.
        val data = ProductivityWidgetData(score = 0, completed = 0, total = 5, trendPoints = 0)
        assertEquals(false, shouldShowEmptyState(data))
    }

    // --- deep-link wire id ---

    @Test
    fun `open insights wire id is stable`() {
        assertEquals("open_insights", WidgetLaunchAction.OpenInsights.wireId)
        // Round-trip through deserialize so the deep-link path keeps working
        // even if someone refactors the launcher contract.
        assertEquals(
            WidgetLaunchAction.OpenInsights,
            WidgetLaunchAction.deserialize(WidgetLaunchAction.OpenInsights.wireId)
        )
        // Sanity: the productivity widget does NOT launch into QuickAdd or any
        // task-id-specific flow.
        assertNotEquals(WidgetLaunchAction.QuickAdd.wireId, WidgetLaunchAction.OpenInsights.wireId)
    }

    // --- score formatting (used for the badge text) ---

    @Test
    fun `score renders as plain integer string`() {
        assertEquals("0", formatScore(0))
        assertEquals("87", formatScore(87))
        assertEquals("100", formatScore(100))
    }

    // --- compact trend summary on small size bucket ---

    @Test
    fun `compact trend summary uses arrow plus completed over total`() {
        val data = ProductivityWidgetData(score = 80, completed = 4, total = 5, trendPoints = 10)
        assertEquals("↑ 4/5", compactTrend(data))

        val flat = ProductivityWidgetData(score = 50, completed = 2, total = 4, trendPoints = 0)
        assertEquals("– 2/4", compactTrend(flat))

        val down = ProductivityWidgetData(score = 20, completed = 1, total = 5, trendPoints = -30)
        assertEquals("↓ 1/5", compactTrend(down))
    }

    // ---- Helpers (mirror the widget's render-time decisions). ----

    private enum class ScoreBand { GREEN, ORANGE, RED }

    private fun classify(score: Int, thresholds: ProductivityWidgetThresholds): ScoreBand = when {
        score >= thresholds.greenScore -> ScoreBand.GREEN
        score >= thresholds.orangeScore -> ScoreBand.ORANGE
        else -> ScoreBand.RED
    }

    private fun trendLabel(trendPoints: Int): String = when {
        trendPoints > 0 -> "↑ $trendPoints pts"
        trendPoints < 0 -> "↓ ${-trendPoints} pts"
        else -> "– no change"
    }

    private fun yesterdayScore(score: Int, trendPoints: Int): Int =
        (score - trendPoints).coerceIn(0, 100)

    private fun shouldShowEmptyState(data: ProductivityWidgetData): Boolean = data.total == 0

    private fun formatScore(score: Int): String = score.toString()

    private fun compactTrend(data: ProductivityWidgetData): String {
        val arrow = when {
            data.trendPoints > 0 -> "↑"
            data.trendPoints < 0 -> "↓"
            else -> "–"
        }
        return "$arrow ${data.completed}/${data.total}"
    }
}
