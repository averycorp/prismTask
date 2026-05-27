package com.averycorp.prismtask.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the local-midnight rollover semantics of
 * [DormancyDismissPreferences.dismissedIdsFor]. A dismissal stored for one
 * local date must not count as dismissed on a later date.
 */
class DormancyDismissPreferencesTest {

    @Test
    fun `entries for today resolve to their task ids`() {
        val entries = setOf("1|2026-05-27", "2|2026-05-27")
        assertEquals(setOf(1L, 2L), DormancyDismissPreferences.dismissedIdsFor(entries, "2026-05-27"))
    }

    @Test
    fun `entries from a prior day are ignored after rollover`() {
        val entries = setOf("1|2026-05-26", "2|2026-05-27")
        // On the 27th, only task 2 is still dismissed; task 1 rolled over.
        assertEquals(setOf(2L), DormancyDismissPreferences.dismissedIdsFor(entries, "2026-05-27"))
        // On the 28th, nothing is dismissed.
        assertTrue(DormancyDismissPreferences.dismissedIdsFor(entries, "2026-05-28").isEmpty())
    }

    @Test
    fun `malformed entries are skipped`() {
        val entries = setOf("garbage", "|2026-05-27", "x|2026-05-27", "3|2026-05-27")
        assertEquals(setOf(3L), DormancyDismissPreferences.dismissedIdsFor(entries, "2026-05-27"))
    }
}
