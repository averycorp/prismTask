package com.averycorp.prismtask.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.ui.components.ThemedSwitch
import com.averycorp.prismtask.ui.screens.settings.sections.medication.MedicationSlotEditorSheet
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

/**
 * Settings → Medication Slots editor. Lists every slot the user has
 * created, lets them rename / re-time / re-drift, drag-reorder via the
 * up/down buttons, soft-delete with confirmation, and (optionally)
 * restore a previously deleted slot.
 *
 * Soft-deleted slots are hidden by default; flipping "Show deleted"
 * surfaces them so the user can either restore one or hard-delete it
 * later. The screen never destroys tier-state history — `is_active = 0`
 * is the only persistence change for a soft-delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationSlotsScreen(
    navController: NavController,
    viewModel: MedicationSlotsViewModel = hiltViewModel()
) {
    val allSlots by viewModel.allSlots.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<MedicationSlotEntity?>(null) }
    var deletingSlot by remember { mutableStateOf<MedicationSlotEntity?>(null) }
    var showDeleted by remember { mutableStateOf(false) }

    val activeSlots = allSlots.filter { it.isActive }
    val deletedSlots = allSlots.filter { !it.isActive }
    val visibleSlots = if (showDeleted) allSlots else activeSlots

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Medication Slots") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add slot")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(
                    text = "Slots define the times of day you take medication. " +
                        "Link each medication to one or more slots, then mark doses " +
                        "as taken on the Today screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )
            }

            if (deletedSlots.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Show Deleted (${deletedSlots.size})",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        ThemedSwitch(
                            checked = showDeleted,
                            onCheckedChange = { showDeleted = it }
                        )
                    }
                }
            }

            if (visibleSlots.isEmpty()) {
                item {
                    EmptyMedicationSlotsState(onAddClick = { showAddSheet = true })
                }
            } else {
                itemsIndexed(visibleSlots = visibleSlots, activeSlots = activeSlots) { slot, indexInActive ->
                    SlotRow(
                        slot = slot,
                        canMoveUp = slot.isActive && indexInActive > 0,
                        canMoveDown = slot.isActive &&
                            indexInActive >= 0 &&
                            indexInActive < activeSlots.size - 1,
                        onEdit = { editingSlot = slot },
                        onDelete = { deletingSlot = slot },
                        onRestore = { viewModel.restore(slot.id) },
                        onMoveUp = {
                            if (indexInActive > 0) {
                                viewModel.swapOrder(slot, activeSlots[indexInActive - 1])
                            }
                        },
                        onMoveDown = {
                            if (indexInActive in 0 until activeSlots.size - 1) {
                                viewModel.swapOrder(slot, activeSlots[indexInActive + 1])
                            }
                        }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddSheet) {
        MedicationSlotEditorSheet(
            title = "New Medication Slot",
            initialName = "",
            initialIdealTime = "09:00",
            initialDriftMinutes = 180,
            confirmLabel = "Create",
            onDismiss = { showAddSheet = false },
            onConfirm = { name, idealTime, drift, reminderMode, intervalMinutes ->
                viewModel.create(name, idealTime, drift, reminderMode, intervalMinutes)
                showAddSheet = false
            }
        )
    }

    editingSlot?.let { slot ->
        MedicationSlotEditorSheet(
            title = "Edit Medication Slot",
            initialName = slot.name,
            initialIdealTime = slot.idealTime,
            initialDriftMinutes = slot.driftMinutes,
            initialReminderMode = slot.reminderMode,
            initialReminderIntervalMinutes = slot.reminderIntervalMinutes,
            confirmLabel = "Save",
            onDismiss = { editingSlot = null },
            onConfirm = { name, idealTime, drift, reminderMode, intervalMinutes ->
                viewModel.update(slot, name, idealTime, drift, reminderMode, intervalMinutes)
                editingSlot = null
            }
        )
    }

    deletingSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { deletingSlot = null },
            title = { Text("Delete Slot?") },
            text = {
                Text(
                    "\"${slot.name}\" will be hidden from medication pickers. " +
                        "Historical tier data is preserved and the slot can be " +
                        "restored later from this screen."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.softDelete(slot.id)
                        deletingSlot = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSlot = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Custom items wrapper that hands each row its index inside the active
 * sub-list — needed because the visible list mixes active + deleted but
 * the up/down buttons only operate on the active range.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    visibleSlots: List<MedicationSlotEntity>,
    activeSlots: List<MedicationSlotEntity>,
    rowContent: @Composable (slot: MedicationSlotEntity, indexInActive: Int) -> Unit
) {
    items(items = visibleSlots, key = { it.id }) { slot ->
        val indexInActive = activeSlots.indexOfFirst { it.id == slot.id }
        rowContent(slot, indexInActive)
    }
}

@Composable
private fun SlotRow(
    slot: MedicationSlotEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val containerColor = if (slot.isActive) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val titleColor = if (slot.isActive) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ).clickable(enabled = slot.isActive, onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = slot.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )
            Text(
                text = "${slot.idealTime} · ±${slot.driftMinutes}m drift",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (slot.isActive) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move up",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move down",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit ${slot.name}",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${slot.name}",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            IconButton(onClick = onRestore, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Restore,
                    contentDescription = "Restore ${slot.name}",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EmptyMedicationSlotsState(onAddClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "No medication slots yet",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Add a slot to start tracking medication times",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Add Slot")
            }
        }
    }
}
