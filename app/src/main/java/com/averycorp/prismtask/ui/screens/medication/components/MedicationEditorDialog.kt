package com.averycorp.prismtask.ui.screens.medication.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.domain.model.medication.MedicationTier

/**
 * Per-medication reminder-mode override choice. Mirrors
 * `SlotReminderModeChoice` for the slot editor but lives in the
 * medication-editor package so it doesn't pull in the settings layer.
 *  - INHERIT persists as `reminder_mode = NULL` (medication inherits
 *    its slot's mode, then the global default).
 *  - CLOCK / INTERVAL persist as the literal column value.
 */
internal enum class MedicationReminderModeChoice { INHERIT, CLOCK, INTERVAL }

internal fun reminderModeChoiceFromString(raw: String?): MedicationReminderModeChoice = when (raw) {
    "CLOCK" -> MedicationReminderModeChoice.CLOCK
    "INTERVAL" -> MedicationReminderModeChoice.INTERVAL
    else -> MedicationReminderModeChoice.INHERIT
}

internal fun reminderModeChoiceToString(choice: MedicationReminderModeChoice): String? = when (choice) {
    MedicationReminderModeChoice.INHERIT -> null
    MedicationReminderModeChoice.CLOCK -> "CLOCK"
    MedicationReminderModeChoice.INTERVAL -> "INTERVAL"
}

/**
 * Replacement for the old [MedDialog]. Operates on [MedicationTier] +
 * [MedicationSlotSelection] directly rather than the legacy
 * `SelfCareStepEntity` shape, so the create / edit path persists to
 * `medications` + `medication_medication_slots` + `medication_slot_overrides`
 * instead of the `self_care_steps` / `self_care_logs.tiers_by_time` path.
 */
@Composable
fun MedicationEditorDialog(
    title: String,
    initialName: String = "",
    initialTier: MedicationTier = MedicationTier.ESSENTIAL,
    initialNotes: String = "",
    initialSelections: List<MedicationSlotSelection> = emptyList(),
    activeSlots: List<MedicationSlotEntity>,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        tier: MedicationTier,
        notes: String,
        selections: List<MedicationSlotSelection>,
        reminderMode: String?,
        reminderIntervalMinutes: Int?,
        promptDoseAtLog: Boolean
    ) -> Unit,
    onCreateNewSlot: () -> Unit,
    initialReminderMode: String? = null,
    initialReminderIntervalMinutes: Int? = null,
    initialPromptDoseAtLog: Boolean = false
) {
    var name by remember { mutableStateOf(initialName) }
    var tier by remember { mutableStateOf(initialTier) }
    var notes by remember { mutableStateOf(initialNotes) }
    var selections by remember { mutableStateOf(initialSelections) }
    var reminderModeChoice by remember {
        mutableStateOf(reminderModeChoiceFromString(initialReminderMode))
    }
    var intervalMinutes by remember {
        mutableStateOf(initialReminderIntervalMinutes ?: 240)
    }
    var customIntervalText by remember(intervalMinutes) {
        mutableStateOf(intervalMinutes.toString())
    }
    var promptDoseAtLog by remember { mutableStateOf(initialPromptDoseAtLog) }
    val intervalPresets = listOf(120, 240, 360, 480)
    val isCustomInterval = intervalMinutes !in intervalPresets

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Medication Name") },
                    placeholder = { Text("e.g. Lamotrigine 200mg") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                MedicationTierRadio(
                    selected = tier,
                    onSelected = { tier = it },
                    modifier = Modifier.fillMaxWidth()
                )
                MedicationSlotPicker(
                    activeSlots = activeSlots,
                    selections = selections,
                    onSelectionsChange = { selections = it },
                    onCreateNewSlot = onCreateNewSlot,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    placeholder = { Text("e.g. take with food") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Reminder Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ReminderModeChip(
                        label = "Default",
                        selected = reminderModeChoice == MedicationReminderModeChoice.INHERIT,
                        onClick = { reminderModeChoice = MedicationReminderModeChoice.INHERIT }
                    )
                    ReminderModeChip(
                        label = "Clock",
                        selected = reminderModeChoice == MedicationReminderModeChoice.CLOCK,
                        onClick = { reminderModeChoice = MedicationReminderModeChoice.CLOCK }
                    )
                    ReminderModeChip(
                        label = "Interval",
                        selected = reminderModeChoice == MedicationReminderModeChoice.INTERVAL,
                        onClick = { reminderModeChoice = MedicationReminderModeChoice.INTERVAL }
                    )
                }
                Text(
                    text = when (reminderModeChoice) {
                        MedicationReminderModeChoice.INHERIT ->
                            "Inherits from the slot. If the slot also inherits, uses the app default."
                        MedicationReminderModeChoice.CLOCK ->
                            "Reminder fires at each linked slot's ideal time."
                        MedicationReminderModeChoice.INTERVAL ->
                            "Reminder fires ${formatInterval(intervalMinutes)} after the most recent dose."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reminderModeChoice == MedicationReminderModeChoice.INTERVAL) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        intervalPresets.forEach { mins ->
                            val selected = !isCustomInterval && intervalMinutes == mins
                            AssistChip(
                                onClick = { intervalMinutes = mins },
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
                            onClick = {
                                if (!isCustomInterval) {
                                    intervalMinutes = (intervalMinutes + 1).coerceIn(60, 1440)
                                    customIntervalText = intervalMinutes.toString()
                                }
                            },
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
                        val intervalOutOfRange = customIntervalText.toIntOrNull()
                            ?.let { it !in 60..1440 } == true
                        OutlinedTextField(
                            value = customIntervalText,
                            onValueChange = { raw ->
                                val update = applyMinuteFieldEdit(raw, 60, 1440)
                                customIntervalText = update.text
                                update.newMinutes?.let { intervalMinutes = it }
                            },
                            label = { Text("Custom interval (minutes, 60–1440)") },
                            isError = intervalOutOfRange,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (selections.isEmpty() && activeSlots.isNotEmpty()) {
                    Text(
                        text = "No slot picked — this medication will appear in " +
                            "the Unscheduled section as an as-needed dose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Prompt for Dose at Logging Time",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Ask for an amount (e.g. \"500 mg\") each time you log this med.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = promptDoseAtLog,
                        onCheckedChange = { promptDoseAtLog = it }
                    )
                }
            }
        },
        confirmButton = {
            // A blank name is the only Save blocker. A med with no linked
            // slot is a valid as-needed (PRN) entry — it surfaces in the
            // "Unscheduled" section of the Medication screen. The
            // duplicate-name `SQLiteConstraintException` crash that PR
            // #1141 originally guarded against here is mitigated upstream
            // by `MedicationViewModel.addMedication`'s `getByNameOnce`
            // pre-flight + outer try/catch — see
            // `docs/audits/ALLOW_UNSCHEDULED_MEDICATION_AUDIT.md`.
            Button(
                onClick = {
                    onConfirm(
                        name.trim(),
                        tier,
                        notes.trim(),
                        selections,
                        reminderModeChoiceToString(reminderModeChoice),
                        if (reminderModeChoice == MedicationReminderModeChoice.INTERVAL) intervalMinutes else null,
                        promptDoseAtLog
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

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
