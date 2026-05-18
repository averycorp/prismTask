package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsRowWithSubtitle

@Composable
fun HabitsSection(
    streakMaxMissedDays: Int,
    onStreakMaxMissedDaysChange: (Int) -> Unit,
    todaySkipAfterCompleteDays: Int,
    onTodaySkipAfterCompleteDaysChange: (Int) -> Unit,
    todaySkipBeforeScheduleDays: Int,
    onTodaySkipBeforeScheduleDaysChange: (Int) -> Unit,
    skipCapPerWeek: Int,
    onSkipCapPerWeekChange: (Int) -> Unit
) {
    var showStreakDialog by remember { mutableStateOf(false) }
    var showSkipAfterDialog by remember { mutableStateOf(false) }
    var showSkipBeforeDialog by remember { mutableStateOf(false) }
    var showSkipCapDialog by remember { mutableStateOf(false) }

    if (showStreakDialog) {
        StreakMaxMissedDaysDialog(
            current = streakMaxMissedDays,
            onConfirm = {
                onStreakMaxMissedDaysChange(it)
                showStreakDialog = false
            },
            onDismiss = { showStreakDialog = false }
        )
    }

    if (showSkipAfterDialog) {
        TodaySkipDaysDialog(
            title = "Hide After Completion",
            description = "Hide a recurring habit from the Today screen for this many days " +
                "after it was completed. Set to 0 to disable; per-habit overrides take " +
                "precedence over this default.",
            current = todaySkipAfterCompleteDays,
            onConfirm = {
                onTodaySkipAfterCompleteDaysChange(it)
                showSkipAfterDialog = false
            },
            onDismiss = { showSkipAfterDialog = false }
        )
    }

    if (showSkipBeforeDialog) {
        TodaySkipDaysDialog(
            title = "Hide Before Next Schedule",
            description = "Hide a recurring habit from the Today screen if its next " +
                "scheduled occurrence falls within this many days. Set to 0 to disable; " +
                "per-habit overrides take precedence over this default.",
            current = todaySkipBeforeScheduleDays,
            onConfirm = {
                onTodaySkipBeforeScheduleDaysChange(it)
                showSkipBeforeDialog = false
            },
            onDismiss = { showSkipBeforeDialog = false }
        )
    }

    if (showSkipCapDialog) {
        SkipCapDialog(
            current = skipCapPerWeek,
            onConfirm = {
                onSkipCapPerWeekChange(it)
                showSkipCapDialog = false
            },
            onDismiss = { showSkipCapDialog = false }
        )
    }

    SectionHeader("Habits")

    SettingsRowWithSubtitle(
        title = "Streak Grace Period",
        subtitle = subtitleForMissedDays(streakMaxMissedDays),
        onClick = { showStreakDialog = true }
    )

    SettingsRowWithSubtitle(
        title = "Hide After Completion",
        subtitle = subtitleForSkipDays(todaySkipAfterCompleteDays, "after a recent completion"),
        onClick = { showSkipAfterDialog = true }
    )

    SettingsRowWithSubtitle(
        title = "Hide Before Next Schedule",
        subtitle = subtitleForSkipDays(
            todaySkipBeforeScheduleDays,
            "before the next scheduled occurrence"
        ),
        onClick = { showSkipBeforeDialog = true }
    )

    SettingsRowWithSubtitle(
        title = "Skip Cap",
        subtitle = subtitleForSkipCap(skipCapPerWeek),
        onClick = { showSkipCapDialog = true }
    )

    HorizontalDivider()
}

private fun subtitleForSkipCap(cap: Int): String = when (cap) {
    0 -> "Unlimited long-press skips per habit"
    1 -> "1 long-press skip per habit per 7 days"
    else -> "$cap long-press skips per habit per 7 days"
}

private fun subtitleForMissedDays(days: Int): String = when (days) {
    1 -> "1 missed day ends a streak"
    else -> "$days missed days end a streak"
}

private fun subtitleForSkipDays(days: Int, scopeText: String): String = when {
    days <= 0 -> "Disabled"
    days == 1 -> "Hide on Today for 1 day $scopeText"
    else -> "Hide on Today for $days days $scopeText"
}

@Composable
private fun TodaySkipDaysDialog(
    title: String,
    description: String,
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val max = HabitListPreferences.MAX_TODAY_SKIP_DAYS
    var value by remember(current) { mutableIntStateOf(current.coerceIn(0, max)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = if (value == 0) "Disabled" else if (value == 1) "1 day" else "$value days",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt().coerceIn(0, max) },
                    valueRange = 0f..max.toFloat(),
                    steps = max - 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Off",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$max days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SkipCapDialog(
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val min = HabitListPreferences.MIN_SKIP_CAP_PER_WEEK
    val max = HabitListPreferences.MAX_SKIP_CAP_PER_WEEK
    var value by remember(current) { mutableIntStateOf(current.coerceIn(min, max)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip Cap") },
        text = {
            Column {
                Text(
                    text = subtitleForSkipCap(value),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt().coerceIn(min, max) },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min) - 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Off",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$max / 7 days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Long-press a habit's circle to mark today as a skip without " +
                        "breaking the streak. This cap is per habit and applies to a " +
                        "rolling 7-day window. Set to 0 to allow unlimited skips.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StreakMaxMissedDaysDialog(
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val min = HabitListPreferences.MIN_STREAK_MAX_MISSED_DAYS
    val max = HabitListPreferences.MAX_STREAK_MAX_MISSED_DAYS
    var value by remember(current) { mutableIntStateOf(current.coerceIn(min, max)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Streak Grace Period") },
        text = {
            Column {
                Text(
                    text = subtitleForMissedDays(value),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt().coerceIn(min, max) },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min) - 1
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "$min day",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$max days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Choose how many consecutive missed days break a daily-habit streak. " +
                        "At 1, any missed day ends the streak (original behavior). Higher values " +
                        "forgive occasional gaps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
