package com.averycorp.prismtask.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainModeResolverTest {

    private val base = NdPreferences(
        adhdModeEnabled = true,
        calmModeEnabled = true,
        focusReleaseModeEnabled = true,
        reduceAnimations = true,
        mutedColorPalette = true,
        quietMode = true,
        reduceHaptics = true,
        softContrast = true,
        checkInIntervalMinutes = 25,
        completionAnimations = true,
        streakCelebrations = true,
        showProgressBars = true,
        goodEnoughTimersEnabled = true,
        defaultGoodEnoughMinutes = 30,
        antiReworkEnabled = true,
        softWarningEnabled = true,
        coolingOffEnabled = false,
        coolingOffMinutes = 30,
        revisionCounterEnabled = false,
        maxRevisions = 3,
        shipItCelebrationsEnabled = true
    )

    @Test
    fun `null override returns same base instance`() {
        val result = BrainModeResolver.resolveEffective(base, null)
        assertSame("Resolver should short-circuit on null override", base, result)
    }

    @Test
    fun `mode with all-null overrides leaves base fields unchanged`() {
        val inertMode = CustomBrainMode(name = "inert", description = "no overrides")
        val result = BrainModeResolver.resolveEffective(base, inertMode)
        assertEquals(base, result)
    }

    @Test
    fun `single override forces field state when active`() {
        val mode = CustomBrainMode(
            name = "quiet-travel",
            description = "",
            quietModeOverride = false
        )
        val result = BrainModeResolver.resolveEffective(
            base.copy(quietMode = true),
            mode
        )
        assertEquals(false, result.quietMode)
        // All other fields should be untouched.
        assertEquals(true, result.adhdModeEnabled)
        assertEquals(true, result.calmModeEnabled)
        assertEquals(true, result.reduceAnimations)
        assertEquals(25, result.checkInIntervalMinutes)
    }

    @Test
    fun `override can flip a field from off to on`() {
        val mode = CustomBrainMode(
            name = "deep-focus",
            description = "",
            quietModeOverride = true,
            reduceAnimationsOverride = true
        )
        val baseAllOff = base.copy(
            quietMode = false,
            reduceAnimations = false
        )
        val result = BrainModeResolver.resolveEffective(baseAllOff, mode)
        assertTrue(result.quietMode)
        assertTrue(result.reduceAnimations)
    }

    @Test
    fun `override covers all three mode flags`() {
        val mode = CustomBrainMode(
            name = "burnout-recovery",
            description = "",
            adhdModeEnabledOverride = false,
            calmModeEnabledOverride = true,
            focusReleaseModeEnabledOverride = false
        )
        val result = BrainModeResolver.resolveEffective(base, mode)
        assertEquals(false, result.adhdModeEnabled)
        assertEquals(true, result.calmModeEnabled)
        assertEquals(false, result.focusReleaseModeEnabled)
    }

    @Test
    fun `numeric and enum fields pass through unchanged through resolver`() {
        val mode = CustomBrainMode(
            name = "x",
            description = "",
            quietModeOverride = true
        )
        val result = BrainModeResolver.resolveEffective(base, mode)
        assertEquals(25, result.checkInIntervalMinutes)
        assertEquals(30, result.defaultGoodEnoughMinutes)
        assertEquals(GoodEnoughEscalation.NUDGE, result.goodEnoughEscalation)
        assertEquals(CelebrationIntensity.MEDIUM, result.celebrationIntensity)
    }

    @Test
    fun `every overlay field is wired correctly`() {
        // Encode every override as `false` and verify it forces all the
        // matching fields off on a base where they were all on.
        val mode = CustomBrainMode(
            name = "everything-off",
            description = "",
            adhdModeEnabledOverride = false,
            calmModeEnabledOverride = false,
            focusReleaseModeEnabledOverride = false,
            reduceAnimationsOverride = false,
            mutedColorPaletteOverride = false,
            quietModeOverride = false,
            reduceHapticsOverride = false,
            softContrastOverride = false,
            completionAnimationsOverride = false,
            streakCelebrationsOverride = false,
            showProgressBarsOverride = false,
            goodEnoughTimersEnabledOverride = false,
            antiReworkEnabledOverride = false,
            shipItCelebrationsEnabledOverride = false
        )
        val result = BrainModeResolver.resolveEffective(base, mode)
        assertEquals(false, result.adhdModeEnabled)
        assertEquals(false, result.calmModeEnabled)
        assertEquals(false, result.focusReleaseModeEnabled)
        assertEquals(false, result.reduceAnimations)
        assertEquals(false, result.mutedColorPalette)
        assertEquals(false, result.quietMode)
        assertEquals(false, result.reduceHaptics)
        assertEquals(false, result.softContrast)
        assertEquals(false, result.completionAnimations)
        assertEquals(false, result.streakCelebrations)
        assertEquals(false, result.showProgressBars)
        assertEquals(false, result.goodEnoughTimersEnabled)
        assertEquals(false, result.antiReworkEnabled)
        assertEquals(false, result.shipItCelebrationsEnabled)
    }
}
