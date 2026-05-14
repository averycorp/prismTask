package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.ui.components.settings.DurationPickerDialog
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsRowWithSubtitle
import com.averycorp.prismtask.ui.components.settings.SettingsToggleRow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerSection(
    timerWorkSeconds: Int,
    timerBreakSeconds: Int,
    timerLongBreakSeconds: Int,
    timerCustomSeconds: Int,
    pomodoroAvailableMinutes: Int,
    pomodoroFocusPreference: String,
    buzzUntilDismissed: Boolean,
    overrideVolume: Boolean,
    alarmVolumePercent: Int,
    // A2 Pomodoro+ AI Coaching — each surface has its own toggle, all default on.
    preSessionCoachingEnabled: Boolean,
    breakCoachingEnabled: Boolean,
    recapCoachingEnabled: Boolean,
    onTimerWorkMinutesChange: (Int) -> Unit,
    onTimerBreakMinutesChange: (Int) -> Unit,
    onTimerLongBreakMinutesChange: (Int) -> Unit,
    onTimerCustomMinutesChange: (Int) -> Unit,
    onPomodoroAvailableMinutesChange: (Int) -> Unit,
    onPomodoroFocusPreferenceChange: (String) -> Unit,
    onBuzzUntilDismissedChange: (Boolean) -> Unit,
    onOverrideVolumeChange: (Boolean) -> Unit,
    onAlarmVolumePercentChange: (Int) -> Unit,
    onPreSessionCoachingChange: (Boolean) -> Unit,
    onBreakCoachingChange: (Boolean) -> Unit,
    onRecapCoachingChange: (Boolean) -> Unit
) {
    var showTimerWorkDialog by remember { mutableStateOf(false) }
    var showTimerBreakDialog by remember { mutableStateOf(false) }
    var showTimerLongBreakDialog by remember { mutableStateOf(false) }
    var showTimerCustomDialog by remember { mutableStateOf(false) }
    var showAvailableTimeDialog by remember { mutableStateOf(false) }
    var showFocusDialog by remember { mutableStateOf(false) }
    var showAlarmVolumeDialog by remember { mutableStateOf(false) }

    if (showTimerWorkDialog) {
        DurationPickerDialog(
            title = "Work Duration",
            currentMinutes = timerWorkSeconds / 60,
            onConfirm = {
                onTimerWorkMinutesChange(it)
                showTimerWorkDialog = false
            },
            onDismiss = { showTimerWorkDialog = false }
        )
    }

    if (showTimerBreakDialog) {
        DurationPickerDialog(
            title = "Short Break Duration",
            currentMinutes = timerBreakSeconds / 60,
            onConfirm = {
                onTimerBreakMinutesChange(it)
                showTimerBreakDialog = false
            },
            onDismiss = { showTimerBreakDialog = false }
        )
    }

    if (showTimerLongBreakDialog) {
        DurationPickerDialog(
            title = "Long Break Duration",
            currentMinutes = timerLongBreakSeconds / 60,
            onConfirm = {
                onTimerLongBreakMinutesChange(it)
                showTimerLongBreakDialog = false
            },
            onDismiss = { showTimerLongBreakDialog = false }
        )
    }

    if (showTimerCustomDialog) {
        DurationPickerDialog(
            title = "Custom Duration",
            currentMinutes = timerCustomSeconds / 60,
            onConfirm = {
                onTimerCustomMinutesChange(it)
                showTimerCustomDialog = false
            },
            onDismiss = { showTimerCustomDialog = false }
        )
    }

    if (showAvailableTimeDialog) {
        AvailableTimePickerDialog(
            currentMinutes = pomodoroAvailableMinutes,
            onConfirm = {
                onPomodoroAvailableMinutesChange(it)
                showAvailableTimeDialog = false
            },
            onDismiss = { showAvailableTimeDialog = false }
        )
    }

    if (showFocusDialog) {
        FocusStylePickerDialog(
            current = pomodoroFocusPreference,
            onConfirm = {
                onPomodoroFocusPreferenceChange(it)
                showFocusDialog = false
            },
            onDismiss = { showFocusDialog = false }
        )
    }

    if (showAlarmVolumeDialog) {
        AlarmVolumePickerDialog(
            currentPercent = alarmVolumePercent,
            onConfirm = {
                onAlarmVolumePercentChange(it)
                showAlarmVolumeDialog = false
            },
            onDismiss = { showAlarmVolumeDialog = false }
        )
    }

    SectionHeader("Timer & Pomodoro")

    SettingsRowWithSubtitle(
        title = "Work Duration",
        subtitle = "${timerWorkSeconds / 60} min",
        onClick = { showTimerWorkDialog = true }
    )
    SettingsRowWithSubtitle(
        title = "Short Break Duration",
        subtitle = "${timerBreakSeconds / 60} min",
        onClick = { showTimerBreakDialog = true }
    )
    SettingsRowWithSubtitle(
        title = "Long Break Duration",
        subtitle = "${timerLongBreakSeconds / 60} min",
        onClick = { showTimerLongBreakDialog = true }
    )
    SettingsRowWithSubtitle(
        title = "Custom Duration",
        subtitle = "${timerCustomSeconds / 60} min",
        onClick = { showTimerCustomDialog = true }
    )
    SettingsRowWithSubtitle(
        title = "Available Focus Time",
        subtitle = formatAvailableMinutes(pomodoroAvailableMinutes),
        onClick = { showAvailableTimeDialog = true }
    )
    SettingsRowWithSubtitle(
        title = "Focus Style",
        subtitle = focusStyleLabel(pomodoroFocusPreference),
        onClick = { showFocusDialog = true }
    )
    SettingsToggleRow(
        title = "Buzz Until Dismissed",
        subtitle = "Vibrate continuously when the timer ends until you tap or swipe the notification.",
        checked = buzzUntilDismissed,
        onCheckedChange = onBuzzUntilDismissedChange
    )
    SettingsToggleRow(
        title = "Override System Volume",
        subtitle = "Ring the timer at a fixed volume even if the alarm slider is low or muted.",
        checked = overrideVolume,
        onCheckedChange = onOverrideVolumeChange
    )
    if (overrideVolume) {
        SettingsRowWithSubtitle(
            title = "Alarm Volume",
            subtitle = "$alarmVolumePercent%",
            onClick = { showAlarmVolumeDialog = true }
        )
    }

    // ---- A2 Pomodoro+ AI Coaching toggles ---------------------------
    // Grouped with the other Pomodoro controls. Toggles are independent —
    // a user can enable any subset of the three coaching surfaces.
    SettingsToggleRow(
        title = "AI Coaching Before Sessions",
        subtitle = "Get a suggested approach from Claude before each focus session starts.",
        checked = preSessionCoachingEnabled,
        onCheckedChange = onPreSessionCoachingChange
    )
    SettingsToggleRow(
        title = "AI Break Suggestions",
        subtitle = "Contextual break activities during short and long breaks.",
        checked = breakCoachingEnabled,
        onCheckedChange = onBreakCoachingChange
    )
    SettingsToggleRow(
        title = "AI Session Recap",
        subtitle = "A quick recap and \"carry forward\" suggestion when a session ends.",
        checked = recapCoachingEnabled,
        onCheckedChange = onRecapCoachingChange
    )

    HorizontalDivider()
}

