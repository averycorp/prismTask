package com.averycorp.prismtask.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.model.CognitiveLoad
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.model.TaskMode
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The pending-confirm payload assembled from a NLP parse result. The sheet
 * lets the user edit every field before the task is actually written.
 *
 * Carries [plannedDateOverride] verbatim from the host (e.g. the Plan-for-
 * Today sheet) so the eventual insert lands on the same plan slot it would
 * have without the confirm step.
 */
data class PendingConfirmTask(
    val title: String,
    val dueDate: Long?,
    val dueTime: Long?,
    val priority: Int,
    val projectName: String?,
    val tags: List<String>,
    val recurrenceHint: String?,
    val lifeCategory: String?,
    val taskMode: String?,
    val cognitiveLoad: String?,
    val plannedDateOverride: Long?
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskConfirmSheet(
    pending: PendingConfirmTask,
    onSave: (PendingConfirmTask) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var title by remember(pending) { mutableStateOf(pending.title) }
    var dueDate by remember(pending) { mutableStateOf(pending.dueDate) }
    var dueTime by remember(pending) { mutableStateOf(pending.dueTime) }
    var priority by remember(pending) { mutableStateOf(pending.priority) }
    var projectName by remember(pending) { mutableStateOf(pending.projectName.orEmpty()) }
    var tagsText by remember(pending) { mutableStateOf(pending.tags.joinToString(", ")) }
    var lifeCategory by remember(pending) { mutableStateOf(pending.lifeCategory) }
    var taskMode by remember(pending) { mutableStateOf(pending.taskMode) }
    var cognitiveLoad by remember(pending) { mutableStateOf(pending.cognitiveLoad) }
    val recurrenceHint = pending.recurrenceHint

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = {
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Confirm Task",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Tap any field to edit. Save when ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = {
                        Text(
                            dueDate?.let { dateFormat.format(Date(it)) } ?: "Pick Date"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null
                        )
                    },
                    colors = if (dueDate != null) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    }
                )
                AssistChip(
                    onClick = { showTimePicker = true },
                    label = {
                        Text(
                            dueTime?.let { timeFormat.format(Date(it)) } ?: "Pick Time"
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                    },
                    colors = if (dueTime != null) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    }
                )
                if (dueDate != null) {
                    TextButton(
                        onClick = {
                            dueDate = null
                            dueTime = null
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Priority",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            PriorityChips(priority = priority, onPick = { priority = it })

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("Project (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (recurrenceHint != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Repeats: ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = recurrenceHint.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Life Category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            EnumPickerRow(
                values = LifeCategory.TRACKED.map { it.name },
                labels = LifeCategory.TRACKED.map { LifeCategory.label(it) },
                selected = lifeCategory,
                onPick = { lifeCategory = if (lifeCategory == it) null else it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Task Mode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            EnumPickerRow(
                values = TaskMode.TRACKED.map { it.name },
                labels = TaskMode.TRACKED.map { TaskMode.label(it) },
                selected = taskMode,
                onPick = { taskMode = if (taskMode == it) null else it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Cognitive Load",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            EnumPickerRow(
                values = CognitiveLoad.TRACKED.map { it.name },
                labels = CognitiveLoad.TRACKED.map { CognitiveLoad.label(it) },
                selected = cognitiveLoad,
                onPick = { cognitiveLoad = if (cognitiveLoad == it) null else it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val cleanedTags = tagsText
                            .split(',')
                            .map { it.trim().removePrefix("#") }
                            .filter { it.isNotBlank() }
                        val edited = pending.copy(
                            title = title.trim().ifEmpty { pending.title },
                            dueDate = dueDate,
                            dueTime = dueTime,
                            priority = priority,
                            projectName = projectName.trim().ifEmpty { null },
                            tags = cleanedTags,
                            lifeCategory = lifeCategory,
                            taskMode = taskMode,
                            cognitiveLoad = cognitiveLoad
                        )
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onSave(edited)
                        }
                    },
                    enabled = title.isNotBlank()
                ) {
                    Text("Save Task")
                }
            }
        }

        if (showDatePicker) {
            DueDateDialog(
                initialMillis = dueDate,
                onConfirm = {
                    dueDate = it
                    showDatePicker = false
                },
                onDismiss = { showDatePicker = false }
            )
        }
        if (showTimePicker) {
            DueTimeDialog(
                initialMillis = dueTime,
                onConfirm = {
                    dueTime = it
                    // If user picks a time without a date, anchor to today.
                    if (dueDate == null) dueDate = it
                    showTimePicker = false
                },
                onDismiss = { showTimePicker = false }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityChips(priority: Int, onPick: (Int) -> Unit) {
    val labels = listOf("None", "Low", "Medium", "High", "Urgent")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { level, label ->
            val color = if (level == 0) {
                MaterialTheme.colorScheme.outline
            } else {
                LocalPriorityColors.current.forLevel(level)
            }
            FilterChip(
                selected = priority == level,
                onClick = { onPick(level) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.18f),
                    selectedLabelColor = color
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnumPickerRow(
    values: List<String>,
    labels: List<String>,
    selected: String?,
    onPick: (String) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { index, value ->
            FilterChip(
                selected = selected == value,
                onClick = { onPick(value) },
                label = { Text(labels[index]) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateDialog(
    initialMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialMillis ?: System.currentTimeMillis()
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis ?: return@TextButton onDismiss()
                onConfirm(picked)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

@Composable
private fun DueTimeDialog(
    initialMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val cal = remember(initialMillis) {
        Calendar.getInstance().apply {
            timeInMillis = initialMillis ?: System.currentTimeMillis()
        }
    }
    val state = rememberAnalogClockState(
        initialHour = cal.get(Calendar.HOUR_OF_DAY),
        initialMinute = cal.get(Calendar.MINUTE),
        initialSecond = 0,
        is24Hour = false
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = Calendar.getInstance().apply {
                    timeInMillis = initialMillis ?: System.currentTimeMillis()
                    set(Calendar.HOUR_OF_DAY, state.hour)
                    set(Calendar.MINUTE, state.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                onConfirm(picked)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnalogClockPicker(state = state)
            }
        }
    )
}
