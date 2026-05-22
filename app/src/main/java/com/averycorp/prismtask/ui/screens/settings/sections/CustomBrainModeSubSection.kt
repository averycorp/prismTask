package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.CustomBrainMode

/**
 * F4 Item 5 — Custom Brain Modes settings sub-section (v2 dispatch).
 *
 * Lists user-defined modes alongside the three built-in toggles
 * (Quick-Start / Calm / Focus & Release). v2 lifts the v1 informational-
 * only deferral: each mode carries optional per-field overrides for the
 * ND preferences toggle surface, and exactly one mode at a time can be
 * marked active, in which case its overrides layer onto the effective
 * ND prefs at dispatch sites (batch-preview simplified-UI, widget
 * empty-state quiet-mode read, future use case wiring) via the
 * [com.averycorp.prismtask.data.preferences.BrainModeResolver].
 *
 * The mode list is rendered as a column of rows, each with an Active
 * filter chip, an Edit pencil, and a Delete trash icon. The Add dialog
 * and the Edit dialog share the same form (the Edit dialog pre-populates
 * with the existing mode and dispatches to `onUpdate`; the Add dialog
 * starts from a blank `CustomBrainMode` and dispatches to `onAdd`). Both
 * dialogs use a scrollable column so the 14 overlay toggles fit on
 * smaller screens without clipping the confirm / cancel buttons.
 */
