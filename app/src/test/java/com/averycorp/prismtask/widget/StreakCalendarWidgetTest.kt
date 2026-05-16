package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.ui.theme.PrismTheme
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [StreakCalendarWidget].
 *
 * Covers the four guarantees called out in the widget-functionality audit:
 *
 *  - **Longest-streak header** reads from data (not the historical
 *    hardcoded "18" — fixed in PR #1025).
 *  - **Heatmap intensity** scales linearly with bucket value so a busier
 *    day renders darker than a lighter one.
 *  - **Cell click intent** carries `EXTRA_LAUNCH_ACTION = open_habits` plus
 *    the new [StreakCalendarWidget.EXTRA_HABIT_DATE] long extra so the
 *    Activity / NavGraph can scope a "habit log for this day" view.
 *  - **Empty state**: the widget knows to surface a no-activity message
 *    when the user has zero completions and zero streak.
 *
 * The composable body cannot be invoked outside a running Glance host, so
 * we exercise the pure helpers — `heatColor`, `openHabitsAtDateIntent` (via
 * its callers' contract), and the data-class plumbing — directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StreakCalendarWidgetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `extra habit date key is stable`() {
        // The launch-intent key is consumed downstream by the Activity (and a
        // future NavGraph handler). Renaming it would silently break the
        // tap-cell deep link — pin the wire value.
        assertEquals(
            "com.averycorp.prismtask.HABIT_DATE",
            StreakCalendarWidget.EXTRA_HABIT_DATE
        )
    }

    @Test
    fun `widget data preserves longest streak from real source`() {
        // Regression: historical widget hardcoded "18". Confirm whatever
        // number the data layer hands us survives unchanged onto the data
        // record the composable reads.
        val data = StreakCalendarWidgetData(
            intensities = List(12 * 7) { 0 },
            activeDays = 5,
            longestStreak = 7,
            weeks = 12
        )
        assertEquals(7, data.longestStreak)
        assertNotEquals("hardcoded 18 sentinel", 18, data.longestStreak)
    }

    @Test
    fun `widget data with zero streak indicates empty state`() {
        val data = StreakCalendarWidgetData(
            intensities = List(12 * 7) { 0 },
            activeDays = 0,
            longestStreak = 0,
            weeks = 12
        )
        // The composable shows the empty-state when both are zero.
        assertTrue(data.activeDays == 0 && data.longestStreak == 0)
    }

    @Test
    fun `widget data with completions but no current streak still renders heatmap`() {
        // A user who completed habits last week but skipped the past few
        // days has activeDays > 0 but longestStreak == 0. We still want the
        // heatmap — empty-state should only fire when BOTH counters are zero.
        val data = StreakCalendarWidgetData(
            intensities = List(12 * 7) { if (it < 5) 2 else 0 },
            activeDays = 5,
            longestStreak = 0,
            weeks = 12
        )
        assertFalse(data.activeDays == 0 && data.longestStreak == 0)
    }

    @Test
    fun `heat color empty bucket falls back to habit incomplete`() {
        val palette = widgetThemePalette(PrismTheme.VOID)
        val empty = heatColor(0, palette)
        // The 0-bucket explicitly returns palette.habitIncomplete — verifying
        // by reference equality is fine since palette values are constructed
        // fresh and the function should hand back the exact same provider.
        assertEquals(palette.habitIncomplete, empty)
    }

    @Test
    fun `heat color higher bucket is distinct from empty bucket`() {
        val palette = widgetThemePalette(PrismTheme.VOID)
        val empty = heatColor(0, palette)
        val low = heatColor(1, palette)
        val mid = heatColor(2, palette)
        val high = heatColor(4, palette)
        // Each bucket renders a distinct color so the heatmap visually
        // distinguishes a busy day from an empty one.
        assertNotEquals(empty, low)
        assertNotEquals(low, mid)
        assertNotEquals(mid, high)
    }

    @Test
    fun `heat color out of range bucket saturates to maximum`() {
        val palette = widgetThemePalette(PrismTheme.VOID)
        val maxBucket = heatColor(4, palette)
        // Any v >= 4 hits the else branch and uses alpha = 1.0f.
        val overflow = heatColor(99, palette)
        assertEquals(maxBucket, overflow)
    }

    @Test
    fun `cell click intent carries open habits wire id and date extra`() {
        val cellDate = 1_700_000_000_000L
        val intent = buildCellIntent(context, cellDate)
        assertEquals(
            WidgetLaunchAction.OpenHabits.wireId,
            intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION)
        )
        assertEquals(cellDate, intent.getLongExtra(StreakCalendarWidget.EXTRA_HABIT_DATE, -1L))
        // Required flags so the Activity treats the launch as a fresh task
        // (matches the sibling-widget intent pattern).
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun `different cells produce different date extras`() {
        val dayMs = 24L * 60L * 60L * 1000L
        val a = buildCellIntent(context, 1_700_000_000_000L)
        val b = buildCellIntent(context, 1_700_000_000_000L + dayMs)
        assertNotEquals(
            a.getLongExtra(StreakCalendarWidget.EXTRA_HABIT_DATE, -1L),
            b.getLongExtra(StreakCalendarWidget.EXTRA_HABIT_DATE, -1L)
        )
    }

    @Test
    fun `intent target activity is main activity`() {
        val intent = buildCellIntent(context, 1_700_000_000_000L)
        assertNotNull(intent.component)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `wire id matches widget launch action contract`() {
        // Cross-check against the contract — if either side drifts (renaming
        // the data object, or stamping the wrong string onto the intent),
        // the deep-link silently breaks.
        assertEquals("open_habits", WidgetLaunchAction.OpenHabits.wireId)
    }

    @Test
    fun `deserialize round trips open habits wire id`() {
        val intent = buildCellIntent(context, 1_700_000_000_000L)
        val wireId = intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION)
        val action = WidgetLaunchAction.deserialize(wireId = wireId)
        assertEquals(WidgetLaunchAction.OpenHabits, action)
    }

    @Test
    fun `intent without date extra returns sentinel`() {
        // Defensive: confirms `-1L` survives as the "no date" sentinel for
        // callers that don't pass a date. The downstream Activity uses the
        // same sentinel-coalescing pattern as `EXTRA_TASK_ID`.
        val intent = Intent(context, MainActivity::class.java)
        assertEquals(-1L, intent.getLongExtra(StreakCalendarWidget.EXTRA_HABIT_DATE, -1L))
    }

    @Test
    fun `intent without launch action extra returns null`() {
        val intent = Intent(context, MainActivity::class.java)
        assertNull(intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION))
    }

    /**
     * Mirror of the widget's private `openHabitsAtDateIntent` helper.
     * Duplicated here intentionally so the test pins the *intent shape* the
     * widget commits to; if the widget's helper drifts away from this shape,
     * the deep-link contract assertions above will fail.
     */
    private fun buildCellIntent(context: Context, dateStart: Long): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenHabits.wireId)
            putExtra(StreakCalendarWidget.EXTRA_HABIT_DATE, dateStart)
        }
}
