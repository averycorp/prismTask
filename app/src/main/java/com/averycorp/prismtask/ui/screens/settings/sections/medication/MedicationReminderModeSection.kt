package com.averycorp.prismtask.ui.screens.settings.sections.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.data.preferences.MedicationReminderMode
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.screens.medication.components.IntervalHoursMinutesField
import kotlinx.coroutines.launch

/**
 * Settings section for the global default medication reminder mode.
 * Per-slot and per-medication overrides win over this default; users
 * who want CLOCK everywhere just leave it on the default and never
 * touch the slot editor's "Default / Clock / Interval" picker.
 *
 * Self-contained — owns its own ViewModel via Hilt rather than
 * accepting state through a long parameter list, which keeps
 * NotificationsScreen's already-large signature manageable.
 */
@Composable
fun MedicationReminderModeSection(viewModel: MedicationReminderModeSettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SectionHeader("Medication Reminders")

    Text(
        text = "Default reminder mode for medication slots. Each slot can " +
            "override this in Settings → Medication Slots.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ModeChip(
            label = "Clock",
            selected = prefs.mode == MedicationReminderMode.CLOCK,
            onClick = {
                scope.launch {
                    viewModel.save(prefs.copy(mode = MedicationReminderMode.CLOCK))
                }
            }
        )
        ModeChip(
            label = "Interval",
            selected = prefs.mode == MedicationReminderMode.INTERVAL,
            onClick = {
                scope.launch {
                    viewModel.save(prefs.copy(mode = MedicationReminderMode.INTERVAL))
                }
            }
        )
    }

    Text(
        text = when (prefs.mode) {
            MedicationReminderMode.CLOCK ->
                "Reminders fire at each slot's ideal time."
            MedicationReminderMode.INTERVAL ->
                "Reminders fire ${formatInterval(prefs.intervalDefaultMinutes)} after the most recent dose."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )

    if (prefs.mode == MedicationReminderMode.INTERVAL) {
        IntervalPicker(
            currentMinutes = prefs.intervalDefaultMinutes,
            onSave = { minutes ->
                scope.launch {
                    viewModel.save(prefs.copy(intervalDefaultMinutes = minutes))
                }
            }
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun IntervalPicker(currentMinutes: Int, onSave: (Int) -> Unit) {
    val presets = listOf(120, 240, 360, 480) // 2h / 4h / 6h / 8h
    // Custom-mode visibility is explicit user state, not derived from
    // `currentMinutes !in presets`. The derived shape silently hid the
    // input field whenever a typed value landed on a preset (e.g. typing
    // toward 1200 passes through 120), trapping the user mid-keystroke.
    // See `docs/audits/MEDICATION_CUSTOM_INTERVAL_INPUT_AUDIT.md` §
    // Anti-pattern recommendation #1.
    var isCustom by remember { mutableStateOf(currentMinutes !in presets) }

    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            presets.forEach { mins ->
                val selected = !isCustom && currentMinutes == mins
                AssistChip(
                    onClick = {
                        isCustom = false
                        onSave(mins)
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
                onClick = { isCustom = true },
                label = { Text("Custom") },
                colors = if (isCustom) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                }
            )
        }
        if (isCustom) {
            IntervalHoursMinutesField(
                totalMinutes = currentMinutes,
                onTotalMinutesChange = onSave,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatInterval(mins: Int): String = when {
    mins % 60 == 0 -> "${mins / 60}h"
    else -> "${mins}m"
}
