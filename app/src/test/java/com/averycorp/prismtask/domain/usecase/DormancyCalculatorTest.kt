package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DormancyCalculatorTest {

    private val oneDay = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L // arbitrary fixed clock

    private fun task(
        lastEngagementAt: Long? = null,
        override: Int? = null
    ): TaskEntity = TaskEntity(
        id = 1,
        title = "t",
        lastEngagementAt = lastEngagementAt,
        dormancyThresholdDaysOverride = override
    )

    // ── effectiveThreshold = override ?: global (Checkpoint 1 #6) ──

    @Test
    fun `effective threshold falls back to global when no override`() {
        assertEquals(7, task(override = null).effectiveDormancyThreshold(global = 7))
    }

    @Test
    fun `effective threshold uses override when present`() {
        assertEquals(30, task(override = 30).effectiveDormancyThreshold(global = 7))
    }

    @Test
    fun `override of zero still wins over global`() {
        assertEquals(0, task(override = 0).effectiveDormancyThreshold(global = 7))
    }

    // ── isDormant boundary conditions (Checkpoint 3 #4) ──

    @Test
    fun `null last engagement is never dormant`() {
        assertFalse(task(lastEngagementAt = null).isDormant(now, global = 7))
    }

    @Test
    fun `exactly at threshold is not dormant`() {
        // 7 whole days elapsed, threshold 7 → 7 > 7 is false.
        val t = task(lastEngagementAt = now - 7 * oneDay)
        assertFalse(t.isDormant(now, global = 7))
    }

    @Test
    fun `one day past threshold is dormant`() {
        val t = task(lastEngagementAt = now - 8 * oneDay)
        assertTrue(t.isDormant(now, global = 7))
    }

    @Test
    fun `one day under threshold is not dormant`() {
        val t = task(lastEngagementAt = now - 6 * oneDay)
        assertFalse(t.isDormant(now, global = 7))
    }

    @Test
    fun `partial day past threshold does not tip until a full day elapses`() {
        // 7 days + 23h → still 7 whole days elapsed → not dormant.
        val t = task(lastEngagementAt = now - (7 * oneDay + 23 * 60 * 60 * 1000))
        assertFalse(t.isDormant(now, global = 7))
    }

    @Test
    fun `override threshold takes precedence over global for dormancy`() {
        // 10 days dormant: global 7 would be dormant, but override 30 is not.
        val t = task(lastEngagementAt = now - 10 * oneDay, override = 30)
        assertFalse(t.isDormant(now, global = 7))
        // And a short override makes it dormant sooner than the global would.
        val t2 = task(lastEngagementAt = now - 3 * oneDay, override = 2)
        assertTrue(t2.isDormant(now, global = 7))
    }

    @Test
    fun `future engagement timestamp is not dormant`() {
        val t = task(lastEngagementAt = now + 5 * oneDay)
        assertFalse(t.isDormant(now, global = 7))
    }

    @Test
    fun `days dormant is zero for never engaged`() {
        assertEquals(0L, DormancyCalculator.daysDormant(null, now))
    }

    @Test
    fun `days dormant counts whole days`() {
        assertEquals(9L, DormancyCalculator.daysDormant(now - (9 * oneDay + 5 * 60 * 1000), now))
    }
}
