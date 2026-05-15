package com.averycorp.prismtask.ui.screens.addedittask

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.AttachmentEntity
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.RecurrenceSelector
import com.averycorp.prismtask.ui.components.TagSelector
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditTaskScreen(
    navController: NavController,
    viewModel: AddEditTaskViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ThemedSubScreenTitle(if (viewModel.isEditMode) "Edit Task" else "New Task")
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (viewModel.isEditMode) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        AddEditTaskFormFields(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            trailingContent = {
                Spacer(modifier = Modifier.height(8.dp))

                // Save
                Button(
                    onClick = {
                        scope.launch {
                            if (viewModel.saveTask()) navController.popBackStack()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (viewModel.isEditMode) "Update Task" else "Save Task",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Task?") },
            text = {
                Text(
                    "This will remove the task and its history. You can " +
                        "still undo from the snackbar after it's deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        viewModel.deleteTask()
                        navController.popBackStack()
                    }
                }) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddEditTaskFormFields(
    viewModel: AddEditTaskViewModel,
    modifier: Modifier = Modifier,
    trailingContent: @Composable ColumnScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val editorRows by viewModel.editorFieldRows.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    var notesExpanded by remember { mutableStateOf(viewModel.notes.isNotBlank()) }
    var showAddLinkDialog by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onAddImageAttachment(context, it) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Title
        OutlinedTextField(
            value = viewModel.title,
            onValueChange = viewModel::onTitleChange,
            label = { Text("Title") },
            isError = viewModel.titleError,
            supportingText = if (viewModel.titleError) {
                { Text("Title is required") }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Description
        OutlinedTextField(
            value = viewModel.description,
            onValueChange = viewModel::onDescriptionChange,
            label = { Text("Description") },
            minLines = minOf(3, editorRows.descriptionRows),
            maxLines = editorRows.descriptionRows,
            modifier = Modifier.fillMaxWidth()
        )

        // Due date
        SectionLabel("Due Date")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateQuickChip("Today", viewModel.dueDate, todayMillis()) {
                viewModel.onDueDateChange(todayMillis())
            }
            DateQuickChip("Tomorrow", viewModel.dueDate, tomorrowMillis()) {
                viewModel.onDueDateChange(tomorrowMillis())
            }
            DateQuickChip("+1 Week", viewModel.dueDate, weekFromNowMillis()) {
                viewModel.onDueDateChange(weekFromNowMillis())
            }
            FilterChip(
                selected = false,
                onClick = { showDatePicker = true },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick Date")
                    }
                }
            )
            if (viewModel.dueDate != null) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.onDueDateChange(null) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                )
            }
        }
        if (viewModel.dueDate != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Due: ${viewModel.dueDate?.let { formatDateSmart(it) } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { viewModel.onDueDateChange(null) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear date",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Due time — STANDARD+
        SectionLabel("Due Time")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showTimePicker = true }) {
                Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = viewModel.dueTime?.let { formatTime(it) } ?: "No Time"
                )
            }
            if (viewModel.dueTime != null) {
                IconButton(onClick = { viewModel.onDueTimeChange(null) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear time", modifier = Modifier.size(18.dp))
                }
            }
        }

        // Reminder — STANDARD+
        SectionLabel("Reminder")
        val hasDate = viewModel.dueDate != null
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDate) { showReminderDialog = true }
                .padding(vertical = 8.dp)
        ) {
            Icon(
                imageVector = if (viewModel.reminderOffset != null) {
                    Icons.Default.Notifications
                } else {
                    Icons.Default.NotificationsNone
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (hasDate) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (!hasDate) {
                    "Set a due date first"
                } else {
                    reminderOffsetLabel(viewModel.reminderOffset)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasDate) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }

        // Recurrence — STANDARD+
        SectionLabel("Recurrence")
        RecurrenceSelector(
            currentRule = viewModel.recurrenceRule,
            onRuleChanged = viewModel::onRecurrenceRuleChange
        )

        // Priority
        SectionLabel("Priority")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val priorityColors = LocalPriorityColors.current
            PriorityOption.entries.forEach { option ->
                PriorityChip(
                    label = option.label,
                    color = priorityColors.forLevel(option.value),
                    selected = viewModel.priority == option.value,
                    onClick = { viewModel.onPriorityChange(option.value) }
                )
            }
        }

        // Project — STANDARD+
        SectionLabel("Project")
        ProjectDropdown(
            selectedProjectId = viewModel.projectId,
            projects = projects,
            onSelect = viewModel::onProjectIdChange
        )

        // Tags — STANDARD+
        SectionLabel("Tags")
        TagSelector(
            availableTags = allTags,
            selectedTagIds = viewModel.selectedTagIds,
            onSelectionChanged = viewModel::onSelectedTagIdsChange
        )

        // Notes
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    notesExpanded = !notesExpanded
                }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Notes")
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (notesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (notesExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = notesExpanded) {
            OutlinedTextField(
                value = viewModel.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes") },
                minLines = minOf(4, editorRows.notesRows),
                maxLines = editorRows.notesRows,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (viewModel.isEditMode) {
            SectionLabel("Attachments")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Image")
                }
                OutlinedButton(onClick = { showAddLinkDialog = true }) {
                    Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Link")
                }
            }
            if (attachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    attachments.forEach { attachment ->
                        AttachmentRow(
                            attachment = attachment,
                            onDelete = { viewModel.onDeleteAttachment(context, attachment) }
                        )
                    }
                }
            }
        }

        trailingContent()
    }

    // Date picker dialog
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = viewModel.dueDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDueDateChange(
                        com.averycorp.prismtask.ui.components.datePickerToLocalMillis(
                            state.selectedDateMillis
                        )
                    )
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }

    // Reminder picker dialog
    if (showReminderDialog) {
        val reminderPresets by viewModel.reminderPresets.collectAsStateWithLifecycle()
        ReminderPickerDialog(
            currentOffset = viewModel.reminderOffset,
            presets = reminderPresets,
            onSelect = { offset ->
                viewModel.onReminderOffsetChange(offset)
                showReminderDialog = false
            },
            onDismiss = { showReminderDialog = false }
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

    // Time picker dialog
    if (showTimePicker) {
        val cal = Calendar.getInstance().apply {
            viewModel.dueTime?.let { timeInMillis = it }
        }
        val clockState = rememberAnalogClockState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = false
        )
        TimePickerDialog(
            onDismiss = { showTimePicker = false },
            onConfirm = {
                val picked = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, clockState.hour)
                    set(Calendar.MINUTE, clockState.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                viewModel.onDueTimeChange(picked.timeInMillis)
                showTimePicker = false
            }
        ) {
            AnalogClockPicker(state = clockState)
        }
    }
}

