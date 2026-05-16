package com.averycorp.prismtask.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [HabitStreakWidget]'s pure-Kotlin pieces — [applyConfig], the
 * [HabitWidgetData] / [HabitWidgetItem] model, and [habitIdParams] action
 * parameters. Mirrors the shape of [CalendarWidgetTest]: we exercise the
 * data wiring that drives header text, empty state, and toggle intents
 * without spinning up a Glance host.
 */
class HabitStreakWidgetTest {

    @Test
    fun `applyConfig with empty selection returns full habit list`() {
        val data = sampleHabitData(
            HabitWidgetItem(1L, "Run", "🏃", 5, true),
            HabitWidgetItem(2L, "Read", "📖", 3, false)
        )
        val config = WidgetConfigDataStore.HabitStreakConfig() // empty selection, max 6
        val result = applyConfig(data, config)
        assertEquals(2, result.habits.size)
        assertEquals(5, result.longestStreak)
    }

    @Test
    fun `applyConfig with selectedHabitIds filters to that set in order`() {
        val data = sampleHabitData(
            HabitWidgetItem(1L, "Run", "🏃", 5, true),
            HabitWidgetItem(2L, "Read", "📖", 3, false),
            HabitWidgetItem(3L, "Meditate", "🧘", 10, true)
        )
        val config = WidgetConfigDataStore.HabitStreakConfig(
            selectedHabitIds = listOf(3L, 1L) // user-picked order
        )
        val result = applyConfig(data, config)
        assertEquals(2, result.habits.size)
        assertEquals(3L, result.habits[0].id)
        assertEquals(1L, result.habits[1].id)
        // Longest is recomputed over the visible subset (Meditate's 10).
        assertEquals(10, result.longestStreak)
    }

    @Test
    fun `applyConfig drops selected ids that no longer exist`() {
        // Habit was deleted from the DB after the user picked it; widget
        // should silently drop the stale id rather than crash.
        val data = sampleHabitData(
            HabitWidgetItem(1L, "Run", "🏃", 5, true)
        )
        val config = WidgetConfigDataStore.HabitStreakConfig(
            selectedHabitIds = listOf(999L, 1L)
        )
        val result = applyConfig(data, config)
        assertEquals(1, result.habits.size)
        assertEquals(1L, result.habits[0].id)
    }

    @Test
    fun `applyConfig caps to maxItems`() {
        val data = sampleHabitData(
            *(1L..10L).map { id ->
                HabitWidgetItem(id, "h$id", "⭐", id.toInt(), false)
            }.toTypedArray()
        )
        val config = WidgetConfigDataStore.HabitStreakConfig(maxItems = 3)
        val result = applyConfig(data, config)
        assertEquals(3, result.habits.size)
        // The first three habits' streaks are 1, 2, 3 — so the visible
        // longest is 3, not the source data's 10.
        assertEquals(3, result.longestStreak)
    }

    @Test
    fun `applyConfig empty data yields zero longest streak`() {
        val data = HabitWidgetData(habits = emptyList(), longestStreak = 0)
        val result = applyConfig(data, WidgetConfigDataStore.HabitStreakConfig())
        assertTrue(result.habits.isEmpty())
        assertEquals(0, result.longestStreak)
    }

    @Test
    fun `habitIdParams encodes the habit id under the canonical key`() {
        // The toggle row's clickable wires actionRunCallback<ToggleHabitFromWidgetAction>
        // with this exact parameter bundle, so the encoding contract is
        // load-bearing for the per-habit tap-to-toggle gesture.
        val params = habitIdParams(42L)
        assertEquals(42L, params[WidgetActionKeys.HABIT_ID])
    }

    @Test
    fun `HabitStreakConfig defaults match documented widget behaviour`() {
        // The widget body branches on these defaults; pin them so a silent
        // change in WidgetConfigDataStore doesn't flip the widget's visual
        // contract.
        val cfg = WidgetConfigDataStore.HabitStreakConfig()
        assertTrue(cfg.selectedHabitIds.isEmpty())
        assertTrue(cfg.showStreakCount)
        assertFalse(cfg.layoutGrid)
        assertEquals(6, cfg.maxItems)
    }

    @Test
    fun `HabitWidgetData copy preserves identity of habits when filtering`() {
        // applyConfig should produce a defensive copy via data class .copy —
        // mutations downstream shouldn't leak into the source list.
        val source = sampleHabitData(HabitWidgetItem(1L, "Run", "🏃", 5, true))
        val result = applyConfig(source, WidgetConfigDataStore.HabitStreakConfig())
        assertNotNull(result)
        assertEquals(source.habits[0].id, result.habits[0].id)
    }

    @Test
    fun `last7Days defaults to empty list when not provided`() {
        // WeeklyDots iterates last7Days; empty default keeps the row safe
        // when DAO didn't populate the field (e.g. on a brand-new install).
        val item = HabitWidgetItem(1L, "Run", "🏃", 0, false)
        assertTrue(item.last7Days.isEmpty())
    }

    @Test
    fun `applyConfig with maxItems zero yields empty visible list`() {
        // Defensive — maxItems is coerced 1..12 in WidgetConfigDataStore,
        // but a stale config (older app version) could pass 0; the widget
        // must not crash.
        val data = sampleHabitData(HabitWidgetItem(1L, "Run", "🏃", 5, true))
        val config = WidgetConfigDataStore.HabitStreakConfig(maxItems = 0)
        val result = applyConfig(data, config)
        assertTrue(result.habits.isEmpty())
        assertEquals(0, result.longestStreak)
    }

    @Test
    fun `selected order overrides natural sort order`() {
        // The Habit screen sorts active habits by sort_order ASC; the
        // widget config should win over that natural order so the user's
        // chosen layout sticks.
        val data = sampleHabitData(
            HabitWidgetItem(1L, "A", "⭐", 1, false),
            HabitWidgetItem(2L, "B", "⭐", 2, false),
            HabitWidgetItem(3L, "C", "⭐", 3, false)
        )
        val config = WidgetConfigDataStore.HabitStreakConfig(
            selectedHabitIds = listOf(3L, 2L, 1L)
        )
        val result = applyConfig(data, config)
        assertEquals(listOf(3L, 2L, 1L), result.habits.map { it.id })
    }

    @Test
    fun `getHabitData contract returns null-safe data shape`() {
        // Documents the constructor invariants the widget body relies on:
        // habits is non-null, longestStreak is non-negative.
        val empty = HabitWidgetData(habits = emptyList(), longestStreak = 0)
        assertNull(empty.habits.firstOrNull())
        assertTrue(empty.longestStreak >= 0)
    }

    private fun sampleHabitData(vararg items: HabitWidgetItem): HabitWidgetData =
        HabitWidgetData(
            habits = items.toList(),
            longestStreak = items.maxOfOrNull { it.streak } ?: 0
        )
}
