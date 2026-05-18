package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.HabitEntity

/**
 * Resolves per-habit forgiveness/streak overrides against the global defaults.
 *
 * Mirrors the shape of [HabitTodayVisibilityResolver] and the `-1 = inherit`
 * sentinel idiom used by [HabitEntity.todaySkipAfterCompleteDays] /
 * [HabitEntity.todaySkipBeforeScheduleDays].
 *
 * Sentinel semantics:
 *  - [HabitEntity.streakMaxMissedDays]: `-1` inherits the global; `>= 1` is
 *    the per-habit grace value (the slider clamps to 1..7 in the editor, but
 *    the resolver only requires `>= 1` so an out-of-range stored value still
 *    wins over the global).
 *  - [HabitEntity.forgivenessEnabled]: three-state because the global toggle
 *    is independent of the slider values. `-1` inherits, `0` forces the
 *    forgiveness window off for this habit, `1` forces it on. Any other
 *    value also falls back to the global.
 *  - [HabitEntity.forgivenessAllowedMisses]: `-1` inherits; `>= 0` is the
 *    per-habit budget. The lower bound is zero (not one) because opting in
 *    to a zero-miss budget for a strict-essentials habit is a valid choice
 *    that the global default cannot express on its own.
 *  - [HabitEntity.forgivenessGracePeriodDays]: `-1` inherits; `>= 1` is the
 *    per-habit window length in days.
 */
object HabitForgivenessResolver {
    fun resolveMaxMissedDays(habit: HabitEntity, globalDefault: Int): Int =
        if (habit.streakMaxMissedDays >= 1) habit.streakMaxMissedDays else globalDefault

    fun resolveForgivenessConfig(
        habit: HabitEntity,
        globalConfig: ForgivenessConfig
    ): ForgivenessConfig {
        val enabled = when (habit.forgivenessEnabled) {
            0 -> false
            1 -> true
            else -> globalConfig.enabled
        }
        val allowed = if (habit.forgivenessAllowedMisses >= 0) {
            habit.forgivenessAllowedMisses
        } else {
            globalConfig.allowedMisses
        }
        val window = if (habit.forgivenessGracePeriodDays >= 1) {
            habit.forgivenessGracePeriodDays
        } else {
            globalConfig.gracePeriodDays
        }
        return ForgivenessConfig(
            enabled = enabled,
            allowedMisses = allowed,
            gracePeriodDays = window
        )
    }
}
