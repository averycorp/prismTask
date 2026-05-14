package com.averycorp.prismtask.ui.screens.addedittask.tabs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.ui.components.BreakdownResultCard
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.components.CoachingCard
import com.averycorp.prismtask.ui.components.CoachingErrorCard
import com.averycorp.prismtask.ui.components.TierBadge
import com.averycorp.prismtask.ui.components.UpgradePrompt
import com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskViewModel
import com.averycorp.prismtask.ui.screens.addedittask.AttachmentRow
import com.averycorp.prismtask.ui.screens.addedittask.FileImportSuggestionSheet
import com.averycorp.prismtask.ui.screens.addedittask.PendingSubtask
import com.averycorp.prismtask.ui.screens.addedittask.SectionLabel
import com.averycorp.prismtask.ui.screens.coaching.CoachingViewModel
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import kotlinx.coroutines.launch

@Composable
internal fun PriorityCircleRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val priorityColors = LocalPriorityColors.current
    val levels = listOf(
        0 to priorityColors.none,
        1 to priorityColors.low,
        2 to priorityColors.medium,
        3 to priorityColors.high,
        4 to priorityColors.urgent
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        levels.forEach { (level, color) ->
            PriorityCircle(
                color = color,
                selected = selected == level,
                onClick = { onSelect(level) }
            )
        }
    }
}

