package com.averycorp.prismtask.ui.screens.onboarding

import com.averycorp.prismtask.ui.screens.onboarding.OnboardingPreferenceMapper.Result
import com.averycorp.prismtask.ui.screens.onboarding.OnboardingPreferenceMapper.TuningOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mental-Health-First § G6: covers every option in isolation, multi-select
 * combinations, and the "None of these" override.
 */
class OnboardingPreferenceMapperTest {

    // ── Skip path ────────────────────────────────────────────────────────────

    @Test
    fun `empty selection is a no-op`() {
        val r = OnboardingPreferenceMapper.resolve(emptySet())
        assertEquals(Result.EMPTY, r)
        assertTrue(r.isNoOp)
    }

    @Test
    fun `Result EMPTY has no flags set and no check-in interval`() {
        val r = Result.EMPTY
        assertFalse(r.adhdMode)
        assertFalse(r.calmMode)
        assertFalse(r.focusReleaseMode)
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertFalse(r.goodEnoughTimers)
        assertFalse(r.forgivenessStreaks)
        assertFalse(r.compactMode)
        assertFalse(r.primeRestDay)
        assertNull(r.checkInIntervalMinutes)
    }

    // ── Each option in isolation ────────────────────────────────────────────

    @Test
    fun `overwhelmed by long lists maps to compact mode only`() {
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.OVERWHELMED_BY_LONG_LISTS))
        assertTrue(r.compactMode)
        // Verify nothing else flipped.
        assertFalse(r.adhdMode)
        assertFalse(r.calmMode)
        assertFalse(r.focusReleaseMode)
        assertFalse(r.forgivenessStreaks)
        assertFalse(r.primeRestDay)
        assertNull(r.checkInIntervalMinutes)
    }

    @Test
    fun `lose track of time maps to ADHD mode plus 25-min check-in`() {
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.LOSE_TRACK_OF_TIME))
        assertTrue(r.adhdMode)
        assertEquals(25, r.checkInIntervalMinutes)
        assertFalse(r.calmMode)
        assertFalse(r.focusReleaseMode)
        assertFalse(r.compactMode)
    }

    @Test
    fun `low-energy days maps to forgiveness streaks plus rest-day priming`() {
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.LOW_ENERGY_DAYS))
        assertTrue(r.forgivenessStreaks)
        assertTrue(r.primeRestDay)
        assertFalse(r.adhdMode)
        assertFalse(r.calmMode)
        assertFalse(r.focusReleaseMode)
    }

    @Test
    fun `fewer animations maps to calm mode reduce animations and muted palette`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS)
        )
        assertTrue(r.calmMode)
        assertTrue(r.reduceAnimations)
        assertTrue(r.mutedColorPalette)
        assertFalse(r.adhdMode)
        assertFalse(r.focusReleaseMode)
    }

    @Test
    fun `over-polish maps to focus and release plus good enough timers`() {
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.OVER_POLISH))
        assertTrue(r.focusReleaseMode)
        assertTrue(r.goodEnoughTimers)
        assertFalse(r.adhdMode)
        assertFalse(r.calmMode)
    }

    // ── None-of-these override ──────────────────────────────────────────────

    @Test
    fun `none of these alone is a no-op`() {
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.NONE_OF_THESE))
        assertEquals(Result.EMPTY, r)
    }

    @Test
    fun `none of these overrides all other selections`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(
                TuningOption.NONE_OF_THESE,
                TuningOption.OVERWHELMED_BY_LONG_LISTS,
                TuningOption.LOSE_TRACK_OF_TIME,
                TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS
            )
        )
        // "None of these" wipes the rest — audit § G6 hard rule.
        assertEquals(Result.EMPTY, r)
        assertTrue(r.isNoOp)
    }

    // ── Multi-select combinations ───────────────────────────────────────────

    @Test
    fun `two options OR-merge their preference flags`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(TuningOption.LOSE_TRACK_OF_TIME, TuningOption.LOW_ENERGY_DAYS)
        )
        assertTrue(r.adhdMode)
        assertEquals(25, r.checkInIntervalMinutes)
        assertTrue(r.forgivenessStreaks)
        assertTrue(r.primeRestDay)
        assertFalse(r.calmMode)
        assertFalse(r.focusReleaseMode)
        assertFalse(r.compactMode)
    }

    @Test
    fun `all five real options selected yields every default`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(
                TuningOption.OVERWHELMED_BY_LONG_LISTS,
                TuningOption.LOSE_TRACK_OF_TIME,
                TuningOption.LOW_ENERGY_DAYS,
                TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS,
                TuningOption.OVER_POLISH
            )
        )
        assertTrue(r.adhdMode)
        assertTrue(r.calmMode)
        assertTrue(r.focusReleaseMode)
        assertTrue(r.reduceAnimations)
        assertTrue(r.mutedColorPalette)
        assertTrue(r.goodEnoughTimers)
        assertTrue(r.forgivenessStreaks)
        assertTrue(r.compactMode)
        assertTrue(r.primeRestDay)
        assertEquals(25, r.checkInIntervalMinutes)
        assertFalse(r.isNoOp)
    }

    @Test
    fun `over-polish plus low-energy combines focus-release and forgiveness`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(TuningOption.OVER_POLISH, TuningOption.LOW_ENERGY_DAYS)
        )
        assertTrue(r.focusReleaseMode)
        assertTrue(r.goodEnoughTimers)
        assertTrue(r.forgivenessStreaks)
        assertTrue(r.primeRestDay)
        assertFalse(r.adhdMode)
        assertFalse(r.calmMode)
        assertFalse(r.compactMode)
    }

    @Test
    fun `overwhelmed plus calm does not touch ADHD or forgiveness flags`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(
                TuningOption.OVERWHELMED_BY_LONG_LISTS,
                TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS
            )
        )
        assertTrue(r.compactMode)
        assertTrue(r.calmMode)
        assertTrue(r.reduceAnimations)
        assertTrue(r.mutedColorPalette)
        assertFalse(r.adhdMode)
        assertFalse(r.forgivenessStreaks)
        assertFalse(r.focusReleaseMode)
        assertNull(r.checkInIntervalMinutes)
    }
}
