package com.averycorp.prismtask.ui.screens.medication.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-dose taken_at editor — long-press on a medication row opens this so
 * the user can correct the wall-clock for an already-taken dose, or
 * backdate a fresh dose to "I took it at 8am but only opened the app
 * now". Mirrors [MedicationTimeEditSheet] (which works at slot
 * intended_time granularity) but stamps `medication_doses.taken_at`
 * directly via [composeIntendedTime] for SoD-correct anchoring.
 *
 * [onRemove] is wired only when the row is being retimed for an existing
 * dose; null hides the Remove button (e.g. for un-taken rows where there
 * is nothing yet to remove).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseTimeEditSheet(
    medicationName: String,
    initialTakenAt: Long?,
    logicalDay: LocalDate,
    onDismiss: () -> Unit,
    onSave: (takenAt: Long) -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()

    val zone = remember { ZoneId.systemDefault() }
    val seed = initialTakenAt ?: System.currentTimeMillis()
    val seedTime = remember(seed) {
        Instant.ofEpochMilli(seed).atZone(zone).toLocalTime()
    }
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val clockState = rememberAnalogClockState(
        initialHour = seedTime.hour,
        initialMinute = seedTime.minute,
        initialSecond = 0,
        is24Hour = is24Hour
    )

    val title = if (initialTakenAt != null) "Edit logged time" else "Log at past time"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = medicationName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            AnalogClockPicker(state = clockState)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) {
                        Text(
                            text = "Remove",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Button(onClick = {
                    onSave(
                        composeIntendedTime(
                            pickedHour = clockState.hour,
                            pickedMinute = clockState.minute,
                            logicalDay = logicalDay,
                            nowMillis = System.currentTimeMillis(),
                            zone = zone
                        )
                    )
                }) { Text("Save") }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