private fun formatAvailableMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

private fun focusStyleLabel(key: String): String = when (key) {
    "deep_work" -> "Deep Work"
    "quick_wins" -> "Quick Wins"
    "balanced" -> "Balanced"
    "deadline_driven" -> "Deadline Driven"
    else -> "Balanced"
}

@Composable
private fun AvailableTimePickerDialog(
    currentMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val minMinutes = TimerPreferences.MIN_AVAILABLE_MINUTES
    val maxMinutes = TimerPreferences.MAX_AVAILABLE_MINUTES
    var minutes by remember(currentMinutes) {
        mutableIntStateOf(currentMinutes.coerceIn(minMinutes, maxMinutes))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Available Focus Time") },
        text = {
            Column {
                Text(
                    text = formatAvailableMinutes(minutes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = {
                        // Snap to 15-minute increments.
                        val snapped = (it / 15).toInt() * 15
                        minutes = snapped.coerceIn(minMinutes, maxMinutes)
                    },
                    valueRange = minMinutes.toFloat()..maxMinutes.toFloat(),
                    steps = ((maxMinutes - minMinutes) / 15) - 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatAvailableMinutes(minMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatAvailableMinutes(maxMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(minutes) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AlarmVolumePickerDialog(
    currentPercent: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val minPercent = TimerPreferences.MIN_ALARM_VOLUME_PERCENT
    val maxPercent = TimerPreferences.MAX_ALARM_VOLUME_PERCENT
    var percent by remember(currentPercent) {
        mutableIntStateOf(currentPercent.coerceIn(minPercent, maxPercent))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alarm Volume") },
        text = {
            Column {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                Slider(
                    value = percent.toFloat(),
                    onValueChange = {
                        percent = it.toInt().coerceIn(minPercent, maxPercent)
                    },
                    valueRange = minPercent.toFloat()..maxPercent.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "$minPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$maxPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(percent) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusStylePickerDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(current) { mutableStateOf(current) }
    val options = listOf(
        "deep_work" to "Deep Work",
        "quick_wins" to "Quick Wins",
        "balanced" to "Balanced",
        "deadline_driven" to "Deadline Driven"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Focus Style") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (key, label) ->
                        FilterChip(
                            selected = selected == key,
                            onClick = { selected = key },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val description = when (selected) {
                    "deep_work" -> "Complex tasks, batch similar work"
                    "quick_wins" -> "Short tasks first, build momentum"
                    "balanced" -> "Mix of quick wins and deep work"
                    "deadline_driven" -> "Most urgent deadlines first"
                    else -> ""
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
