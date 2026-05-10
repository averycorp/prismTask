package com.averycorp.prismtask.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.averycorp.prismtask.data.local.entity.SavedFilterEntity

/**
 * Compact preset row for the FilterPanel sheet. Shows existing presets as
 * tappable chips (long-press to delete) plus a "Save as Preset…" affordance
 * gated on whether the current filter is non-default.
 *
 * Hidden entirely when the user has no saved presets AND the current
 * filter is empty — the unconfigured FilterPanel stays as light as before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedFilterPresetsRow(
    presets: List<SavedFilterEntity>,
    canSaveCurrent: Boolean,
    onApply: (SavedFilterEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onSaveCurrent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (presets.isEmpty() && !canSaveCurrent) return

    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SavedFilterEntity?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Saved Presets",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp)
        ) {
            items(presets, key = { it.id }) { preset ->
                PresetChip(
                    label = preset.iconEmoji.orEmpty().let { emoji ->
                        if (emoji.isBlank()) preset.name else "$emoji  ${preset.name}"
                    },
                    onClick = { onApply(preset) },
                    onLongClick = { pendingDelete = preset }
                )
            }
            if (canSaveCurrent) {
                item {
                    PresetChip(
                        label = "Save as Preset…",
                        leadingIcon = Icons.Filled.BookmarkAdd,
                        onClick = { showSaveDialog = true },
                        onLongClick = null
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveFilterPresetDialog(
            existingNames = presets.map { it.name },
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                onSaveCurrent(name)
                showSaveDialog = false
            }
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Preset?") },
            text = { Text("Remove the \"${toDelete.name}\" filter preset?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(toDelete.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val container = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .height(32.dp)
            .background(container, RoundedCornerShape(50))
            .border(1.dp, outline, RoundedCornerShape(50))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun SaveFilterPresetDialog(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val collision = trimmed.isNotEmpty() &&
        existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canSubmit = trimmed.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Filter Preset") },
        text = {
            Column {
                Text(
                    text = "Name this preset so you can re-apply the current filter later.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    isError = collision,
                    supportingText = if (collision) {
                        { Text("A preset with this name already exists. Saving overwrites it.") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConfirm(trimmed) }
            ) { Text(if (collision) "Overwrite" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
