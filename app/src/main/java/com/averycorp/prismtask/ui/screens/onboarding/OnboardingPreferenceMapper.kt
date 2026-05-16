package com.averycorp.prismtask.ui.screens.onboarding

/**
 * Maps user-facing onboarding "tuning" selections to concrete preference defaults.
 *
 * Per the Mental-Health-First audit § G6: this screen is framed as *preferences,
 * not diagnoses*. Each option is a plain-language behavioral description; the
 * mapper turns the selection set into ND/forgiveness/appearance defaults the
 * user can flip back in Settings anytime.
 *
 * The audit's hard rule is that no user-facing string here may name a clinical
 * condition ("ADHD", "depression", "autism", "anxiety"). Mapping output, on
 * the other hand, may freely reference internal ND-mode flags — those are the
 * implementation handles, not the framing.
 *
 * Single source of truth: do NOT scatter the option→preference mapping across
 * the ViewModel. Add new options here.
 */
object OnboardingPreferenceMapper {

    /**
     * The six tuning options the onboarding step exposes. Order is the order
     * shown in the UI. `NONE_OF_THESE` is a single-select that overrides the
     * rest — when present in a selection set, it wipes the other selections
     * (see [resolve]).
     */
    enum class TuningOption {
        /** "I get overwhelmed by long task lists" → compact list defaults. */
        OVERWHELMED_BY_LONG_LISTS,

        /** "I lose track of time often" → 25-min check-in interval. */
        LOSE_TRACK_OF_TIME,

        /** "I have low-energy days often" → rest-day priming. */
        LOW_ENERGY_DAYS,

        /** "I prefer fewer animations and quieter colors" → reduce-animations + muted palette. */
        FEWER_ANIMATIONS_QUIETER_COLORS,

        /** "I tend to over-polish my work" → good-enough timers. */
        OVER_POLISH,

        /** Single-select sentinel — clears the other picks. */
        NONE_OF_THESE
    }

    /**
     * Concrete preference defaults derived from a tuning selection. Flags are
     * Boolean, intervals are nullable Ints (null = leave existing pref alone).
     *
     * The mapper never emits `false` for a flag — it only opts INTO a default.
     * Skipping the step or picking [TuningOption.NONE_OF_THESE] yields the
     * zero value (no writes), preserving today's hardcoded defaults.
     *
     * Scope (post-2026-05 onboarding overlap audit, findings #2 + #6 of
     * `docs/audits/ONBOARDING_OVERLAP_AUDIT.md`): the parent ND-mode flags
     * (`adhdMode`, `calmMode`, `focusReleaseMode`) and `forgivenessStreaks`
     * are intentionally absent. Those parent flags are owned by BrainModePage
     * (page 9) and the HabitsPage Forgiveness Switch (page 6); cascading
     * them from TuningPage silently overrode users who explicitly opted OUT
     * on the earlier pages.
     */
    data class Result(
        // ND sub-flags. These are owned by TuningPage alone (no parent-page
        // counterpart writes them), so writing them here doesn't conflict
        // with any earlier user choice.
        val reduceAnimations: Boolean = false,
        val mutedColorPalette: Boolean = false,
        val goodEnoughTimers: Boolean = false,
        // ADHD check-in interval (null = no change). 25 = matches NdPreferences default.
        val checkInIntervalMinutes: Int? = null,
        // Compact list default for "overwhelmed by long lists".
        val compactMode: Boolean = false,
        // Primes the user for the future Rest Day feature (G3 in the audit).
        // Persistence target: a forgiveness/rest-day priming flag the
        // forthcoming Rest Day screen reads on first launch. The mapper
        // exposes the intent — actual storage is wired in the ViewModel.
        val primeRestDay: Boolean = false
    ) {
        /** True if the result is a no-op (skip / "None of these" / empty pick). */
        val isNoOp: Boolean
            get() = this == EMPTY

        companion object {
            val EMPTY = Result()
        }
    }

    /**
     * Resolve a multi-select set into a single concrete [Result].
     *
     * Rules:
     * - Empty selection → [Result.EMPTY] (skip path).
     * - [TuningOption.NONE_OF_THESE] present → [Result.EMPTY], regardless of
     *   what else was picked. The audit specifies "None of these" as a
     *   single-select override, so we discard the rest.
     * - Otherwise, OR-merge each option's contribution.
     */
    fun resolve(selections: Set<TuningOption>): Result {
        if (selections.isEmpty()) return Result.EMPTY
        if (TuningOption.NONE_OF_THESE in selections) return Result.EMPTY

        var reduceAnimations = false
        var mutedColorPalette = false
        var goodEnoughTimers = false
        var checkInMinutes: Int? = null
        var compactMode = false
        var primeRestDay = false

        for (option in selections) {
            when (option) {
                TuningOption.OVERWHELMED_BY_LONG_LISTS -> {
                    compactMode = true
                }
                TuningOption.LOSE_TRACK_OF_TIME -> {
                    // Parent `adhdMode` is intentionally not written — see
                    // the [Result] KDoc. Only the check-in cadence sub-flag.
                    checkInMinutes = 25
                }
                TuningOption.LOW_ENERGY_DAYS -> {
                    // Parent `ForgivenessPrefs.enabled` is intentionally not
                    // written — see the [Result] KDoc. Only the rest-day
                    // priming flag.
                    primeRestDay = true
                }
                TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS -> {
                    // Parent `calmMode` is intentionally not written — see
                    // the [Result] KDoc. Only the two sub-flags.
                    reduceAnimations = true
                    mutedColorPalette = true
                }
                TuningOption.OVER_POLISH -> {
                    // Parent `focusReleaseMode` is intentionally not written —
                    // see the [Result] KDoc. Only the good-enough-timer flag.
                    goodEnoughTimers = true
                }
                TuningOption.NONE_OF_THESE -> {
                    // Handled above; unreachable here.
                }
            }
        }

        return Result(
            reduceAnimations = reduceAnimations,
            mutedColorPalette = mutedColorPalette,
            goodEnoughTimers = goodEnoughTimers,
            checkInIntervalMinutes = checkInMinutes,
            compactMode = compactMode,
            primeRestDay = primeRestDay
        )
    }
}
