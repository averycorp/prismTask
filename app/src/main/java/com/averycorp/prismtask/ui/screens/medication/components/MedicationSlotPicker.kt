package com.averycorp.prismtask.ui.screens.medication.components

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.formatHhMm
import com.averycorp.prismtask.ui.components.parseHhMm
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import kotlinx.coroutines.flow.collectLatest

/**
 * Selection summary for a single (medication ↔ slot) link, used by the
 * picker as the source-of-truth output. `overrideIdealTime` and
 * `overrideDriftMinutes` are populated only when the user toggles the
 * "Use different time" switch for that specific slot — null fields mean
 * "inherit slot defaults".
 */
data class MedicationSlotSelection(
    val slotId: Long,
    val overrideIdealTime: String? = null,
    val overrideDriftMinutes: Int? = null
) {
    val hasOverride: Boolean
        get() = overrideIdealTime != null || overrideDriftMinutes != null
}

/**
 * Slot picker for the medication create / edit flow. Shows every active
 * slot with a checkbox; checked slots can expand a "Use different time
 * for this med" toggle that surfaces per-link override fields.
 *
 * The picker is purely controlled — selection state lives in the parent
 * (typically a viewmodel for the medication editor), and the parent
 * persists changes via `MedicationSlotRepository.replaceLinksForMedication`
 * + `upsertOverride` / `deleteOverrideForPair` on save. PR2 ships this
 * composable; PR3 wires it into the rebuilt MedDialog.
 *
 * The "Add new slot" affordance hands off to [onCreateNewSlot] so the
 * call site can either route to the Settings → Medication Slots editor
 * or open an inline create sheet — both flows already exist.
 */
@Composable
fun MedicationSlotPicker(
    activeSlots: List<MedicationSlotEntity>,
    selections: List<MedicationSlotSelection>,
    onSelectionsChange: (List<MedicationSlotSelection>) -> Unit,
    onCreateNewSlot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectionById = selections.associateBy { it.slotId }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Slots",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (activeSlots.isEmpty()) {
            EmptyPickerState(onCreateNewSlot = onCreateNewSlot)
        } else {
            activeSlots.forEach { slot ->
                val selection = selectionById[slot.id]
                SlotRow(
                    slot = slot,
                    selection = selection,
                    onCheckedChange = { checked ->
                        val next = if (checked) {
                            selections + MedicationSlotSelection(slotId = slot.id)
                        } else {
                            selections.filter { it.slotId != slot.id }
                        }
                        onSelectionsChange(next)
                    },
                    onOverrideChange = { overrideTime, overrideDrift ->
                        val next = selections.map {
                            if (it.slotId == slot.id) {
                                it.copy(
                                    overrideIdealTime = overrideTime,
                                    overrideDriftMinutes = overrideDrift
                                )
                            } else {
                                it
                            }
                        }
                        onSelectionsChange(next)
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            AssistChip(
                onClick = onCreateNewSlot,
                label = { Text("Add Slot") },
                leadingIcon = {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                colors = AssistChipDefaults.assistChipColors()
            )
        }
    }
}

@Composable
private fun SlotRow(
    slot: MedicationSlotEntity,
    selection: MedicationSlotSelection?,
    onCheckedChange: (Boolean) -> Unit,
    onOverrideChange: (overrideTime: String?, overrideDrift: Int?) -> Unit
) {
    val checked = selection != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            ).border(
                width = if (checked) 1.5.dp else 1.dp,
                color = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = slot.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${slot.idealTime} · ±${slot.driftMinutes}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (checked && selection?.hasOverride == true) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Custom time set",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        AnimatedVisibility(visible = checked) {
            OverrideEditor(
                slot = slot,
                selection = selection,
                onOverrideChange = onOverrideChange
            )
        }
    }
}

@Composable
private fun OverrideEditor(
    slot: MedicationSlotEntity,
    selection: MedicationSlotSelection?,
    onOverrideChange: (overrideTime: String?, overrideDrift: Int?) -> Unit
) {
    val sel = selection ?: return
    var overrideEnabled by remember(sel.hasOverride) { mutableStateOf(sel.hasOverride) }
    val (initialHour, initialMinute) = remember(sel.overrideIdealTime, slot.idealTime) {
        parseHhMm(sel.overrideIdealTime ?: slot.idealTime) ?: (9 to 0)
    }
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val clockState = rememberAnalogClockState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        initialSecond = 0,
        is24Hour = is24Hour
    )
    val timeInput = formatHhMm(clockState.hour, clockState.minute)
    var driftInput by remember(sel.overrideDriftMinutes) {
        mutableStateOf((sel.overrideDriftMinutes ?: slot.driftMinutes).toString())
    }

    // Push hour/minute changes upstream on every dial movement so the
    // parent's selection list stays in sync without requiring a "Save"
    // tap inside the row. Lifecycle-scoped so it tears down when the
    // override is collapsed.
    LaunchedEffect(overrideEnabled) {
        if (!overrideEnabled) return@LaunchedEffect
        snapshotFlow { clockState.hour to clockState.minute }.collectLatest { (h, m) ->
            onOverrideChange(formatHhMm(h, m), driftInput.toIntOrNull())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Use different time for this med",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    val next = !overrideEnabled
                    overrideEnabled = next
                    if (next) {
                        // First-enable: seed the override with slot defaults so the
                        // editor is non-empty, then notify upstream.
                        onOverrideChange(timeInput, driftInput.toIntOrNull())
                    } else {
                        onOverrideChange(null, null)
                    }
                }
            ) {
                Text(if (overrideEnabled) "Clear" else "Set")
            }
        }
        if (overrideEnabled) {
            Text(
                text = "Override time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnalogClockPicker(
                state = clockState,
                diameter = 200.dp,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = driftInput,
                onValueChange = { raw ->
                    driftInput = raw.filter { it.isDigit() }.take(4)
                    onOverrideChange(timeInput, driftInput.toIntOrNull())
                },
                label = { Text("Override drift (minutes)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyPickerState(onCreateNewSlot: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "No medication slots yet.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Create a slot in Settings → Medication Slots.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onCreateNewSlot) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.size(4.dp))
            Text("Create Slot")
        }
    }
}
