package com.averycorp.prismtask.ui.screens.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.domain.model.notifications.QuietHoursWindow
import com.averycorp.prismtask.domain.model.notifications.UrgencyTier
import com.averycorp.prismtask.domain.usecase.NotificationProfileResolver
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import com.averycorp.prismtask.ui.components.settings.SettingsToggleRow
import java.time.DayOfWeek
import java.time.LocalTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationQuietHoursScreen(
    navController: NavController,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(profile.quietHours.enabled) }
    var startHour by remember { mutableStateOf(profile.quietHours.start.hour) }
    var startMinute by remember { mutableStateOf(profile.quietHours.start.minute) }
    var endHour by remember { mutableStateOf(profile.quietHours.end.hour) }
    var endMinute by remember { mutableStateOf(profile.quietHours.end.minute) }
    var days by remember { mutableStateOf(profile.quietHours.days.toMutableSet()) }
    var breakThrough by remember { mutableStateOf(profile.quietHours.priorityOverrideTiers.toMutableSet()) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    NotificationSubScreenScaffold("Quiet hours", navController) {
        SettingsToggleRow(
            title = "Enable quiet hours",
            subtitle = "Defer notifications to the end of the window",
            checked = enabled,
            onCheckedChange = { enabled = it }
        )

        if (enabled) {
            SubHeader("Window")
            QuietHoursTimeRow(
                label = "Starts at",
                hour = startHour,
                minute = startMinute,
                onClick = { showStartPicker = true }
            )
            QuietHoursTimeRow(
                label = "Ends at",
                hour = endHour,
                minute = endMinute,
                onClick = { showEndPicker = true }
            )
            val isOvernight = startHour > endHour ||
                (startHour == endHour && startMinute > endMinute)
            Text(
                text = if (isOvernight) {
                    "Overnight window — starts at ${formatHm(
                        startHour,
                        startMinute
                    )} today and ends at ${formatHm(endHour, endMinute)} tomorrow."
                } else {
                    "Same-day window."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            SubHeader("Days")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DayOfWeek.values().forEach { day ->
                    val checked = day in days
                    FilterChip(
                        selected = checked,
                        onClick = {
                            days = days.toMutableSet().also {
                                if (checked) it.remove(day) else it.add(day)
                            }
                        },
                        label = { Text(day.name.take(3)) }
                    )
                }
            }

            SubHeader("Break-through allowlist")
            Text(
                "Urgency tiers that can still fire during quiet hours. " +
                    "Allow High and Critical so medication doses and " +
                    "time-sensitive reminders aren't silenced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UrgencyTier.values().forEach { tier ->
                    val checked = tier in breakThrough
                    FilterChip(
                        selected = checked,
                        onClick = {
                            breakThrough = breakThrough.toMutableSet().also {
                                if (checked) it.remove(tier) else it.add(tier)
                            }
                        },
                        label = { Text(tier.label) }
                    )
                }
            }
        }

        Button(
            onClick = {
                val entity = profiles.firstOrNull { it.id == profile.id } ?: return@Button
                val window = QuietHoursWindow(
                    enabled = enabled,
                    start = LocalTime.of(startHour, startMinute),
                    end = LocalTime.of(endHour, endMinute),
                    days = days.toSet(),
                    priorityOverrideTiers = breakThrough.toSet()
                )
                val json = NotificationProfileResolver.DEFAULT.encodeQuietHours(window)
                viewModel.commitProfileEdit(entity.copy(quietHoursJson = json))
                navController.popBackStack()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text("Save") }
    }

    if (showStartPicker) {
        QuietHoursTimePickerDialog(
            title = "Quiet hours start",
            initialHour = startHour,
            initialMinute = startMinute,
            onConfirm = { h, m ->
                startHour = h
                startMinute = m
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        QuietHoursTimePickerDialog(
            title = "Quiet hours end",
            initialHour = endHour,
            initialMinute = endMinute,
            onConfirm = { h, m ->
                endHour = h
                endMinute = m
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

private fun formatHm(hour: Int, minute: Int): String {
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val suffix = if (hour < 12) "AM" else "PM"
    return "%d:%02d %s".format(displayHour, minute, suffix)
}

@Composable
private fun QuietHoursTimeRow(
    label: String,
    hour: Int,
    minute: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatHm(hour, minute),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun QuietHoursTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val clockState = rememberAnalogClockState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { AnalogClockPicker(state = clockState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(clockState.hour, clockState.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
