package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.CustomBrainMode

/**
 * F4 Item 5 — additive Custom Brain Modes settings sub-section.
 *
 * Sits below the three built-in Brain Mode toggles (Quick-Start / Calm /
 * Focus & Release) in [BrainModeScreen]. Lets the user define additional
 * named modes that describe their own brain-state preferences. v1 stores
 * a name + short description + a coarse "gentle notifications" hint;
 * dispatch wiring beyond informational display is out of scope (the 31
 * existing call sites of the built-in mode booleans are not touched —
 * operator hard constraint: "Item 5 extends, does not replace defaults").
 */
@Composable
fun CustomBrainModeSubSection(
    modes: List<CustomBrainMode>,
    onAdd: (CustomBrainMode) -> Unit,
    onRemove: (String) -> Unit
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
        text = "Name brain-states that matter to you. v1 stores them as labels — dispatch is informational; defaults stay in charge.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )

    Spacer(modifier = Modifier.height(8.dp))

    if (modes.isEmpty()) {
        Text(
            text = "No custom modes yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            modes.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (mode.description.isNotBlank()) {
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (mode.gentleNotifications) {
                            Text(
                                text = "Gentle notifications",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(mode.name) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${mode.name}")
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    var showAdd by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showAdd = true }) {
        Text("Add A Mode")
    }
    if (showAdd) {
        AddBrainModeDialog(
            onConfirm = { mode ->
                onAdd(mode)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }
}

@Composable
private fun AddBrainModeDialog(
    onConfirm: (CustomBrainMode) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var gentle by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Brain Mode") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("Name") },
                    singleLine = true,
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(
                            CustomBrainMode(
                                name = trimmed,
                                description = description.trim(),
                                gentleNotifications = gentle
                            )
                        )
                    } else {
                        onDismiss()
                    }
                }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