@Composable
internal fun AttachmentRow(
    attachment: AttachmentEntity,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable {
                if (attachment.type == "link") {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(attachment.uri))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
            }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (attachment.type == "image") Icons.Default.Image else Icons.Default.Link,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = attachment.fileName ?: attachment.uri,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

private enum class PriorityOption(
    val value: Int,
    val label: String
) {
    NONE(0, "None"),
    LOW(1, "Low"),
    MEDIUM(2, "Med"),
    HIGH(3, "High"),
    URGENT(4, "Urgent")
}

@Composable
private fun PriorityChip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .then(
                if (selected) {
                    Modifier.background(color.copy(alpha = 0.2f))
                } else {
                    Modifier
                }
            ).border(
                width = 1.5.dp,
                color = if (selected) color else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            ).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectDropdown(
    selectedProjectId: Long?,
    projects: List<com.averycorp.prismtask.data.local.entity.ProjectEntity>,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProject = projects.find { it.id == selectedProjectId }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedProject?.name ?: "No project",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("No Project") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text("${project.icon} ${project.name}") },
                    onClick = {
                        onSelect(project.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { content() }
    )
}

internal fun todayMillis(): Long = Calendar
    .getInstance()
    .apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

internal fun tomorrowMillis(): Long = Calendar
    .getInstance()
    .apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

internal fun weekFromNowMillis(): Long = Calendar
    .getInstance()
    .apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 7)
    }.timeInMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateQuickChip(label: String, currentDate: Long?, targetDate: Long, onClick: () -> Unit) {
    FilterChip(
        selected = currentDate == targetDate,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

internal fun formatDateSmart(epochMillis: Long): String {
    val today = todayMillis()
    val tomorrow = tomorrowMillis()
    val dayAfter = Calendar
        .getInstance()
        .apply {
            timeInMillis = tomorrow
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    val fullFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    return when {
        epochMillis in today until tomorrow -> "Today, ${dateFmt.format(Date(epochMillis))}"
        epochMillis in tomorrow until dayAfter -> "Tomorrow, ${dateFmt.format(Date(epochMillis))}"
        else -> fullFmt.format(Date(epochMillis))
    }
}

internal fun formatTime(epochMillis: Long): String {
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    return fmt.format(Date(epochMillis))
}

internal fun reminderOffsetLabel(offset: Long?): String = when (offset) {
    null -> "No reminder"
    0L -> "At due time"
    900_000L -> "15 minutes before"
    1_800_000L -> "30 minutes before"
    3_600_000L -> "1 hour before"
    86_400_000L -> "1 day before"
    else -> {
        val minutes = offset / 60_000
        when {
            minutes % (24 * 60) == 0L -> "${minutes / (24 * 60)} day${if (minutes / (24 * 60) == 1L) "" else "s"} before"
            minutes % 60 == 0L -> "${minutes / 60} hour${if (minutes / 60 == 1L) "" else "s"} before"
            else -> "$minutes min before"
        }
    }
}

private data class ReminderOption(
    val label: String,
    val offset: Long?
)

private val DEFAULT_REMINDER_OFFSETS = listOf(0L, 900_000L, 1_800_000L, 3_600_000L, 86_400_000L)

private fun reminderOptionsFor(presets: List<Long>): List<ReminderOption> {
    val source = if (presets.isEmpty()) DEFAULT_REMINDER_OFFSETS else presets
    return buildList {
        add(ReminderOption("None", null))
        source.distinct().forEach { offset ->
            add(ReminderOption(reminderOffsetLabel(offset), offset))
        }
    }
}

@Composable
internal fun ReminderPickerDialog(
    currentOffset: Long?,
    presets: List<Long> = DEFAULT_REMINDER_OFFSETS,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val options = remember(presets) { reminderOptionsFor(presets) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Set Reminder") },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.offset) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = currentOffset == option.offset,
                            onClick = { onSelect(option.offset) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    )
}
