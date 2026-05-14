package com.averycorp.prismtask.data.preferences

/**
 * Neurodivergent Mode preferences. Three independent mode toggles (ADHD Mode,
 * Calm Mode, and Focus & Release Mode) that can each be on or off simultaneously.
 *
 * - **ADHD Mode**: enables task decomposition, focus guard, body doubling,
 *   dopamine/reward features.
 * - **Calm Mode**: enables sensory reduction (animations, colors, sounds, haptics,
 *   contrast).
 * - **Focus & Release Mode**: helps perfectionists finish tasks, stop over-polishing,
 *   and let go of completed work.
 */
data class NdPreferences(
    // --- Top-level mode toggles ---
    // Defaults match `NdPreferencesDataStore.ndPreferencesFlow` fallbacks
    // (operator decision 2026-05-14: presume ND baseline; let users opt
    // out). Cascade sub-settings mirror so a fresh install reads
    // self-consistent state with `setX(true)` semantics.
    val adhdModeEnabled: Boolean = true,
    val calmModeEnabled: Boolean = true,
    val focusReleaseModeEnabled: Boolean = true,
    // --- Calm Mode sub-settings (all flip ON when calmModeEnabled first enabled) ---
    val reduceAnimations: Boolean = true,
    val mutedColorPalette: Boolean = true,
    val quietMode: Boolean = true,
    val reduceHaptics: Boolean = true,
    val softContrast: Boolean = true,
    // --- ADHD Mode sub-settings (all flip ON when adhdModeEnabled first enabled) ---
    val checkInIntervalMinutes: Int = 25,
    val completionAnimations: Boolean = true,
    val streakCelebrations: Boolean = true,
    val showProgressBars: Boolean = true,
    val forgivenessStreaks: Boolean = true,
    // --- Focus & Release Mode sub-settings ---
    // Good Enough Timers
    val goodEnoughTimersEnabled: Boolean = true,
    val defaultGoodEnoughMinutes: Int = 30,
    val goodEnoughEscalation: GoodEnoughEscalation = GoodEnoughEscalation.NUDGE,
    // Anti-Rework Guards
    val antiReworkEnabled: Boolean = true,
    val softWarningEnabled: Boolean = true,
    val coolingOffEnabled: Boolean = false,
    val coolingOffMinutes: Int = 30,
    val revisionCounterEnabled: Boolean = false,
    val maxRevisions: Int = 3,
    // Ship-It Celebrations
    val shipItCelebrationsEnabled: Boolean = true,
    val celebrationIntensity: CelebrationIntensity = CelebrationIntensity.MEDIUM
)

/**
 * Returns true when reward animations (task completion, streak milestones) should
 * play. Reward animations always respect their own toggle regardless of
 * [NdPreferences.reduceAnimations] — the two are independent concerns.
 *
 * When both ADHD Mode and Calm Mode are active, idle/transition animations are
 * suppressed (reduceAnimations = true) but completion celebrations still play in a
 * simplified form (e.g., checkmark scale-up with glow instead of confetti).
 */
fun shouldShowRewardAnimation(prefs: NdPreferences): Boolean =
    prefs.completionAnimations

/**
 * Returns the effective [CelebrationIntensity] for Ship-It Celebrations.
 * When Calm Mode is active, intensity is forced to [CelebrationIntensity.LOW]
 * regardless of the user's configured preference.
 */
fun effectiveCelebrationIntensity(prefs: NdPreferences): CelebrationIntensity =
    if (prefs.calmModeEnabled) CelebrationIntensity.LOW else prefs.celebrationIntensity

/**
 * Resolves which celebration system fires when a task is completed.
 * Ship-It celebrations take priority over ADHD completion celebrations when
 * Focus & Release Mode is active, to avoid double-firing.
 *
 * Returns `true` if Ship-It celebrations should fire, `false` if ADHD
 * celebrations (or no celebrations) should fire instead.
 */
fun shouldFireShipItCelebration(prefs: NdPreferences): Boolean =
    prefs.focusReleaseModeEnabled && prefs.shipItCelebrationsEnabled

/**
 * Returns true if any of the three ND modes is active.
 */
fun isAnyNdModeActive(prefs: NdPreferences): Boolean =
    prefs.adhdModeEnabled || prefs.calmModeEnabled || prefs.focusReleaseModeEnabled
