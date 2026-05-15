package com.averycorp.prismtask.ui.screens.settings.sections.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.text.format.DateFormat
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.formatHhMm
import com.averycorp.prismtask.ui.components.parseHhMm
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import com.averycorp.prismtask.ui.screens.medication.components.IntervalHoursMinutesField
import com.averycorp.prismtask.ui.screens.medication.components.applyMinuteFieldEdit

/**
 * Tri-state reminder-mode selection for the slot editor:
 *  - [INHERIT] persists as `reminder_mode = NULL` and lets the user's
 *    global default win.
 *  - [CLOCK] / [INTERVAL] persist as the literal column value and override
 *    the global default. Per-medication overrides (when shipped) take
 *    precedence over both.
 */
internal enum class SlotReminderModeChoice { INHERIT, CLOCK, INTERVAL }

internal fun reminderModeStringToChoice(raw: String?): SlotReminderModeChoice = when (raw) {
    "CLOCK" -> SlotReminderModeChoice.CLOCK
    "INTERVAL" -> SlotReminderModeChoice.INTERVAL
    else -> SlotReminderModeChoice.INHERIT
}

internal fun choiceToReminderModeString(choice: SlotReminderModeChoice): String? = when (choice) {
    SlotReminderModeChoice.INHERIT -> null
    SlotReminderModeChoice.CLOCK -> "CLOCK"
    SlotReminderModeChoice.INTERVAL -> "INTERVAL"
}

/**
 * Inline editor used by `MedicationSlotsScreen` for both create and edit.
 * Shared because the two flows have identical fields and validation; only
 * the dialog title and the "save / create" button label differ.
 *
 * Drift presets cover the common product asks (±30/60/120/180 min) plus a
 * "Custom" override that opens a numeric field. The ideal-time picker is
 * the three-hand analog clock per the
 * `feedback-time-input-use-clock-not-slider` memory — the seconds hand is
 * captured but rounded out at save time because the slot's `ideal_time`
 * column is HH:mm.
 */
