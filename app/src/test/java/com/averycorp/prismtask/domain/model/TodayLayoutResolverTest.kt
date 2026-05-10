package com.averycorp.prismtask.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayLayoutResolverTest {
    @Test
    fun `empty user order falls back to default order`() {
        val result = TodayLayoutResolver.resolve(
            userOrder = emptyList(),
            hiddenKeys = emptySet(),
            currentTier = "FREE"
        )
        assertEquals(TodaySectionId.DEFAULT_ORDER, result.map { it.id.key })
    }

    @Test
    fun `hidden keys mark sections as not visible`() {
        val result = TodayLayoutResolver.resolve(
            userOrder = emptyList(),
            hiddenKeys = setOf("overdue", "completed"),
            currentTier = "FREE"
        )
        val visible = result.filter { it.visible }.map { it.id.key }
        assertFalse("overdue" in visible)
        assertFalse("completed" in visible)
        assertTrue("today_tasks" in visible)
    }

    @Test
    fun `ai briefing gated at free tier`() {
        val result = TodayLayoutResolver.resolve(
            userOrder = emptyList(),
            hiddenKeys = emptySet(),
            currentTier = "FREE"
        )
        val ai = result.first { it.id == TodaySectionId.AI_BRIEFING }
        assertFalse(ai.tierAllows)
        assertFalse(ai.render)
    }

    @Test
    fun `ai briefing renders at pro tier`() {
        val result = TodayLayoutResolver.resolve(
            userOrder = emptyList(),
            hiddenKeys = emptySet(),
            currentTier = "PRO"
        )
        val ai = result.first { it.id == TodaySectionId.AI_BRIEFING }
        assertTrue(ai.tierAllows)
        assertTrue(ai.render)
    }

    @Test
    fun `custom user order is preserved`() {
        val custom = listOf("completed", "overdue", "today_tasks", "planned")
        val result = TodayLayoutResolver.resolve(
            userOrder = custom,
            hiddenKeys = emptySet(),
            currentTier = "FREE"
        )
        // The four user-ordered keys come first in the given order
        assertEquals(custom, result.take(4).map { it.id.key })
    }

    @Test
    fun `new sections are appended with their default visibility`() {
        val partial = listOf("progress", "overdue")
        val result = TodayLayoutResolver.resolve(
            userOrder = partial,
            hiddenKeys = emptySet(),
            currentTier = "FREE"
        )
        // Flagged is appended and not visible by default
        val flagged = result.first { it.id == TodaySectionId.FLAGGED }
        assertFalse(flagged.visible)
        // Today tasks is appended and visible by default
        val today = result.first { it.id == TodaySectionId.TODAY_TASKS }
        assertTrue(today.visible)
    }

    @Test
    fun `duplicate keys in user order are deduped`() {
        val dupes = listOf("progress", "overdue", "progress", "today_tasks")
        val result = TodayLayoutResolver.resolve(
            userOrder = dupes,
            hiddenKeys = emptySet(),
            currentTier = "FREE"
        )
        assertEquals(1, result.count { it.id == TodaySectionId.PROGRESS })
    }

    @Test
    fun `unknown keys are silently dropped`() {
        val withGarbage = listOf("progress", "nonsense_key", "overdue")
        val result = TodayLayoutResolver.resolve(
            userOrder = withGarbage,
            hiddenKeys = emptySet(),
            currentTier = "FREE"
        )
        assertFalse(result.any { it.id.key == "nonsense_key" })
        assertTrue(result.any { it.id == TodaySectionId.PROGRESS })
    }

    @Test
    fun `daily essentials is free-tier and visible by default`() {
        val result = TodayLayoutResolver.resolve(
            userOrder = emptyList(),
            hiddenKeys = emptySet(),
            currentTier = "FREE"
        )
        val essentials = result.first { it.id == TodaySectionId.DAILY_ESSENTIALS }
        assertTrue(essentials.tierAllows)
        assertTrue(essentials.visible)
        assertTrue(essentials.render)
    }
}