@Composable
internal fun PriorityCircle(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tab content composables
// ---------------------------------------------------------------------------

@Composable
internal fun DetailsTabContent(
    viewModel: AddEditTaskViewModel,
    coachingViewModel: CoachingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()

    var showAddLinkDialog by remember { mutableStateOf(false) }
    var attachmentsRevealed by remember { mutableStateOf(false) }

    // AI Coaching state
    val coachingUserTier by coachingViewModel.userTier.collectAsStateWithLifecycle()
    val stuckMessage by coachingViewModel.stuckMessage.collectAsStateWithLifecycle()
    val stuckLoading by coachingViewModel.stuckLoading.collectAsStateWithLifecycle()
    val breakdownSubtasks by coachingViewModel.breakdownSubtasks.collectAsStateWithLifecycle()
    val breakdownLoading by coachingViewModel.breakdownLoading.collectAsStateWithLifecycle()
    val remainingBreakdowns by coachingViewModel.remainingBreakdowns.collectAsStateWithLifecycle()
    val perfectionismMessage by coachingViewModel.perfectionismMessage.collectAsStateWithLifecycle()
    val perfectionismLoading by coachingViewModel.perfectionismLoading.collectAsStateWithLifecycle()
    val coachingUpgradePrompt by coachingViewModel.showUpgradePrompt.collectAsStateWithLifecycle()
    val coachingErrorMessage by coachingViewModel.errorMessage.collectAsStateWithLifecycle()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onAddImageAttachment(context, it) }
    }

    // Any-MIME picker for the "Extract from File" affordance — accepts
    // images, source code, PDFs, DOCX, XLSX, etc. The result feeds the
    // backend extraction round-trip rather than the attachment table.
    val fileExtractLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.extractFromFile(context, it) }
    }
    val fileExtractionState by viewModel.fileExtractionState.collectAsStateWithLifecycle()

    // --- AI Coaching: "Help Me Start" button + stuck coaching card (edit mode) ---
    if (viewModel.isEditMode) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    viewModel.currentEditingTaskId?.let { coachingViewModel.getStuckHelp(it) }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Help Me Start")
                if (coachingUserTier == UserTier.FREE) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TierBadge(requiredTier = UserTier.PRO)
                }
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    viewModel.currentEditingTaskId?.let { coachingViewModel.getTaskBreakdown(it) }
                }
            ) {
                Text("Break It Down")
            }
        }

        // Stuck coaching response card
        CoachingCard(
            message = stuckMessage,
            isLoading = stuckLoading,
            onDismiss = { coachingViewModel.dismissStuckMessage() },
            title = "AI Executive Assistant"
        )

        // Perfectionism detection card
        CoachingCard(
            message = perfectionismMessage,
            isLoading = perfectionismLoading,
            onDismiss = { coachingViewModel.dismissPerfectionism() },
            title = "Pattern Detected"
        )
    }

    // --- AI Task Breakdown results ---
    if (breakdownSubtasks.isNotEmpty()) {
        BreakdownResultCard(
            subtasks = breakdownSubtasks,
            remainingUses = remainingBreakdowns,
            onApply = {
                breakdownSubtasks.forEach { title ->
                    viewModel.addPendingSubtask(title)
                }
                coachingViewModel.dismissBreakdown()
            },
            onDismiss = { coachingViewModel.dismissBreakdown() }
        )
    }

    // Coaching AI failure (inline). Pre-fix the VM set _errorMessage on
    // CoachingResult.Error but no UI consumed it, so AI/backend failures
    // looked like silent no-ops on Help Me Start / Break It Down. See
    // docs/audits/AUTO_BUTTON_AI_FAILURE_AND_UPGRADE_MESSAGES_AUDIT.md
    // (items #4, #5).
    CoachingErrorCard(
        message = coachingErrorMessage,
        onDismiss = { coachingViewModel.dismissError() }
    )

    // Coaching upgrade prompt (inline)
    if (coachingUpgradePrompt) {
        UpgradePrompt(
            currentTier = coachingUserTier,
            feature = "AI Executive Assistant",
            description = "Unlimited AI task assistance, task breakdown, and energy-adaptive daily planning",
            onUpgrade = { _ -> coachingViewModel.dismissUpgradePrompt() },
            onDismiss = { coachingViewModel.dismissUpgradePrompt() }
        )
    }

    // --- Extract From File ---
    // Drops the user into the system file picker; on selection, the VM
    // posts the bytes to the backend and the suggestion sheet renders.
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { fileExtractLauncher.launch("*/*") },
            enabled = !fileExtractionState.isInFlight
        ) {
            if (fileExtractionState.isInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (fileExtractionState.isInFlight) "Reading File\u2026" else "Extract Details from File"
            )
        }
    }

    // --- Description ---
    Column(modifier = Modifier.animateContentSize()) {
        Text(
            text = "Description",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        OutlinedTextField(
            value = viewModel.description,
            onValueChange = viewModel::onDescriptionChange,
            placeholder = { Text("Add Description\u2026") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // --- Notes (tinted background, 🔒 private marker) ---
    Column(modifier = Modifier.animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        ) {
            Text(
                text = "Notes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "\uD83D\uDD12",
                style = MaterialTheme.typography.labelSmall
            )
        }
        OutlinedTextField(
            value = viewModel.notes,
            onValueChange = viewModel::onNotesChange,
            placeholder = { Text("Private Notes\u2026") },
            minLines = 2,
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    // --- Subtasks ---
    // Backed by `viewModel.pendingSubtasks` so template-applied subtasks
    // and user-typed subtasks survive into the save path (the VM flushes
    // the list as real TaskEntity rows once saveTask() succeeds).
    SubtasksInlineSection(viewModel)

    // Keep the attachments section visible for the rest of the session once
    // it first becomes non-empty, so deleting every attachment mid-edit
    // doesn't surprise the user by collapsing it back to a button.
    LaunchedEffect(attachments.isNotEmpty()) {
        if (attachments.isNotEmpty()) attachmentsRevealed = true
    }

    // --- Attachments (edit mode only; hidden when empty until revealed) ---
    if (viewModel.isEditMode) {
        val hasAttachments = attachments.isNotEmpty()
        val showSection = hasAttachments || attachmentsRevealed
        if (showSection) {
            Column(modifier = Modifier.animateContentSize()) {
                SectionLabel(
                    if (hasAttachments) "Attachments (${attachments.size})" else "Attachments"
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (hasAttachments) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        attachments.forEach { attachment ->
                            AttachmentRow(
                                attachment = attachment,
                                onDelete = { viewModel.onDeleteAttachment(context, attachment) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Image")
                    }
                    OutlinedButton(onClick = { showAddLinkDialog = true }) {
                        Icon(
                            Icons.Default.AddLink,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Link")
                    }
                }
            }
        } else {
            TextButton(onClick = { attachmentsRevealed = true }) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Attachment")
            }
        }
    }

    // File-extraction suggestion sheet — renders once the backend returns
    // a non-empty suggestion; the user picks which fields to apply.
    fileExtractionState.suggestion?.let { suggestion ->
        FileImportSuggestionSheet(
            suggestion = suggestion,
            onDismiss = { viewModel.dismissFileExtractionSheet() },
            onApply = { selections ->
                viewModel.applyFileExtractionSuggestion(
                    suggestion = suggestion,
                    applyTitle = selections.applyTitle,
                    applyDescription = selections.applyDescription,
                    applyDueDate = selections.applyDueDate,
                    applyPriority = selections.applyPriority,
                    applyProject = selections.applyProject,
                    applyTags = selections.applyTags,
                    applySubtasks = selections.applySubtasks,
                    applyLifeCategory = selections.applyLifeCategory,
                    applyEstimatedDuration = selections.applyEstimatedDuration,
                    applyLocation = selections.applyLocation,
                    applyRecurrenceHint = selections.applyRecurrenceHint,
                    applyReminderOffset = selections.applyReminderOffset
                )
            }
        )
    }

    // Add link dialog
    if (showAddLinkDialog) {
        var linkUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddLinkDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (linkUrl.isNotBlank()) {
                            viewModel.onAddLinkAttachment(linkUrl.trim())
                            showAddLinkDialog = false
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddLinkDialog = false }) { Text("Cancel") }
            },
            title = { Text("Add Link") },
            text = {
                OutlinedTextField(
                    value = linkUrl,
                    onValueChange = { linkUrl = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

/**
 * Inline subtasks section for the Details tab.
 *
 * Backed by [AddEditTaskViewModel.pendingSubtasks] so the list survives into
 * the save path: once the user hits Save, the VM flushes each pending row
 * into a real [com.averycorp.prismtask.data.local.entity.TaskEntity] tied
 * to the newly-created parent. Templates populate the same list when the
 * user picks a template from the header button.
 */
@Composable
internal fun SubtasksInlineSection(viewModel: AddEditTaskViewModel) {
    val subtasks = viewModel.pendingSubtasks
    var newText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val sorted = subtasks.sortedBy { it.isCompleted }
    val completed = subtasks.count { it.isCompleted }
    val total = subtasks.size

    val submit = {
        val id = viewModel.addPendingSubtask(newText)
        if (id != -1L) {
            newText = ""
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        SectionLabel(
            if (total > 0) "Subtasks ($completed/$total)" else "Subtasks"
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (total == 0) {
            Text(
                text = "Add Subtasks To Break This Task Down",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sorted.forEach { subtask ->
                    LocalSubtaskRow(
                        subtask = subtask,
                        onToggle = { viewModel.togglePendingSubtask(subtask.id) },
                        onDelete = { viewModel.removePendingSubtask(subtask.id) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        OutlinedTextField(
            value = newText,
            onValueChange = { newText = it },
            placeholder = { Text("Add Subtask\u2026") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Subtask",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
    }
}

@Composable
internal fun LocalSubtaskRow(
    subtask: PendingSubtask,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = "Reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        CircularCheckbox(
            checked = subtask.isCompleted,
            onCheckedChange = { onToggle() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = subtask.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (subtask.isCompleted) TextDecoration.LineThrough else null,
            color = if (subtask.isCompleted) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove Subtask",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