@Composable
internal fun MedicationSlotEditorSheet(
    title: String,
    initialName: String,
    initialIdealTime: String,
    initialDriftMinutes: Int,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        idealTime: String,
        driftMinutes: Int,
        reminderMode: String?,
        reminderIntervalMinutes: Int?
    ) -> Unit,
    initialReminderMode: String? = null,
    initialReminderIntervalMinutes: Int? = null,
    globalDefaultModeLabel: String = "Clock"
) {
    val intervalPresets = listOf(120, 240, 360, 480) // 2h / 4h / 6h / 8h
    val driftPresets = listOf(30, 60, 120, 180)
    var name by remember { mutableStateOf(initialName) }
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val (initialIdealHour, initialIdealMinute) = remember(initialIdealTime) {
        parseHhMm(initialIdealTime) ?: (9 to 0)
    }
    val idealTimeClockState = rememberAnalogClockState(
        initialHour = initialIdealHour,
        initialMinute = initialIdealMinute,
        initialSecond = 0,
        is24Hour = is24Hour
    )
    val idealTime = formatHhMm(idealTimeClockState.hour, idealTimeClockState.minute)
    var driftMinutes by remember { mutableStateOf(initialDriftMinutes) }
    var customDriftText by remember(driftMinutes) {
        mutableStateOf(driftMinutes.toString())
    }
    var reminderModeChoice by remember {
        mutableStateOf(reminderModeStringToChoice(initialReminderMode))
    }
    var intervalMinutes by remember {
        mutableStateOf(initialReminderIntervalMinutes ?: 240)
    }
    // Custom-mode visibility is explicit user state, not derived from
    // `value !in presets`. The derived shape silently hid the input
    // field whenever a typed value landed on a preset, trapping users
    // mid-keystroke (e.g. typing "1200" passes through "120"). See
    // `docs/audits/MEDICATION_CUSTOM_INTERVAL_INPUT_AUDIT.md` §
    // Anti-pattern recommendation #1.
    var isCustomInterval by remember {
        mutableStateOf((initialReminderIntervalMinutes ?: 240) !in intervalPresets)
    }
    var isCustomDrift by remember {
        mutableStateOf(initialDriftMinutes !in driftPresets)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Slot Name") },
                    placeholder = { Text("e.g. Morning") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Ideal Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AnalogClockPicker(
                    state = idealTimeClockState,
                    diameter = 220.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Drift Window",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    driftPresets.forEach { mins ->
                        val selected = !isCustomDrift && driftMinutes == mins
                        AssistChip(
                            onClick = {
                                driftMinutes = mins
                                isCustomDrift = false
                            },
                            label = { Text(formatDrift(mins)) },
                            colors = if (selected) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                AssistChipDefaults.assistChipColors()
                            }
                        )
                    }
                    AssistChip(
                        onClick = { isCustomDrift = true },
                        label = { Text("Custom") },
                        colors = if (isCustomDrift) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        }
                    )
                }
                if (isCustomDrift) {
                    val driftOutOfRange = customDriftText.toIntOrNull()
                        ?.let { it !in 1..1440 } == true
                    OutlinedTextField(
                        value = customDriftText,
                        onValueChange = { raw ->
                            val update = applyMinuteFieldEdit(raw, 1, 1440)
                            customDriftText = update.text
                            update.newMinutes?.let { driftMinutes = it }
                        },
                        label = { Text("Custom drift (minutes)") },
                        isError = driftOutOfRange,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "A drift window of ${formatDrift(driftMinutes)} means a dose " +
                        "counts as on-time if logged within ${formatDrift(driftMinutes)} of $idealTime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Reminder Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ReminderModeChip(
                        label = "Default",
                        selected = reminderModeChoice == SlotReminderModeChoice.INHERIT,
                        onClick = { reminderModeChoice = SlotReminderModeChoice.INHERIT }
                    )
                    ReminderModeChip(
                        label = "Clock",
                        selected = reminderModeChoice == SlotReminderModeChoice.CLOCK,
                        onClick = { reminderModeChoice = SlotReminderModeChoice.CLOCK }
                    )
                    ReminderModeChip(
                        label = "Interval",
                        selected = reminderModeChoice == SlotReminderModeChoice.INTERVAL,
                        onClick = { reminderModeChoice = SlotReminderModeChoice.INTERVAL }
                    )
                }
                Text(
                    text = when (reminderModeChoice) {
                        SlotReminderModeChoice.INHERIT ->
                            "Uses the app default ($globalDefaultModeLabel)."
                        SlotReminderModeChoice.CLOCK ->
                            "Reminder fires at $idealTime."
                        SlotReminderModeChoice.INTERVAL ->
                            "Reminder fires ${formatInterval(intervalMinutes)} after the most recent dose."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reminderModeChoice == SlotReminderModeChoice.INTERVAL) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        intervalPresets.forEach { mins ->
                            val selected = !isCustomInterval && intervalMinutes == mins
                            AssistChip(
                                onClick = {
                                    intervalMinutes = mins
                                    isCustomInterval = false
                                },
                                label = { Text(formatInterval(mins)) },
                                colors = if (selected) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                }
                            )
                        }
                        AssistChip(
                            onClick = { isCustomInterval = true },
                            label = { Text("Custom") },
                            colors = if (isCustomInterval) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                AssistChipDefaults.assistChipColors()
                            }
                        )
                    }
                    if (isCustomInterval) {
                        IntervalHoursMinutesField(
                            totalMinutes = intervalMinutes,
                            onTotalMinutesChange = { intervalMinutes = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        name.trim(),
                        idealTime,
                        driftMinutes,
                        choiceToReminderModeString(reminderModeChoice),
                        if (reminderModeChoice == SlotReminderModeChoice.INTERVAL) intervalMinutes else null
                    )
                },
                enabled = name.isNotBlank() && driftMinutes >= 1,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDrift(mins: Int): String = "±${mins}m"

@Composable
private fun ReminderModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            AssistChipDefaults.assistChipColors()
        }
    )
}

private fun formatInterval(mins: Int): String = when {
    mins % 60 == 0 -> "${mins / 60}h"
    else -> "${mins}m"
}
