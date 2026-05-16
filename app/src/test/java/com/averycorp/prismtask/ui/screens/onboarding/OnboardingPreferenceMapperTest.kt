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
 *
 * Post-`fix/onboarding-tuning-mode-cascade` (audit findings #2 + #6): the
 * mapper no longer carries `adhdMode`, `calmMode`, `focusReleaseMode`, or
 * `forgivenessStreaks` fields. Those parent flags are owned by BrainModePage
 * (page 9) and HabitsPage Forgiveness Switch (page 6) respectively, and were
 * removed here so TuningPage can't silently override an opt-out on the
 * earlier pages.
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
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertFalse(r.goodEnoughTimers)
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
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertFalse(r.goodEnoughTimers)
        assertFalse(r.primeRestDay)
        assertNull(r.checkInIntervalMinutes)
    }

    @Test
    fun `lose track of time maps to 25-min check-in only`() {
        // Post-audit-#2: parent `adhdMode` is no longer written by TuningPage.
        // BrainModePage owns that flag.
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.LOSE_TRACK_OF_TIME))
        assertEquals(25, r.checkInIntervalMinutes)
        assertFalse(r.compactMode)
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertFalse(r.goodEnoughTimers)
        assertFalse(r.primeRestDay)
    }

    @Test
    fun `low-energy days maps to rest-day priming only`() {
        // Post-audit-#6: parent `ForgivenessPrefs.enabled` is no longer written
        // by TuningPage. HabitsPage owns that flag.
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.LOW_ENERGY_DAYS))
        assertTrue(r.primeRestDay)
        assertFalse(r.compactMode)
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertFalse(r.goodEnoughTimers)
        assertNull(r.checkInIntervalMinutes)
    }

    @Test
    fun `fewer animations maps to reduce animations and muted palette`() {
        // Post-audit-#2: parent `calmMode` is no longer written by TuningPage.
        val r = OnboardingPreferenceMapper.resolve(
            setOf(TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS)
        )
        assertTrue(r.reduceAnimations)
        assertTrue(r.mutedColorPalette)
        assertFalse(r.compactMode)
        assertFalse(r.goodEnoughTimers)
        assertFalse(r.primeRestDay)
        assertNull(r.checkInIntervalMinutes)
    }

    @Test
    fun `over-polish maps to good-enough timers only`() {
        // Post-audit-#2: parent `focusReleaseMode` is no longer written by
        // TuningPage.
        val r = OnboardingPreferenceMapper.resolve(setOf(TuningOption.OVER_POLISH))
        assertTrue(r.goodEnoughTimers)
        assertFalse(r.compactMode)
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertFalse(r.primeRestDay)
        assertNull(r.checkInIntervalMinutes)
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
        assertEquals(25, r.checkInIntervalMinutes)
        assertTrue(r.primeRestDay)
        assertFalse(r.compactMode)
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertFalse(r.goodEnoughTimers)
    }

    @Test
    fun `all five real options selected yields every sub-flag default`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(
                TuningOption.OVERWHELMED_BY_LONG_LISTS,
                TuningOption.LOSE_TRACK_OF_TIME,
                TuningOption.LOW_ENERGY_DAYS,
                TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS,
                TuningOption.OVER_POLISH
            )
        )
        assertTrue(r.reduceAnimations)
        assertTrue(r.mutedColorPalette)
        assertTrue(r.goodEnoughTimers)
        assertTrue(r.compactMode)
        assertTrue(r.primeRestDay)
        assertEquals(25, r.checkInIntervalMinutes)
        assertFalse(r.isNoOp)
    }

    @Test
    fun `over-polish plus low-energy combines good-enough timers and rest-day priming`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(TuningOption.OVER_POLISH, TuningOption.LOW_ENERGY_DAYS)
        )
        assertTrue(r.goodEnoughTimers)
        assertTrue(r.primeRestDay)
        assertFalse(r.compactMode)
        assertFalse(r.reduceAnimations)
        assertFalse(r.mutedColorPalette)
        assertNull(r.checkInIntervalMinutes)
    }

    @Test
    fun `overwhelmed plus fewer-animations sets compact and animation sub-flags`() {
        val r = OnboardingPreferenceMapper.resolve(
            setOf(
                TuningOption.OVERWHELMED_BY_LONG_LISTS,
                TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS
            )
        )
        assertTrue(r.compactMode)
        assertTrue(r.reduceAnimations)
        assertTrue(r.mutedColorPalette)
        assertFalse(r.goodEnoughTimers)
        assertFalse(r.primeRestDay)
        assertNull(r.checkInIntervalMinutes)
    }
}
