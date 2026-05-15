package com.averycorp.prismtask.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encode / decode round-trip tests for the positional FIELD_SEP-delimited
 * format used by [CustomBrainModePreferences]. Covers v1 → v2 backward
 * compatibility (old persisted modes with only name/description/gentle
 * decode cleanly into the v2 shape with all overrides null) and v2
 * round-trip parity.
 */
class CustomBrainModeEncodingTest {

    @Test
    fun `empty raw decodes to empty list`() {
        assertTrue(CustomBrainModePreferences.decode("").isEmpty())
        assertTrue(CustomBrainModePreferences.decode("   ").isEmpty())
    }

    @Test
    fun `v2 round-trip preserves every field`() {
        val original = listOf(
            CustomBrainMode(
                name = "Quiet Travel",
                description = "Quiet mode + reduced haptics for flights",
                gentleNotifications = true,
                quietModeOverride = true,
                reduceHapticsOverride = true,
                mutedColorPaletteOverride = false
            ),
            CustomBrainMode(
                name = "Deep Focus",
                description = "All ADHD affordances off",
                gentleNotifications = false,
                adhdModeEnabledOverride = false,
                completionAnimationsOverride = false,
                streakCelebrationsOverride = false,
                showProgressBarsOverride = false
            )
        )
        val raw = CustomBrainModePreferences.encode(original)
        val decoded = CustomBrainModePreferences.decode(raw)
        assertEquals(original, decoded)
    }

    @Test
    fun `v1 record (name + description + gentle only) decodes cleanly`() {
        // Hand-craft a v1-shaped line so we don't depend on a v1 build to
        // round-trip. FIELD_SEP is ASCII Unit Separator (0x1F).
        val fs = '\u001F'
        val v1Line = "Calm Travel${fs}Quiet mode + reduced haptics${fs}true"
        val decoded = CustomBrainModePreferences.decode(v1Line)
        assertEquals(1, decoded.size)
        val m = decoded.single()
        assertEquals("Calm Travel", m.name)
        assertEquals("Quiet mode + reduced haptics", m.description)
        assertEquals(true, m.gentleNotifications)
        // All overrides absent — must decode to null, not false.
        assertNull(m.adhdModeEnabledOverride)
        assertNull(m.calmModeEnabledOverride)
        assertNull(m.focusReleaseModeEnabledOverride)
        assertNull(m.reduceAnimationsOverride)
        assertNull(m.mutedColorPaletteOverride)
        assertNull(m.quietModeOverride)
        assertNull(m.reduceHapticsOverride)
        assertNull(m.softContrastOverride)
        assertNull(m.completionAnimationsOverride)
        assertNull(m.streakCelebrationsOverride)
        assertNull(m.showProgressBarsOverride)
        assertNull(m.goodEnoughTimersEnabledOverride)
        assertNull(m.antiReworkEnabledOverride)
        assertNull(m.shipItCelebrationsEnabledOverride)
    }

    @Test
    fun `decode rejects malformed lines without a name`() {
        val fs = '\u001F'
        val raw = "${fs}description-only${fs}false\nGood Mode${fs}desc${fs}true"
        val decoded = CustomBrainModePreferences.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("Good Mode", decoded.single().name)
    }

    @Test
    fun `null override survives encode-decode as null`() {
        val mode = CustomBrainMode(
            name = "Sparse Mode",
            description = "",
            quietModeOverride = true
            // every other override left null
        )
        val raw = CustomBrainModePreferences.encode(listOf(mode))
        val decoded = CustomBrainModePreferences.decode(raw).single()
        assertEquals(true, decoded.quietModeOverride)
        assertNull(decoded.adhdModeEnabledOverride)
        assertNull(decoded.calmModeEnabledOverride)
        assertNull(decoded.shipItCelebrationsEnabledOverride)
    }

    @Test
    fun `override values true and false are both preserved`() {
        val mode = CustomBrainMode(
            name = "Mixed",
            description = "",
            adhdModeEnabledOverride = true,
            calmModeEnabledOverride = false
        )
        val raw = CustomBrainModePreferences.encode(listOf(mode))
        val decoded = CustomBrainModePreferences.decode(raw).single()
        assertEquals(true, decoded.adhdModeEnabledOverride)
        assertEquals(false, decoded.calmModeEnabledOverride)
    }
}
