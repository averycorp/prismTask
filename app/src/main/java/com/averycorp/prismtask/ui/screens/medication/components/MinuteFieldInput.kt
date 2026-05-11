package com.averycorp.prismtask.ui.screens.medication.components

/**
 * Result of applying a single keystroke to a custom-minutes TextField (e.g.,
 * the medication interval and drift pickers).
 *
 * @property text What the field should display next (digits-only, capped to 4).
 * @property newMinutes The integer to commit to upstream state, or null if the
 *   parsed value is missing/out-of-range — the caller must NOT advance state on
 *   null, otherwise a downstream `coerceIn` round-trip would re-key the field
 *   and silently rewrite intermediate user input. Reproduced and locked in by
 *   `MinuteFieldInputTest.intermediateDigitBelowMinimumIsHeld_notCoercedUp`.
 * @property outOfRange True when the user has typed something the field can
 *   parse but that falls outside `[minMinutes, maxMinutes]`. Drives `isError`.
 */
internal data class MinuteFieldUpdate(val text: String, val newMinutes: Int?, val outOfRange: Boolean)

internal fun applyMinuteFieldEdit(
    raw: String,
    minMinutes: Int,
    maxMinutes: Int
): MinuteFieldUpdate {
    val sanitized = raw.filter { it.isDigit() }.take(4)
    val parsed = sanitized.toIntOrNull()
    val inRange = parsed != null && parsed in minMinutes..maxMinutes
    return MinuteFieldUpdate(
        text = sanitized,
        newMinutes = if (inRange) parsed else null,
        outOfRange = parsed != null && !inRange
    )
}
