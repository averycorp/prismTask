package com.averycorp.prismtask.ui.screens.habits.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Card variant used for "bookable" habits — habits that track whether
 * the user has reserved a slot (appointment / call / etc.) as well as
 * completion. Replaces the toggle button with dedicated Book and Log
 * actions, and surfaces the current booking status and last-done date
 * directly in the card body.
 */
@Composable
internal fun BookableHabitItem(
    habitWithStatus: HabitWithStatus,
    onClick: () -> Unit,
    onBook: () -> Unit,
    onLog: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val habit = habitWithStatus.habit
    val habitColor = try {
        Color(android.graphics.Color.parseColor(habit.color))
    } catch (_: Exception) {
        LocalPrismColors.current.primary
    }
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val prismColors = LocalPrismColors.current
    val cardShape = MaterialTheme.shapes.medium
    val attrs = LocalPrismAttrs.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(
                width = 1.dp,
                color = prismColors.primary.copy(alpha = 0.4f),
                shape = cardShape
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (attrs.brackets) {
                        Modifier.border(
                            androidx.compose.foundation.BorderStroke(width = 3.dp, color = habitColor),
                            shape = cardShape
                        ).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
                    } else {
                        Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(habitColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = habit.icon,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name + status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Booking status line
                if (habit.isBooked) {
                    val bookedDateStr = habit.bookedDate?.let { dateFormat.format(Date(it)) } ?: ""
                    val noteStr = habit.bookedNote?.let { " \u2014 $it" } ?: ""
                    Text(
                        text = "\uD83D\uDCC5 Booked: $bookedDateStr$noteStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalPrismColors.current.successColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "\u23F3 Not Booked",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalPrismColors.current.warningColor
                    )
                }

                // Last done line
                if (habitWithStatus.lastLogDate != null) {
                    val daysAgo = TimeUnit.MILLISECONDS.toDays(
                        System.currentTimeMillis() - habitWithStatus.lastLogDate
                    )
                    val lastDateStr = dateFormat.format(Date(habitWithStatus.lastLogDate))
                    Text(
                        text = "Last done: $lastDateStr ($daysAgo days ago)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "No activities logged yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Edit button
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit habit",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete habit",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Action buttons column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Book button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (habit.isBooked) {
                                habitColor.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ).clickable { onBook() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "\uD83D\uDCC5", style = MaterialTheme.typography.labelLarge)
                }
                // Log button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onLog() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "\u2705", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Dialog used by [BookableHabitItem] to book (or re-book) a slot for
 * a habit: presets for Today / Tomorrow / Next Week + a Material 3
 * date picker for arbitrary dates. Supports adding an optional note
 * (e.g. "Dr. Smith, 2pm") and an Unbook action when already booked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingDialog(
    habitWithStatus: HabitWithStatus,
    onConfirm: (Long, String?) -> Unit,
    onUnbook: () -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    var bookedDate by remember { mutableStateOf(habitWithStatus.habit.bookedDate ?: today) }
    var bookedNote by remember { mutableStateOf(habitWithStatus.habit.bookedNote ?: "") }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val tomorrow = remember {
        val cal = Calendar.getInstance()
        cal.timeInMillis = today
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.timeInMillis
    }
    val nextWeek = remember {
        val cal = Calendar.getInstance()
        cal.timeInMillis = today
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        cal.timeInMillis
    }
    val presetDates = remember { setOf(today, tomorrow, nextWeek) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(habitWithStatus.habit.icon)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Book ${habitWithStatus.habit.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Date selector buttons
                Text("Date", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today" to today, "Tomorrow" to tomorrow, "Next Week" to nextWeek).forEach { (label, date) ->
                        FilterChip(
                            selected = bookedDate == date,
                            onClick = { bookedDate = date },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                FilterChip(
                    selected = bookedDate !in presetDates,
                    onClick = { showDatePicker = true },
                    label = { Text("Pick Date\u2026", style = MaterialTheme.typography.labelSmall) }
                )
                Text(
                    text = "Selected: ${dateFormat.format(Date(bookedDate))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = bookedNote,
                    onValueChange = { bookedNote = it },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Dr. Smith, 2pm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(bookedDate, bookedNote.ifBlank { null }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (habitWithStatus.habit.isBooked) {
                    TextButton(onClick = onUnbook) {
                        Text("Unbook", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDayStartToUtcMillis(bookedDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { bookedDate = utcMillisToLocalDayStart(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