@Composable
fun CustomBrainModeSubSection(
    modes: List<CustomBrainMode>,
    activeName: String?,
    onAdd: (CustomBrainMode) -> Unit,
    onUpdate: (CustomBrainMode) -> Unit,
    onRemove: (String) -> Unit,
    onSetActive: (String) -> Unit,
    onClearActive: () -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Your Own Modes",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = "Define modes with override toggles. Mark one Active to layer its overrides onto your base settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )

    Spacer(modifier = Modifier.height(8.dp))

    var editing by remember { mutableStateOf<CustomBrainMode?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    if (modes.isEmpty()) {
        Text(
            text = "No custom modes yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            modes.forEach { mode ->
                val isActive = activeName?.equals(mode.name, ignoreCase = true) == true
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (mode.description.isNotBlank()) {
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val overrideCount = mode.countOverrides()
                        if (overrideCount > 0) {
                            Text(
                                text = "$overrideCount override${if (overrideCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    FilterChip(
                        selected = isActive,
                        onClick = {
                            if (isActive) onClearActive() else onSetActive(mode.name)
                        },
                        label = { Text(if (isActive) "Active" else "Activate") }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { editing = mode }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${mode.name}")
                    }
                    IconButton(onClick = { onRemove(mode.name) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${mode.name}")
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedButton(onClick = { showAdd = true }) {
        Text("Add A Mode")
    }
    if (showAdd) {
        BrainModeFormDialog(
            initial = CustomBrainMode(name = "", description = ""),
            isEdit = false,
            existingNames = modes.map { it.name },
            onConfirm = { mode ->
                onAdd(mode)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }
    editing?.let { current ->
        BrainModeFormDialog(
            initial = current,
            isEdit = true,
            existingNames = modes.filterNot { it.name == current.name }.map { it.name },
            onConfirm = { mode ->
                onUpdate(mode)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

private fun CustomBrainMode.countOverrides(): Int = listOf(
    adhdModeEnabledOverride,
    calmModeEnabledOverride,
    focusReleaseModeEnabledOverride,
    reduceAnimationsOverride,
    mutedColorPaletteOverride,
    quietModeOverride,
    reduceHapticsOverride,
    softContrastOverride,
    completionAnimationsOverride,
    streakCelebrationsOverride,
    showProgressBarsOverride,
    goodEnoughTimersEnabledOverride,
    antiReworkEnabledOverride,
    shipItCelebrationsEnabledOverride
).count { it != null }

@Composable
private fun BrainModeFormDialog(
    initial: CustomBrainMode,
    isEdit: Boolean,
    existingNames: List<String>,
    onConfirm: (CustomBrainMode) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var gentle by remember { mutableStateOf(initial.gentleNotifications) }
    var adhd by remember { mutableStateOf(initial.adhdModeEnabledOverride) }
    var calm by remember { mutableStateOf(initial.calmModeEnabledOverride) }
    var focusRelease by remember { mutableStateOf(initial.focusReleaseModeEnabledOverride) }
    var reduceAnimations by remember { mutableStateOf(initial.reduceAnimationsOverride) }
    var mutedColors by remember { mutableStateOf(initial.mutedColorPaletteOverride) }
    var quietMode by remember { mutableStateOf(initial.quietModeOverride) }
    var reduceHaptics by remember { mutableStateOf(initial.reduceHapticsOverride) }
    var softContrast by remember { mutableStateOf(initial.softContrastOverride) }
    var completionAnimations by remember {
        mutableStateOf(initial.completionAnimationsOverride)
    }
    var streakCelebrations by remember { mutableStateOf(initial.streakCelebrationsOverride) }
    var showProgressBars by remember { mutableStateOf(initial.showProgressBarsOverride) }
    var goodEnoughTimers by remember {
        mutableStateOf(initial.goodEnoughTimersEnabledOverride)
    }
    var antiRework by remember { mutableStateOf(initial.antiReworkEnabledOverride) }
    var shipItCelebrations by remember {
        mutableStateOf(initial.shipItCelebrationsEnabledOverride)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Brain Mode" else "New Brain Mode") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    // name is the key — editing it would orphan the active pointer
                    enabled = !isEdit,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 120) description = it },
                    label = { Text("Description (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = gentle, onCheckedChange = { gentle = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Gentle notifications hint",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Overrides (Inherit Means \"No Change To Base\")",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Modes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OverrideRow(label = "ADHD mode", state = adhd, onChange = { adhd = it })
                OverrideRow(label = "Calm mode", state = calm, onChange = { calm = it })
                OverrideRow(
                    label = "Focus & Release mode",
                    state = focusRelease,
                    onChange = { focusRelease = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Calm Sub-Settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OverrideRow(
                    label = "Reduce animations",
                    state = reduceAnimations,
                    onChange = { reduceAnimations = it }
                )
                OverrideRow(
                    label = "Muted colors",
                    state = mutedColors,
                    onChange = { mutedColors = it }
                )
                OverrideRow(
                    label = "Quiet mode",
                    state = quietMode,
                    onChange = { quietMode = it }
                )
                OverrideRow(
                    label = "Reduce haptics",
                    state = reduceHaptics,
                    onChange = { reduceHaptics = it }
                )
                OverrideRow(
                    label = "Soft contrast",
                    state = softContrast,
                    onChange = { softContrast = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ADHD Sub-Settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OverrideRow(
                    label = "Completion animations",
                    state = completionAnimations,
                    onChange = { completionAnimations = it }
                )
                OverrideRow(
                    label = "Streak celebrations",
                    state = streakCelebrations,
                    onChange = { streakCelebrations = it }
                )
                OverrideRow(
                    label = "Show progress bars",
                    state = showProgressBars,
                    onChange = { showProgressBars = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Focus & Release Sub-Settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OverrideRow(
                    label = "Good-enough timers",
                    state = goodEnoughTimers,
                    onChange = { goodEnoughTimers = it }
                )
                OverrideRow(
                    label = "Anti-rework guard",
                    state = antiRework,
                    onChange = { antiRework = it }
                )
                OverrideRow(
                    label = "Ship-It celebrations",
                    state = shipItCelebrations,
                    onChange = { shipItCelebrations = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    val isDuplicate = !isEdit && existingNames.any {
                        it.equals(trimmed, ignoreCase = true)
                    }
                    if (trimmed.isNotEmpty() && !isDuplicate) {
                        onConfirm(
                            CustomBrainMode(
                                name = trimmed,
                                description = description.trim(),
                                gentleNotifications = gentle,
                                adhdModeEnabledOverride = adhd,
                                calmModeEnabledOverride = calm,
                                focusReleaseModeEnabledOverride = focusRelease,
                                reduceAnimationsOverride = reduceAnimations,
                                mutedColorPaletteOverride = mutedColors,
                                quietModeOverride = quietMode,
                                reduceHapticsOverride = reduceHaptics,
                                softContrastOverride = softContrast,
                                completionAnimationsOverride = completionAnimations,
                                streakCelebrationsOverride = streakCelebrations,
                                showProgressBarsOverride = showProgressBars,
                                goodEnoughTimersEnabledOverride = goodEnoughTimers,
                                antiReworkEnabledOverride = antiRework,
                                shipItCelebrationsEnabledOverride = shipItCelebrations
                            )
                        )
                    } else {
                        onDismiss()
                    }
                }
            ) { Text(if (isEdit) "Save" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Tri-state chip group for a single nullable boolean override.
 * `null` = inherit base, `true` = force on, `false` = force off.
 */
@Composable
private fun OverrideRow(
    label: String,
    state: Boolean?,
    onChange: (Boolean?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        AssistChip(
            onClick = { onChange(null) },
            label = { Text("—") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (state == null) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        AssistChip(
            onClick = { onChange(true) },
            label = { Text("On") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (state == true) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        AssistChip(
            onClick = { onChange(false) },
            label = { Text("Off") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (state == false) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        )
    }
}
