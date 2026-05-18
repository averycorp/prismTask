package com.averycorp.prismtask.ui.screens.medication

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.domain.model.medication.AchievedTier
import com.averycorp.prismtask.domain.model.medication.BulkMarkScope
import com.averycorp.prismtask.domain.model.medication.MedicationTier
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.medication.components.BulkMarkDialog
import com.averycorp.prismtask.ui.screens.medication.components.MedicationEditorDialog
import com.averycorp.prismtask.ui.screens.medication.components.MedicationSlotSelection
import com.averycorp.prismtask.ui.screens.medication.components.MedicationTimeEditSheet
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main Medication screen — rewired from the legacy
 * `SelfCareStepEntity` + `self_care_logs.tiers_by_time` JSON path to the
 * v1.5 `medications` + `medication_slots` + `medication_tier_states`
 * data model (A2 #6 PR3 + A2 #7 closeout).
 *
 * Layout: one card per active slot, grouped for today. Each card shows
 * the slot's name + ideal time + current achieved tier (auto-computed
 * from today's `medication_doses` rows). Tapping the tier chip drops
 * the slot to SKIPPED and records it as `USER_SET`; tapping again
 * clears the override. Per-med toggles below the tier chip flip a
 * `medication_doses` row for `(medication, slot, today)`.
 *
 * Edit mode surfaces an "All Medications" list with rename / archive
 * affordances via [MedicationEditorDialog]; adding a medication opens
 * the same dialog in create mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationScreen(
    navController: NavController,
    viewModel: MedicationViewModel = hiltViewModel()
) {
    val editMode by viewModel.editMode.collectAsStateWithLifecycle()
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val slots by viewModel.activeSlots.collectAsStateWithLifecycle()
    val slotStates by viewModel.slotTodayStates.collectAsStateWithLifecycle()
    val unslottedStates by viewModel.unslottedMedicationsState.collectAsStateWithLifecycle()
    val todayDateIso by viewModel.todayDate.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showBulkMarkDialog by remember { mutableStateOf(false) }
    // Editor opens only once both the medication AND its slot selections
    // are loaded, so the dialog never enters composition with a stale
    // `initialSelections` value — see [EditingMedicationState] doc.
    var editingState by remember { mutableStateOf<EditingMedicationState?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var archivingMed by remember { mutableStateOf<MedicationEntity?>(null) }
    // Slot whose intended_time is being edited (long-press → time sheet).
    var timeEditingSlotState by remember { mutableStateOf<MedicationSlotTodayState?>(null) }
    // Long-press → per-dose time-edit sheet. Target captures the
    // (medication, slot, existing-dose-if-any) tuple so the sheet can
    // either retime / remove an existing row or backdate a new one.
    var doseTimeEditTarget by remember { mutableStateOf<DoseTimeEditTarget?>(null) }
    // Unscheduled meds with >1 dose today route through a picker so the
    // user can pin which row to retime before the time sheet opens.
    var dosePickerTarget by remember { mutableStateOf<DosePickerTarget?>(null) }
    // Open the medication editor for a given med. Loads slot selections
    // first so the dialog enters composition with its final
    // `initialSelections` (see [EditingMedicationState] doc). Used by the
    // All-Medications edit row. Per-med long-press now opens the dose
    // time-edit sheet; editor access lives in edit mode.
    val openEditor: (MedicationEntity) -> Unit = { med ->
        coroutineScope.launch {
            val sels = viewModel.selectionsForMedication(med.id)
            editingState = EditingMedicationState(med, sels)
        }
    }
    // Pending dose-prompt for a (medication, optional slot) pair when the
    // medication has `promptDoseAtLog = true`. The dialog collects a
    // free-form amount, then dispatches to the appropriate record path.
    var doseDialogTarget by remember { mutableStateOf<DoseDialogTarget?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Surface viewmodel error messages (e.g. duplicate medication name) via
    // a Snackbar. Without this collector, errors emitted from
    // `addMedication` / `updateMedication` would be silently dropped.
    LaunchedEffect(Unit) {
        viewModel.errorMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Medication", fontWeight = FontWeight.Bold) },
                actions = {
                    if (!editMode) {
                        IconButton(
                            onClick = { showBulkMarkDialog = true },
                            enabled = slotStates.any { it.medications.isNotEmpty() }
                        ) {
                            Icon(
                                Icons.Default.PlaylistAddCheck,
                                contentDescription = "Bulk mark medications"
                            )
                        }
                        IconButton(onClick = {
                            navController.navigate(PrismTaskRoute.MedicationLog.route)
                        }) {
                            Icon(Icons.Default.History, contentDescription = "Medication log")
                        }
                    }
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(
                            if (editMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (editMode) "Done editing" else "Edit meds",
                            tint = if (editMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    if (editMode) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add medication")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            when {
                slots.isEmpty() -> item {
                    NoSlotsState(
                        onOpenSettings = { navController.navigate("settings/medication_slots") }
                    )
                }
                medications.isEmpty() -> item {
                    NoMedicationsState(onAddClick = { showAddDialog = true })
                }
                else -> {
                    items(slotStates, key = { it.slot.id }) { state ->
                        SlotTodayCard(
                            state = state,
                            editMode = editMode,
                            onToggleDose = { med ->
                                // Meds opted into dose-prompting collect an amount
                                // before the row lands. Already-taken rows skip the
                                // prompt (untoggling has no amount to capture).
                                val alreadyTaken = med.id in state.takenMedicationIds
                                if (med.promptDoseAtLog && !alreadyTaken) {
                                    doseDialogTarget = DoseDialogTarget(
                                        medication = med,
                                        slot = state.slot
                                    )
                                } else {
                                    viewModel.toggleDose(state.slot, med)
                                }
                            },
                            onSelectTier = { tier ->
                                // Every tier click routes through bulkMark, which logs real
                                // dose rows for meds at or below the clicked tier (or for
                                // SKIPPED, deletes real doses + writes synthetic skips).
                                // Achieved-tier display falls out of auto-compute, so there's
                                // no USER_SET override to "clear" by re-tapping the active
                                // tier — the click is idempotent.
                                viewModel.bulkMark(BulkMarkScope.SLOT, state.slot.id, tier)
                            },
                            onLongPressTier = { timeEditingSlotState = state },
                            onLongPressMedication = { med ->
                                doseTimeEditTarget = DoseTimeEditTarget(
                                    medication = med,
                                    slot = state.slot,
                                    existingDose = state.latestDoseByMedicationId[med.id]
                                )
                            }
                        )
                    }
                    if (unslottedStates.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Unscheduled",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(unslottedStates, key = { "unslotted_${it.medication.id}" }) { state ->
                            UnslottedMedicationCard(
                                state = state,
                                onRecordTaken = {
                                    if (state.medication.promptDoseAtLog) {
                                        doseDialogTarget = DoseDialogTarget(
                                            medication = state.medication,
                                            slot = null
                                        )
                                    } else {
                                        viewModel.recordUnslottedDose(state.medication)
                                    }
                                },
                                onLongPress = {
                                    when (state.dosesToday.size) {
                                        0 -> doseTimeEditTarget = DoseTimeEditTarget(
                                            medication = state.medication,
                                            slot = null,
                                            existingDose = null
                                        )
                                        1 -> doseTimeEditTarget = DoseTimeEditTarget(
                                            medication = state.medication,
                                            slot = null,
                                            existingDose = state.dosesToday.single()
                                        )
                                        else -> dosePickerTarget = DosePickerTarget(
                                            medication = state.medication,
                                            doses = state.dosesToday
                                        )
                                    }
                                }
                            )
                        }
                    }
                    if (editMode) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "All Medications",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(medications, key = { "edit_${it.id}" }) { med ->
                            MedicationEditRow(
                                medication = med,
                                onEdit = { openEditor(med) },
                                onArchive = { archivingMed = med }
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddDialog) {
        MedicationEditorDialog(
            title = "Add Medication",
            activeSlots = slots,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, tier, notes, selections, reminderMode, intervalMinutes, promptDose ->
                viewModel.addMedication(
                    name = name,
                    tier = tier,
                    notes = notes,
                    slotSelections = selections,
                    reminderMode = reminderMode,
                    reminderIntervalMinutes = intervalMinutes,
                    promptDoseAtLog = promptDose
                )
                showAddDialog = false
            },
            onCreateNewSlot = { navController.navigate("settings/medication_slots") }
        )
    }

    editingState?.let { state ->
        val med = state.medication
        MedicationEditorDialog(
            title = "Edit Medication",
            initialName = med.name,
            initialTier = MedicationTier.fromStorage(med.tier),
            initialNotes = med.notes,
            initialSelections = state.selections,
            initialReminderMode = med.reminderMode,
            initialReminderIntervalMinutes = med.reminderIntervalMinutes,
            initialPromptDoseAtLog = med.promptDoseAtLog,
            activeSlots = slots,
            onDismiss = { editingState = null },
            onConfirm = { name, tier, notes, selections, reminderMode, intervalMinutes, promptDose ->
                viewModel.updateMedication(
                    medication = med,
                    name = name,
                    tier = tier,
                    notes = notes,
                    slotSelections = selections,
                    reminderMode = reminderMode,
                    reminderIntervalMinutes = intervalMinutes,
                    promptDoseAtLog = promptDose
                )
                editingState = null
            },
            onCreateNewSlot = { navController.navigate("settings/medication_slots") }
        )
    }

    doseDialogTarget?.let { target ->
        DoseAmountPromptDialog(
            medicationName = target.medication.name,
            onDismiss = { doseDialogTarget = null },
            onConfirm = { amount ->
                val slot = target.slot
                if (slot != null) {
                    viewModel.toggleDose(slot, target.medication, amount)
                } else {
                    viewModel.recordUnslottedDose(target.medication, amount)
                }
                doseDialogTarget = null
            }
        )
    }

    dosePickerTarget?.let { target ->
        com.averycorp.prismtask.ui.screens.medication.components.DosePickerSheet(
            medicationName = target.medication.name,
            doses = target.doses,
            onDismiss = { dosePickerTarget = null },
            onSelect = { dose ->
                doseTimeEditTarget = DoseTimeEditTarget(
                    medication = target.medication,
                    slot = null,
                    existingDose = dose
                )
                dosePickerTarget = null
            }
        )
    }

    doseTimeEditTarget?.let { target ->
        val logicalDay = remember(todayDateIso) {
            runCatching { java.time.LocalDate.parse(todayDateIso) }
                .getOrDefault(java.time.LocalDate.now())
        }
        com.averycorp.prismtask.ui.screens.medication.components.DoseTimeEditSheet(
            medicationName = target.medication.name,
            initialTakenAt = target.existingDose?.takenAt,
            logicalDay = logicalDay,
            onDismiss = { doseTimeEditTarget = null },
            onSave = { takenAt ->
                val existing = target.existingDose
                if (existing != null) {
                    viewModel.retimeDose(existing, takenAt)
                } else {
                    viewModel.logDoseAtTime(
                        medication = target.medication,
                        slot = target.slot,
                        takenAt = takenAt
                    )
                }
                doseTimeEditTarget = null
            },
            onRemove = target.existingDose?.let { dose ->
                {
                    viewModel.removeDose(dose)
                    doseTimeEditTarget = null
                }
            }
        )
    }

    timeEditingSlotState?.let { state ->
        // The sheet anchors the user-picked HH:mm to the slot card's logical
        // day (not wall-clock). When the SoD-boundary window is open
        // (wall-clock has crossed midnight but the logical day has not yet
        // rolled over), parsing todayDateIso gives the canonical anchor —
        // an unparseable value (only at first emission before LocalDateFlow
        // settles) falls back to the wall-clock day, matching legacy behavior.
        val logicalDay = remember(todayDateIso) {
            runCatching { java.time.LocalDate.parse(todayDateIso) }
                .getOrDefault(java.time.LocalDate.now())
        }
        MedicationTimeEditSheet(
            initialIntendedTime = state.intendedTime,
            slotName = state.slot.name,
            logicalDay = logicalDay,
            onDismiss = { timeEditingSlotState = null },
            onSave = { intendedTime ->
                viewModel.setIntendedTimeForSlot(state.slot, intendedTime)
                timeEditingSlotState = null
            }
        )
    }

    if (showBulkMarkDialog) {
        BulkMarkDialog(
            slotStates = slotStates,
            onDismiss = { showBulkMarkDialog = false },
            onConfirm = { scope, slotId, tier ->
                viewModel.bulkMark(scope, slotId, tier)
                showBulkMarkDialog = false
            }
        )
    }

    archivingMed?.let { med ->
        AlertDialog(
            onDismissRequest = { archivingMed = null },
            title = { Text("Archive Medication") },
            text = {
                Text(
                    "Archive \"${med.name}\"? Dose history is kept — you can unarchive " +
                        "from the medication log."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.archiveMedication(med)
                    archivingMed = null
                }) {
                    Text("Archive", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { archivingMed = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SlotTodayCard(
    state: MedicationSlotTodayState,
    editMode: Boolean,
    onToggleDose: (MedicationEntity) -> Unit,
    onSelectTier: (AchievedTier) -> Unit,
    onLongPressTier: () -> Unit,
    onLongPressMedication: (MedicationEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.slot.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${state.slot.idealTime} · ±${state.slot.driftMinutes}m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(9.dp))
        TierSegmentedRow(
            achievedTier = state.achievedTier,
            isUserSet = state.isUserSet,
            isBacklogged = state.isBacklogged,
            onSelectTier = onSelectTier,
            onLongPressActiveTier = onLongPressTier
        )
        if (state.medications.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "No medications linked to this slot yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            state.medications.sortedBy { it.sortOrder }.forEach { med ->
                MedicationDoseRow(
                    medication = med,
                    taken = med.id in state.takenMedicationIds,
                    takenAt = state.takenAtByMedicationId[med.id],
                    enabled = !editMode,
                    onToggle = { onToggleDose(med) },
                    onLongPress = { onLongPressMedication(med) }
                )
            }
        }
    }
}

/**
 * Four-button segmented selector — one button per [AchievedTier]. Tapping
 * an inactive tier records it as `USER_SET`; tapping the active tier when
 * it's already a user override clears the override (auto-compute resumes).
 *
 * Long-press on the active tier opens the intended-time edit sheet —
 * mirrors the legacy single-chip long-press affordance so the gesture
 * stays discoverable.
 */
@Composable
private fun TierSegmentedRow(
    achievedTier: AchievedTier,
    isUserSet: Boolean,
    isBacklogged: Boolean,
    onSelectTier: (AchievedTier) -> Unit,
    onLongPressActiveTier: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AchievedTier.entries.forEach { tier ->
            val active = tier == achievedTier
            TierSegmentButton(
                tier = tier,
                color = tierColorFor(tier),
                active = active,
                isUserSet = active && isUserSet,
                isBacklogged = active && isBacklogged,
                onClick = { onSelectTier(tier) },
                onLongClick = if (active) onLongPressActiveTier else null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TierSegmentButton(
    tier: AchievedTier,
    color: Color,
    active: Boolean,
    isUserSet: Boolean,
    isBacklogged: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val borderColor = if (active) color else MaterialTheme.colorScheme.outlineVariant
    val textColor = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant
    val background = if (active) color.copy(alpha = 0.15f) else Color.Transparent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Edit time" else null
            )
            .padding(horizontal = 4.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (isBacklogged) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "Logged at a different time than taken",
                    tint = color,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = tierShortLabelForButton(tier),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = textColor
            )
            if (isUserSet) {
                // Small dot indicates the active tier was set by the user
                // (vs. auto-computed). Lives inside the Row so it always
                // sits next to the label without being clipped.
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MedicationDoseRow(
    medication: MedicationEntity,
    taken: Boolean,
    takenAt: Long?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (taken) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                }
            ).border(
                width = 1.dp,
                color = if (taken) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(10.dp)
            )
            // Long-press is always available (even in edit mode) so users
            // can correct the logged time without first leaving edit
            // mode. Tap stays gated by `enabled` to preserve the existing
            // edit-mode no-toggle behavior. Editor access for med
            // metadata lives in edit mode → All Medications.
            .combinedClickable(
                enabled = true,
                onClick = { if (enabled) onToggle() },
                onLongClick = onLongPress,
                onLongClickLabel = "Edit logged time"
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (taken) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier.border(
                            1.5.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp)
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (taken) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = medication.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (taken) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = tierShortLabel(MedicationTier.fromStorage(medication.tier)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (taken && takenAt != null) {
            Text(
                text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(takenAt)),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MedicationEditRow(
    medication: MedicationEntity,
    onEdit: () -> Unit,
    onArchive: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onEdit)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = medication.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = tierShortLabel(MedicationTier.fromStorage(medication.tier)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit ${medication.name}",
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onArchive, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Archive,
                contentDescription = "Archive ${medication.name}",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Card row for a medication that isn't linked to any slot. Each tap on
 * "Record Taken" inserts a new dose row (no toggle); the label below the
 * name surfaces the most recent take time today as visual feedback.
 * Un-recording goes through the Medication Log screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnslottedMedicationCard(
    state: UnslottedMedicationState,
    onRecordTaken: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            // Long-press whole-row → time-edit / dose-picker (depending on
            // how many doses landed today). Tap stays a no-op so the
            // explicit Record Taken button remains the only logging path.
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
                onLongClickLabel = "Edit logged time"
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.medication.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            val takenAt = state.takenAt
            if (state.takenToday && takenAt != null) {
                Text(
                    text = "Last taken at ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(takenAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = tierShortLabel(MedicationTier.fromStorage(state.medication.tier)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Button(
            onClick = onRecordTaken,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Record Taken")
        }
    }
}

/**
 * Snapshot of the (medication, optional slot) pair that needs a dose
 * amount captured before the row is logged. `slot == null` routes to the
 * unscheduled record path; non-null routes to the per-slot toggle.
 */
private data class DoseDialogTarget(
    val medication: MedicationEntity,
    val slot: com.averycorp.prismtask.data.local.entity.MedicationSlotEntity?
)

/**
 * Long-press → time-edit target. [existingDose] is non-null when the
 * sheet should retime / remove an already-logged row; null means the
 * sheet logs a fresh dose at the chosen wall-clock (backdated). [slot]
 * is null for unscheduled-section meds; non-null for slot-card rows.
 */
private data class DoseTimeEditTarget(
    val medication: MedicationEntity,
    val slot: com.averycorp.prismtask.data.local.entity.MedicationSlotEntity?,
    val existingDose: com.averycorp.prismtask.data.local.entity.MedicationDoseEntity?
)

/**
 * Long-press picker target for unscheduled meds with >1 dose today.
 * Selecting a dose transitions to [DoseTimeEditTarget] with the chosen
 * row as [DoseTimeEditTarget.existingDose].
 */
private data class DosePickerTarget(
    val medication: MedicationEntity,
    val doses: List<com.averycorp.prismtask.data.local.entity.MedicationDoseEntity>
)

/**
 * Coupled (med, selections) state set atomically before the editor opens.
 * [MedicationEditorDialog]'s selections list lives in unkeyed `remember`,
 * so it captures `initialSelections` on first composition and won't
 * re-seed when an async load completes — rendering with stale data would
 * wipe slot links on Save and drop the med into Unscheduled.
 */
private data class EditingMedicationState(
    val medication: MedicationEntity,
    val selections: List<MedicationSlotSelection>
)

/**
 * Free-form dose-amount prompt fired before logging when the parent med
 * has `promptDoseAtLog = true`. The user types something like "500 mg"
 * or "1 tablet"; the string is passed through verbatim. An empty input
 * is allowed — the row is still logged, just with `doseAmount = null` —
 * so the prompt never blocks a user mid-flow.
 */
@Composable
private fun DoseAmountPromptDialog(
    medicationName: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record $medicationName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "How much did you take?",
                    style = MaterialTheme.typography.bodyMedium
                )
                androidx.compose.material3.OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    placeholder = { Text("e.g. 500 mg, 1 tablet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(amount.trim().takeIf { it.isNotEmpty() })
            }) { Text("Record") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun NoSlotsState(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "⏰", style = MaterialTheme.typography.displaySmall)
            Text(
                text = "No medication slots yet",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Create a slot first so doses have a home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenSettings) { Text("Open Medication Slots") }
        }
    }
}

@Composable
private fun NoMedicationsState(onAddClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "💊", style = MaterialTheme.typography.displaySmall)
            Text(
                text = "No medications added yet",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Tap the edit button, then + to add your first medication.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onAddClick) { Text("Add Medication") }
        }
    }
}

private fun tierShortLabel(tier: MedicationTier): String = when (tier) {
    MedicationTier.ESSENTIAL -> "Essential"
    MedicationTier.PRESCRIPTION -> "Prescription"
    MedicationTier.COMPLETE -> "Complete"
}

/** Short labels for the 4-button segmented selector — long names truncate at narrow widths. */
private fun tierShortLabelForButton(tier: AchievedTier): String = when (tier) {
    AchievedTier.SKIPPED -> "Skip"
    AchievedTier.ESSENTIAL -> "Ess"
    AchievedTier.PRESCRIPTION -> "Rx"
    AchievedTier.COMPLETE -> "Done"
}

/**
 * Per-theme tier colors. Maps the 4 [AchievedTier] values to the
 * `PrismThemeColors` semantic tokens so each theme expresses tier
 * meaning in its own palette (Cyberpunk neon vs. Void editorial vs.
 * Matrix phosphor vs. Synthwave retro), instead of locking everything
 * to Material defaults.
 */
@Composable
private fun tierColorFor(tier: AchievedTier): Color {
    val c = LocalPrismColors.current
    return when (tier) {
        AchievedTier.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        AchievedTier.ESSENTIAL -> c.destructiveColor
        AchievedTier.PRESCRIPTION -> c.infoColor
        AchievedTier.COMPLETE -> c.successColor
    }
}
