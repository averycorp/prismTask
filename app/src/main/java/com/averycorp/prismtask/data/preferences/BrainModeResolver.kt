package com.averycorp.prismtask.data.preferences

/**
 * Layers an optional active [CustomBrainMode] overlay on top of base
 * [NdPreferences] to produce the effective ND preferences seen by
 * dispatch sites.
 *
 * Each override field is null-coalesced — `null` means "inherit base."
 * Fields with no overlay counterpart (numeric thresholds, escalation
 * enums, celebration intensity, et al.) pass through unchanged. The
 * resolver is a pure function: no IO, no DataStore reads, no Flow
 * collection — callers handle flow plumbing.
 *
 * Architecturally, this lets a custom mode act as a *sparse overlay*
 * rather than a full replacement. Users can adopt a mode that flips
 * `quietMode` on without disturbing animations, color palette, or
 * any of the ADHD-mode reward affordances.
 */
object BrainModeResolver {
    fun resolveEffective(base: NdPreferences, override: CustomBrainMode?): NdPreferences {
        if (override == null) return base
        return base.copy(
            adhdModeEnabled = override.adhdModeEnabledOverride ?: base.adhdModeEnabled,
            calmModeEnabled = override.calmModeEnabledOverride ?: base.calmModeEnabled,
            focusReleaseModeEnabled = override.focusReleaseModeEnabledOverride
                ?: base.focusReleaseModeEnabled,
            reduceAnimations = override.reduceAnimationsOverride ?: base.reduceAnimations,
            mutedColorPalette = override.mutedColorPaletteOverride ?: base.mutedColorPalette,
            quietMode = override.quietModeOverride ?: base.quietMode,
            reduceHaptics = override.reduceHapticsOverride ?: base.reduceHaptics,
            softContrast = override.softContrastOverride ?: base.softContrast,
            completionAnimations = override.completionAnimationsOverride
                ?: base.completionAnimations,
            streakCelebrations = override.streakCelebrationsOverride ?: base.streakCelebrations,
            showProgressBars = override.showProgressBarsOverride ?: base.showProgressBars,
            goodEnoughTimersEnabled = override.goodEnoughTimersEnabledOverride
                ?: base.goodEnoughTimersEnabled,
            antiReworkEnabled = override.antiReworkEnabledOverride ?: base.antiReworkEnabled,
            shipItCelebrationsEnabled = override.shipItCelebrationsEnabledOverride
                ?: base.shipItCelebrationsEnabled
        )
    }
}
