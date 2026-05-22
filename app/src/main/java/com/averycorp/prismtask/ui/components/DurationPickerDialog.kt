package com.averycorp.prismtask.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Slider-backed picker for a task length / duration value in MINUTES. Used
 * in Settings → Global Defaults → "Default Task Length" so Free users can
 * adjust the per-task fallback the balance + cognitive-load trackers use
 * when a task has no `estimatedDuration`.
 *
 * Sliders (not the analog clock used for time-of-day inputs) are the right
 * primitive here — this is a duration scalar, not a wall-clock time.
 */
@Composable
fun DurationPickerDialog(
    initialMinutes: Int,
    minMinutes: Int = 5,
    maxMinutes: Int = 240,
    step: Int = 5,
    title: String = "Default Task Length",
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableIntStateOf(initialMinutes.coerceIn(minMinutes, maxMinutes)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = formatDurationMinutesShort(selected),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = selected.toFloat(),
                    onValueChange = { v ->
                        val snapped = (v / step).toInt() * step
                        selected = snapped.coerceIn(minMinutes, maxMinutes)
                    },
                    valueRange = minMinutes.toFloat()..maxMinutes.toFloat(),
                    steps = ((maxMinutes - minMinutes) / step) - 1
                )
                Text(
                    text = "Used as fallback length when a task has no estimate. Powers the Today balance bar " +
                        "and cognitive-load weighting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDurationMinutesShort(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}
