package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Streak Pause sub-section (F4 Item 3). Lets the user mark a date range
 * as rest days so the forgiveness-first streak treats every day in the
 * range as "kept" without consuming grace. Composes with the existing
 * Rest Day primitive — the section dispatches into
 * [com.averycorp.prismtask.data.repository.RestDayRepository] for the
 * actual day marking; the pause window is remembered in
 * [com.averycorp.prismtask.data.preferences.RestDayPreferences] so the
 * inactive vs active state stays consistent across launches.
 *
 * UX: when no pause is active, a "Plan A Pause..." button opens two
 * sequential date pickers (start → end). When a pause is active, the
 * section shows the window and an "End Pause Now" button that clears
 * both the prefs window and the rest-day rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreakPauseSection(
    activeFrom: LocalDate?,
    activeTo: LocalDate?,
    onApplyPause: (LocalDate, LocalDate) -> Unit,
    onClearPause: () -> Unit
) {
    SectionHeader("Streak Pause")

    val active = activeFrom != null && activeTo != null
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }

    if (active) {
        Text(
            text = "Resting from ${activeFrom!!.format(dateFmt)} through ${activeTo!!.format(dateFmt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Your streak stays intact for every day in this window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onClearPause) {
            Text("End Pause Now")
        }
    } else {
        Text(
            text = "Planning a break? Mark a date range as resting and your streak won't break while you're away.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        var pickStart by remember { mutableStateOf(false) }
        var pickEnd by remember { mutableStateOf<LocalDate?>(null) }

        Button(onClick = { pickStart = true }) {
            Text("Plan A Pause…")
        }

        if (pickStart) {
            DatePickerForDate(
                title = "Start date",
                onDateSelected = { start ->
                    pickStart = false
                    pickEnd = start
                },
                onDismiss = { pickStart = false }
            )
        }
        val startForEnd = pickEnd
        if (startForEnd != null) {
            DatePickerForDate(
                title = "End date",
                initialMillis = startForEnd.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                onDateSelected = { end ->
                    pickEnd = null
                    val from = if (end.isBefore(startForEnd)) end else startForEnd
                    val to = if (end.isBefore(startForEnd)) startForEnd else end
                    onApplyPause(from, to)
                },
                onDismiss = { pickEnd = null }
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerForDate(
    title: String,
    initialMillis: Long? = null,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    val date = java.time.Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    onDateSelected(date)
                } else {
                    onDismiss()
                }
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
        Column {
            DatePicker(state = state)
        }
    }
}
