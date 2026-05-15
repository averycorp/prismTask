package com.averycorp.prismtask.ui.screens.addedittask.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.RecurrenceType
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.RecurrenceDialog
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskViewModel
import com.averycorp.prismtask.ui.screens.addedittask.ReminderPickerDialog
import com.averycorp.prismtask.ui.screens.addedittask.SectionLabel
import com.averycorp.prismtask.ui.screens.addedittask.TimePickerDialog
import com.averycorp.prismtask.ui.screens.addedittask.formatTime
import com.averycorp.prismtask.ui.screens.addedittask.todayMillis
import com.averycorp.prismtask.ui.screens.addedittask.tomorrowMillis
import com.averycorp.prismtask.ui.screens.addedittask.weekFromNowMillis
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleTabContent(viewModel: AddEditTaskViewModel) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    var showRecurrenceDialog by remember { mutableStateOf(false) }
    var showCustomDurationDialog by remember { mutableStateOf(false) }
    var showCustomLogTimeDialog by remember { mutableStateOf(false) }

    val dueDate = viewModel.dueDate
    val hasDate = dueDate != null
    val loggedMinutes by viewModel.loggedMinutes.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // ---- Due Date section ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Due Date")

            val today = todayMillis()
            val tomorrow = tomorrowMillis()
            val nextWeek = weekFromNowMillis()
            val matchesShortcut = dueDate == today || dueDate == tomorrow || dueDate == nextWeek

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleChip(
                    label = "Today",
                    selected = dueDate == today,
                    onClick = { viewModel.onDueDateChange(today) }
                )
                ScheduleChip(
                    label = "Tomorrow",
                    selected = dueDate == tomorrow,
                    onClick = { viewModel.onDueDateChange(tomorrow) }
                )
                ScheduleChip(
                    label = "Next Week",
                    selected = dueDate == nextWeek,
                    onClick = { viewModel.onDueDateChange(nextWeek) }
                )
                ScheduleChip(
                    label = "None",
                    selected = dueDate == null,
                    onClick = { viewModel.onDueDateChange(null) }
                )
                if (dueDate != null && !matchesShortcut) {
                    FilterChip(
                        selected = true,
                        onClick = { showDatePicker = true },
                        label = { Text(formatShortDate(dueDate)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear date",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { viewModel.onDueDateChange(null) }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            TextButton(
                onClick = { showDatePicker = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pick Date\u2026")
            }

            if (dueDate != null) {
                Text(
                    text = "\uD83D\uDCC5 ${formatFullDate(dueDate)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ---- Time section (visible when date set) ----
        AnimatedVisibility(
            visible = hasDate,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Time")
                if (viewModel.dueTime == null) {
                    TextButton(
                        onClick = { showTimePicker = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("\uD83D\uDD50 Add Time")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { showTimePicker = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "\uD83D\uDD50 ${viewModel.dueTime?.let { formatTime(it) } ?: ""}",
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(
                            onClick = { viewModel.onDueTimeChange(null) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear time",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---- Duration section ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Estimated Duration")
            val duration = viewModel.estimatedDuration
            val presets = listOf(
                "15m" to 15,
                "30m" to 30,
                "1h" to 60,
                "1.5h" to 90,
                "2h" to 120,
                "3h" to 180
            )
            val matchesPreset = duration != null && presets.any { it.second == duration }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (label, minutes) ->
                    ScheduleChip(
                        label = label,
                        selected = duration == minutes,
                        onClick = { viewModel.onEstimatedDurationChange(minutes) }
                    )
                }
                ScheduleChip(
                    label = "Custom",
                    selected = duration != null && !matchesPreset,
                    onClick = { showCustomDurationDialog = true }
                )
            }

            if (duration != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⏱ ${formatDurationMinutes(duration)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = { viewModel.onEstimatedDurationChange(null) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear duration",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- Logged Time section (edit mode only — needs a persisted task to FK against) ----
        if (viewModel.isEditMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Logged Time")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleChip(
                        label = "+15m",
                        selected = false,
                        onClick = { viewModel.logTime(15) }
                    )
                    ScheduleChip(
                        label = "+30m",
                        selected = false,
                        onClick = { viewModel.logTime(30) }
                    )
                    ScheduleChip(
                        label = "+1h",
                        selected = false,
                        onClick = { viewModel.logTime(60) }
                    )
                    ScheduleChip(
                        label = "+Custom",
                        selected = false,
                        onClick = { showCustomLogTimeDialog = true }
                    )
                }
                Text(
                    text = if (loggedMinutes > 0) {
                        "Total: ${formatDurationMinutes(loggedMinutes)}"
                    } else {
                        "No time logged yet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (loggedMinutes > 0) FontWeight.Medium else FontWeight.Normal,
                    color = if (loggedMinutes > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // ---- Recurrence section ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Repeat")
            val rule = viewModel.recurrenceRule
            if (rule == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Does Not Repeat",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { showRecurrenceDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Set Recurrence\u2026")
                    }
                }
            } else {
                Text(
                    text = formatRecurrenceSummary(rule),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showRecurrenceDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Edit")
                    }
                    TextButton(
                        onClick = { viewModel.onRecurrenceRuleChange(null) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---- Reminder section (visible when date set) ----
        AnimatedVisibility(
            visible = hasDate,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Reminder")
                if (viewModel.reminderOffset == null) {
                    TextButton(
                        onClick = { showReminderDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("\uD83D\uDD14 Add Reminder")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { showReminderDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "\uD83D\uDD14 ${reminderOffsetTitleCase(viewModel.reminderOffset)}",
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(
                            onClick = { viewModel.onReminderOffsetChange(null) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear reminder",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
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
        val reminderPresets by viewModel.reminderPresets.collectAsState()
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

    // Recurrence dialog (wraps the existing RecurrenceSelector dialog internals)
    if (showRecurrenceDialog) {
        RecurrenceDialog(
            initialRule = viewModel.recurrenceRule,
            onDismiss = { showRecurrenceDialog = false },
            onConfirm = { rule ->
                viewModel.onRecurrenceRuleChange(rule)
                showRecurrenceDialog = false
            }
        )
    }

    // Custom duration dialog
    if (showCustomDurationDialog) {
        CustomDurationDialog(
            initialMinutes = viewModel.estimatedDuration,
            onConfirm = { minutes ->
                viewModel.onEstimatedDurationChange(minutes)
                showCustomDurationDialog = false
            },
            onDismiss = { showCustomDurationDialog = false }
        )
    }

    // Custom log-time dialog — appends a manual entry instead of replacing.
    if (showCustomLogTimeDialog) {
        CustomDurationDialog(
            initialMinutes = null,
            onConfirm = { minutes ->
                if (minutes != null) viewModel.logTime(minutes)
                showCustomLogTimeDialog = false
            },
            onDismiss = { showCustomLogTimeDialog = false }
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

/**
 * Quick-select chip used across the Schedule tab. Renders as a filled accent
 * chip when selected, outlined otherwise (FilterChip's default unselected
 * state).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
internal fun CustomDurationDialog(
    initialMinutes: Int?,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialMinutes?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Duration") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter { it.isDigit() }.take(4) },
                label = { Text("Minutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = text.toIntOrNull()?.takeIf { it > 0 }
                onConfirm(minutes)
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

internal fun formatShortDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))

internal fun formatFullDate(epochMillis: Long): String =
    SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))

internal fun formatDurationMinutes(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "$minutes ${if (minutes == 1) "Minute" else "Minutes"}"
        minutes == 0 -> "$hours ${if (hours == 1) "Hour" else "Hours"}"
        else -> {
            val hourLabel = if (hours == 1) "Hour" else "Hours"
            val minuteLabel = if (minutes == 1) "Minute" else "Minutes"
            "$hours $hourLabel $minutes $minuteLabel"
        }
    }
}

internal fun reminderOffsetTitleCase(offset: Long?): String = when (offset) {
    null -> "No Reminder"
    0L -> "At Due Time"
    900_000L -> "15 Minutes Before Due"
    1_800_000L -> "30 Minutes Before Due"
    3_600_000L -> "1 Hour Before Due"
    86_400_000L -> "1 Day Before Due"
    else -> "${offset / 60_000} Minutes Before Due"
}

internal fun formatRecurrenceSummary(rule: RecurrenceRule): String {
    val interval = rule.interval.coerceAtLeast(1)
    val base = when (rule.type) {
        RecurrenceType.DAILY -> if (interval == 1) "Every Day" else "Every $interval Days"
        RecurrenceType.WEEKLY -> {
            val prefix = if (interval == 1) "Every Week" else "Every $interval Weeks"
            val days = rule.daysOfWeek?.takeIf { it.isNotEmpty() }?.let { list ->
                val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                list.sorted().joinToString(", ") { names.getOrElse(it - 1) { "" } }
            }
            if (days != null) "$prefix on $days" else prefix
        }
        RecurrenceType.MONTHLY -> {
            val prefix = if (interval == 1) "Every Month" else "Every $interval Months"
            rule.dayOfMonth?.let { "$prefix on Day $it" } ?: prefix
        }
        RecurrenceType.YEARLY -> if (interval == 1) "Every Year" else "Every $interval Years"
        RecurrenceType.CUSTOM -> "Custom"
        RecurrenceType.WEEKDAY -> "Every Weekday"
        RecurrenceType.BIWEEKLY -> "Every Other Week"
        RecurrenceType.CUSTOM_DAYS -> {
            val days = rule.monthDays
                ?.takeIf { it.isNotEmpty() }
                ?.sorted()
                ?.joinToString(", ")
            if (days != null) "Monthly on Days $days" else "Custom Days"
        }
        RecurrenceType.AFTER_COMPLETION -> {
            val n = rule.afterCompletionInterval ?: 1
            val unit = rule.afterCompletionUnit ?: "days"
            "$n $unit After Completion"
        }
    }
    return base
}
