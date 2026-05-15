package com.averycorp.prismtask.ui.screens.medication.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Result of applying a keystroke to either field in
 * [IntervalHoursMinutesField]. Mirrors [MinuteFieldUpdate] but for the
 * split hours/minutes case.
 *
 * @property hoursText Next display value for the Hours field (digits-only,
 *   capped at 2).
 * @property minutesText Next display value for the Minutes field
 *   (digits-only, capped at 2).
 * @property newTotalMinutes Total minutes to commit upstream, or null when
 *   the combined value is missing or out of range. Callers MUST NOT advance
 *   state on null — same rationale as [MinuteFieldUpdate.newMinutes].
 * @property outOfRange True when the parsed combination falls outside
 *   `[minTotalMinutes, maxTotalMinutes]` or minutes ≥ 60. Drives `isError`.
 */
internal data class HoursMinutesUpdate(
    val hoursText: String,
    val minutesText: String,
    val newTotalMinutes: Int?,
    val outOfRange: Boolean
)

internal fun applyHoursMinutesEdit(
    rawHours: String,
    rawMinutes: String,
    minTotalMinutes: Int,
    maxTotalMinutes: Int
): HoursMinutesUpdate {
    val sanitizedHours = rawHours.filter { it.isDigit() }.take(2)
    val sanitizedMinutes = rawMinutes.filter { it.isDigit() }.take(2)
    val parsedHours = sanitizedHours.toIntOrNull()
    val parsedMinutes = sanitizedMinutes.toIntOrNull()
    if (parsedHours == null && parsedMinutes == null) {
        return HoursMinutesUpdate(sanitizedHours, sanitizedMinutes, null, false)
    }
    val h = parsedHours ?: 0
    val m = parsedMinutes ?: 0
    val minutesOverflow = m > 59
    val total = h * 60 + m
    val inRange = !minutesOverflow && total in minTotalMinutes..maxTotalMinutes
    return HoursMinutesUpdate(
        hoursText = sanitizedHours,
        minutesText = sanitizedMinutes,
        newTotalMinutes = if (inRange && parsedHours != null && parsedMinutes != null) total else null,
        outOfRange = !inRange
    )
}

/**
 * Two-field hours+minutes editor for medication reminder intervals.
 * Replaces the older single "Custom interval (minutes, 60–1440)" text
 * field — durations are easier to enter as `1h 30m` than as `90`.
 *
 * Each field's text state is keyed on [totalMinutes] so external preset
 * clicks ("2h", "4h") reset the displayed digits, while typing-flow
 * recompositions preserve them (the round-trip
 * `onTotalMinutesChange(total) → remember(total)` re-renders the same
 * digits the user just typed).
 */
@Composable
internal fun IntervalHoursMinutesField(
    totalMinutes: Int,
    onTotalMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minTotalMinutes: Int = 60,
    maxTotalMinutes: Int = 1440
) {
    var hoursText by remember(totalMinutes) { mutableStateOf((totalMinutes / 60).toString()) }
    var minutesText by remember(totalMinutes) { mutableStateOf((totalMinutes % 60).toString()) }

    val live = applyHoursMinutesEdit(hoursText, minutesText, minTotalMinutes, maxTotalMinutes)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = hoursText,
            onValueChange = { raw ->
                val update = applyHoursMinutesEdit(raw, minutesText, minTotalMinutes, maxTotalMinutes)
                hoursText = update.hoursText
                update.newTotalMinutes?.let(onTotalMinutesChange)
            },
            label = { Text("Hours") },
            isError = live.outOfRange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = minutesText,
            onValueChange = { raw ->
                val update = applyHoursMinutesEdit(hoursText, raw, minTotalMinutes, maxTotalMinutes)
                minutesText = update.minutesText
                update.newTotalMinutes?.let(onTotalMinutesChange)
            },
            label = { Text("Minutes") },
            isError = live.outOfRange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
}
