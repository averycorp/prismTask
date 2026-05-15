package com.averycorp.prismtask.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Start-of-Day picker dialog. Wraps [AnalogClockPicker] with the
 * onboarding-friendly title and explanation text. Used by both the
 * first-launch prompt (`dismissable = false`) and the Settings screen
 * (dismissable). Persists hour + minute; seconds are picked but rounded
 * away on save — the affordance stays consistent with every other
 * time-of-day input in the app per the
 * `feedback-time-input-use-clock-not-slider` memory.
 */
@Composable
fun StartOfDayPickerDialog(
    initialHour: Int,
    initialMinute: Int,
    dismissable: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val clockState = rememberAnalogClockState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = initialMinute.coerceIn(0, 59),
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        title = {
            Text(
                text = "When Does Your Day Start?",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    text = "This controls when habits reset and streaks roll over. " +
                        "Most people pick between 3–5 AM. Calendar dates and explicit " +
                        "due dates are unaffected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AnalogClockPicker(state = clockState)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clockState.hour, clockState.minute) }) {
                Text("Set")
            }
        },
        dismissButton = if (dismissable) {
            {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        } else {
            null
        },
    )
}

/** Renders "4:00 AM" for use in Settings subtitles. */
fun formatStartOfDay(hour: Int, minute: Int): String {
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val suffix = if (hour < 12) "AM" else "PM"
    return "%d:%02d %s".format(displayHour, minute, suffix)
}
