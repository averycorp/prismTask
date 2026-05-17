package com.averycorp.prismtask.ui.screens.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import com.averycorp.prismtask.ui.components.settings.SettingsToggleRow

@Composable
fun NotificationBriefingScreen(
    navController: NavController,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val morningHour by viewModel.briefingMorningHour.collectAsStateWithLifecycle()
    val eveningHour by viewModel.briefingEveningHour.collectAsStateWithLifecycle()
    val middayEnabled by viewModel.briefingMiddayEnabled.collectAsStateWithLifecycle()
    val tone by viewModel.briefingTone.collectAsStateWithLifecycle()
    val sections by viewModel.briefingSections.collectAsStateWithLifecycle()
    val readAloud by viewModel.briefingReadAloudEnabled.collectAsStateWithLifecycle()

    var showMorningPicker by remember { mutableStateOf(false) }
    var showEveningPicker by remember { mutableStateOf(false) }

    NotificationSubScreenScaffold("Daily Briefing", navController) {
        SubHeader("Delivery times")
        BriefingHourRow(
            label = "Morning briefing",
            hour = morningHour,
            onClick = { showMorningPicker = true }
        )
        BriefingHourRow(
            label = "Evening briefing",
            hour = eveningHour,
            onClick = { showEveningPicker = true }
        )
        SettingsToggleRow(
            title = "Add midday check-in",
            subtitle = "A short briefing at 12:30 PM",
            checked = middayEnabled,
            onCheckedChange = viewModel::setBriefingMiddayEnabled
        )

        SectionSpacer()
        SubHeader("Tone")
        listOf(
            NotificationPreferences.BRIEFING_TONE_CONCISE to "Concise — Just the facts",
            NotificationPreferences.BRIEFING_TONE_CONVERSATIONAL to "Conversational — Friendly rundown",
            NotificationPreferences.BRIEFING_TONE_MOTIVATIONAL to "Motivational — Pep-talk energy"
        ).forEach { (key, label) ->
            RadioRow(
                label = label,
                selected = tone == key,
                onSelect = { viewModel.setBriefingTone(key) }
            )
        }

        SectionSpacer()
        SubHeader("Sections")
        Text("Tap to mute sections you don't need.", style = MaterialTheme.typography.bodySmall)
        listOf(
            NotificationPreferences.BRIEFING_SECTION_TODAY_TASKS to "Today's tasks",
            NotificationPreferences.BRIEFING_SECTION_OVERDUE to "Overdue items",
            NotificationPreferences.BRIEFING_SECTION_UPCOMING to "Upcoming deadlines",
            NotificationPreferences.BRIEFING_SECTION_COLLAB to "Collaborator updates",
            NotificationPreferences.BRIEFING_SECTION_STREAK to "Streak status"
        ).forEach { (key, label) ->
            CheckableRow(
                label = label,
                checked = key in sections,
                onToggle = { viewModel.toggleBriefingSection(key, key !in sections) }
            )
        }

        SectionSpacer()
        SubHeader("Delivery")
        SettingsToggleRow(
            title = "Read aloud",
            subtitle = "Use TTS to read the briefing when tapped",
            checked = readAloud,
            onCheckedChange = viewModel::setBriefingReadAloud
        )
    }

    if (showMorningPicker) {
        BriefingHourPickerDialog(
            title = "Morning briefing",
            initialHour = morningHour,
            onConfirm = { h ->
                showMorningPicker = false
                viewModel.setBriefingMorningHour(h)
            },
            onDismiss = { showMorningPicker = false }
        )
    }

    if (showEveningPicker) {
        BriefingHourPickerDialog(
            title = "Evening briefing",
            initialHour = eveningHour,
            onConfirm = { h ->
                showEveningPicker = false
                viewModel.setBriefingEveningHour(h)
            },
            onDismiss = { showEveningPicker = false }
        )
    }
}

/**
 * Clickable row that shows the current briefing hour and opens an
 * [AnalogClockPicker] dialog on tap. The previous implementation used a
 * Material 3 `Slider(valueRange = 0f..23f, steps = 22, onValueChange =
 * { it.toInt() })`, which snaps to tick positions computed via `lerp(0f,
 * 23f, i/23f)` in single-precision float — those values round to
 * `i ± ε`, and `it.toInt()` truncates the `i-ε` case down to `i-1`, so
 * the user saw certain hours skipped or repeated as they dragged. Per
 * the `feedback-time-input-use-clock-not-slider` memory, time-of-day
 * inputs use the analog clock picker app-wide. PR #1488 applied the
 * same fix to the onboarding Start-of-Day picker.
 */
@Composable
private fun BriefingHourRow(label: String, hour: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(formatHourLabel(hour), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Inlined hour-only picker dialog. The data model stores hour-only, but
 * the dial still renders all three hands (hour / minute / second) per
 * the `feedback-time-input-use-clock-not-slider` memory so the time
 * input affordance stays consistent across the app — minute + second
 * are picked but discarded on save.
 *
 * Inlined here rather than extracted into `ui/components/` to avoid
 * conflicting with a parallel worker fixing the same bug in
 * `AdvancedTuningScreen.kt`; the two files duplicate a few lines of
 * `AlertDialog` boilerplate, which is fine for now.
 */
@Composable
private fun BriefingHourPickerDialog(
    title: String,
    initialHour: Int,
    onConfirm: (hour: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val clockState = rememberAnalogClockState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = 0,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                AnalogClockPicker(state = clockState)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clockState.hour) }) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatHourLabel(hour: Int): String {
    val h = hour.coerceIn(0, 23)
    val am = h < 12
    val display = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$display:00 ${if (am) "AM" else "PM"}"
}
