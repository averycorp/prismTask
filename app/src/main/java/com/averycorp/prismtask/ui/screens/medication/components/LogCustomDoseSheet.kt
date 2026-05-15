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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

/**
 * Bottom-sheet entry surface for logging a one-time custom medication
 * to the Medication Log. Three fields:
 *
 * - **Name** (required): free-text medication name. Stored verbatim in
 *   [com.averycorp.prismtask.data.local.entity.MedicationDoseEntity.customMedicationName].
 *   The Log button stays disabled until non-blank.
 * - **Time**: defaults to "Now"; tap-and-edit on the three-hand analog
 *   clock picker. Stored as wall-clock millis on today's date.
 * - **Note** (optional): up to 200 chars of free-form context.
 *
 * The sheet does not own the date — it always logs against today (or
 * the user's logical day per `DayBoundary`, which the repository
 * resolves). Back-dated entries land via the medication log screen if a
 * user needs them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogCustomDoseSheet(
    onDismiss: () -> Unit,
    onLog: (name: String, takenAtMillis: Long, note: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val nowCal = remember { Calendar.getInstance() }
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val clockState = rememberAnalogClockState(
        initialHour = nowCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = nowCal.get(Calendar.MINUTE),
        initialSecond = 0,
        is24Hour = is24Hour
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Log Custom Dose",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Add a one-time entry to your medication log without saving it as a tracked medication.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Medication Name") },
                placeholder = { Text("e.g. Tylenol 500mg") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Time",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            AnalogClockPicker(
                state = clockState,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text("Note (Optional)") },
                placeholder = { Text("Why or how — anything you want to remember") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.height(0.dp))
                Button(
                    onClick = {
                        onLog(name.trim(), todayAt(clockState.hour, clockState.minute), note.trim())
                    },
                    enabled = name.isNotBlank()
                ) { Text("Log Dose") }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Today's date at the picked wall-clock hour/minute, in device timezone. */
private fun todayAt(hour: Int, minute: Int): Long {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val time = LocalTime.of(hour, minute)
    return LocalDateTime.of(today, time).atZone(zone).toInstant().toEpochMilli()
}
