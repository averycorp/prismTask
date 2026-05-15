package com.averycorp.prismtask.ui.screens.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.components.settings.SettingsRowWithSubtitle
import com.averycorp.prismtask.ui.components.settings.SettingsToggleRow
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute

/**
 * Per-type enable/disable toggles. Mirrors the legacy
 * NotificationSettingsSection rows but lives under the new hub.
 */
@Composable
fun NotificationTypesScreen(
    navController: NavController,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val task by viewModel.taskRemindersEnabled.collectAsStateWithLifecycle()
    val timer by viewModel.timerAlertsEnabled.collectAsStateWithLifecycle()
    val med by viewModel.medicationRemindersEnabled.collectAsStateWithLifecycle()
    val taskFollowSystem by viewModel.taskRemindersFollowSystem.collectAsStateWithLifecycle()
    val taskVolumeLoud by viewModel.taskRemindersVolumeLoud.collectAsStateWithLifecycle()
    val taskVibrationRepeat by viewModel.taskRemindersVibrationRepeat.collectAsStateWithLifecycle()
    val timerFollowSystem by viewModel.timerAlertsFollowSystem.collectAsStateWithLifecycle()
    val timerVolumeLoud by viewModel.timerAlertsVolumeLoud.collectAsStateWithLifecycle()
    val timerVibrationRepeat by viewModel.timerAlertsVibrationRepeat.collectAsStateWithLifecycle()
    val medFollowSystem by viewModel.medicationRemindersFollowSystem.collectAsStateWithLifecycle()
    val medVolumeLoud by viewModel.medicationRemindersVolumeLoud.collectAsStateWithLifecycle()
    val medVibrationRepeat by viewModel.medicationRemindersVibrationRepeat.collectAsStateWithLifecycle()
    val briefing by viewModel.dailyBriefingEnabled.collectAsStateWithLifecycle()
    val evening by viewModel.eveningSummaryEnabled.collectAsStateWithLifecycle()
    val weekly by viewModel.weeklySummaryEnabled.collectAsStateWithLifecycle()
    val weeklyReviewAuto by viewModel.weeklyReviewAutoGenerateEnabled.collectAsStateWithLifecycle()
    val weeklyReviewNotify by viewModel.weeklyReviewNotificationEnabled.collectAsStateWithLifecycle()
    val weeklyAnalytics by viewModel.weeklyAnalyticsNotificationEnabled.collectAsStateWithLifecycle()
    val weeklyTask by viewModel.weeklyTaskSummaryEnabled.collectAsStateWithLifecycle()
    val streak by viewModel.streakAlertsEnabled.collectAsStateWithLifecycle()
    val reengage by viewModel.reengagementEnabled.collectAsStateWithLifecycle()
    val overload by viewModel.overloadAlertsEnabled.collectAsStateWithLifecycle()
    val nagSuppressionDays by viewModel.habitNagSuppressionDays.collectAsStateWithLifecycle()

    NotificationSubScreenScaffold("Notification Types", navController) {
        SubHeader("Tasks & Reminders")
        SettingsToggleRow(
            title = "Task Reminders",
            subtitle = "Fire before a task is due",
            checked = task,
            onCheckedChange = viewModel::setTaskRemindersEnabled
        )
        PerTypeOverrideBlock(
            enabled = task,
            followSystem = taskFollowSystem,
            volumeLoud = taskVolumeLoud,
            vibrationRepeat = taskVibrationRepeat,
            onFollowSystemChange = viewModel::setTaskRemindersFollowSystem,
            onVolumeLoudChange = viewModel::setTaskRemindersVolumeLoud,
            onVibrationRepeatChange = viewModel::setTaskRemindersVibrationRepeat
        )

        SettingsToggleRow(
            title = "Timer Alerts",
            subtitle = "When a timer or focus session completes",
            checked = timer,
            onCheckedChange = viewModel::setTimerAlertsEnabled
        )
        PerTypeOverrideBlock(
            enabled = timer,
            followSystem = timerFollowSystem,
            volumeLoud = timerVolumeLoud,
            vibrationRepeat = timerVibrationRepeat,
            onFollowSystemChange = viewModel::setTimerAlertsFollowSystem,
            onVolumeLoudChange = viewModel::setTimerAlertsVolumeLoud,
            onVibrationRepeatChange = viewModel::setTimerAlertsVibrationRepeat
        )

        SettingsToggleRow(
            title = "Medication Reminders",
            subtitle = "Medication and timed habit reminders",
            checked = med,
            onCheckedChange = viewModel::setMedicationRemindersEnabled
        )
        PerTypeOverrideBlock(
            enabled = med,
            followSystem = medFollowSystem,
            volumeLoud = medVolumeLoud,
            vibrationRepeat = medVibrationRepeat,
            onFollowSystemChange = viewModel::setMedicationRemindersFollowSystem,
            onVolumeLoudChange = viewModel::setMedicationRemindersVolumeLoud,
            onVibrationRepeatChange = viewModel::setMedicationRemindersVibrationRepeat
        )

        SectionSpacer()
        SubHeader("Habit Reminders")

        SettingsToggleRow(
            title = "Delay Habit Reminders If Scheduled",
            subtitle = if (nagSuppressionDays > 0) {
                "Suppress nag if the habit is booked within $nagSuppressionDays days"
            } else {
                "Disabled \u2014 nag notifications fire immediately"
            },
            checked = nagSuppressionDays > 0,
            onCheckedChange = { enabled ->
                viewModel.setHabitNagSuppressionDays(if (enabled) 7 else 0)
            }
        )

        if (nagSuppressionDays > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Suppression window:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$nagSuppressionDays days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = nagSuppressionDays.toFloat(),
                    onValueChange = { viewModel.setHabitNagSuppressionDays(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SectionSpacer()
        SubHeader("AI & Summaries")
        SettingsToggleRow(
            title = "Daily Briefing",
            subtitle = "Morning AI briefing",
            checked = briefing,
            onCheckedChange = viewModel::setDailyBriefingEnabled
        )
        SettingsToggleRow(
            title = "Evening Summary",
            subtitle = "End-of-day recap",
            checked = evening,
            onCheckedChange = viewModel::setEveningSummaryEnabled
        )
        SettingsToggleRow(
            title = "Weekly Habit Summary",
            subtitle = "Weekly habit recap (Sunday 7 PM)",
            checked = weekly,
            onCheckedChange = viewModel::setWeeklySummaryEnabled
        )
        SettingsToggleRow(
            title = "Weekly Task Summary",
            subtitle = "Weekly task recap (Sunday 7:30 PM)",
            checked = weeklyTask,
            onCheckedChange = viewModel::setWeeklyTaskSummaryEnabled
        )
        SettingsToggleRow(
            title = "Auto-Generate Weekly Reviews",
            subtitle = "AI-generated recap of your week (Sunday 8 PM)",
            checked = weeklyReviewAuto,
            onCheckedChange = viewModel::setWeeklyReviewAutoGenerateEnabled
        )
        SettingsToggleRow(
            title = "Notify Me When A Review Is Ready",
            subtitle = "Post a notification when a new weekly review is available",
            checked = weeklyReviewNotify,
            onCheckedChange = viewModel::setWeeklyReviewNotificationEnabled
        )
        SettingsToggleRow(
            title = "Weekly Analytics Summary",
            subtitle = "One-line score + tasks + habit-rate recap (Sunday 7 PM)",
            checked = weeklyAnalytics,
            onCheckedChange = viewModel::setWeeklyAnalyticsNotificationEnabled
        )
        SettingsRowWithSubtitle(
            title = "Weekly Review History",
            subtitle = "Read past weekly reviews",
            onClick = {
                navController.navigate(PrismTaskRoute.WeeklyReviewsList.route)
            }
        )

        SectionSpacer()
        SubHeader("Gamification & Life-Balance")
        SettingsToggleRow(
            title = "Streak & Milestones",
            subtitle = "Celebrate streak milestones and warn when streaks are at risk",
            checked = streak,
            onCheckedChange = viewModel::setStreakAlertsEnabled
        )
        SettingsToggleRow(
            title = "Balance Alerts",
            subtitle = "Nudge when work-life balance is skewing",
            checked = overload,
            onCheckedChange = viewModel::setOverloadAlertsEnabled
        )
        SettingsToggleRow(
            title = "Re-engagement",
            subtitle = "Gentle nudge after inactivity",
            checked = reengage,
            onCheckedChange = viewModel::setReengagementEnabled
        )
    }
}

/**
 * Volume + vibration sub-controls for a notification type. Hidden when
 * the parent type is disabled — there's nothing to tune until it fires.
 * When "Align with phone settings" is on, the loud/repeat toggles are
 * also hidden because they don't take effect; their stored values
 * persist so toggling the align switch off restores the user's choices.
 *
 * Indented one column to visually nest under the parent enable row.
 */
@Composable
private fun PerTypeOverrideBlock(
    enabled: Boolean,
    followSystem: Boolean,
    volumeLoud: Boolean,
    vibrationRepeat: Boolean,
    onFollowSystemChange: (Boolean) -> Unit,
    onVolumeLoudChange: (Boolean) -> Unit,
    onVibrationRepeatChange: (Boolean) -> Unit
) {
    if (!enabled) return
    Column(modifier = Modifier.padding(start = 16.dp)) {
        SettingsToggleRow(
            title = "Align with phone settings",
            subtitle = if (followSystem) {
                "Volume and vibration follow your phone's notification settings"
            } else {
                "Using app overrides below"
            },
            checked = followSystem,
            onCheckedChange = onFollowSystemChange
        )
        if (!followSystem) {
            SettingsToggleRow(
                title = "Loud (alarm volume)",
                subtitle = "Play at alarm volume, bypassing silent mode",
                checked = volumeLoud,
                onCheckedChange = onVolumeLoudChange
            )
            SettingsToggleRow(
                title = "Repeating vibration",
                subtitle = "Buzz repeatedly until you act on it",
                checked = vibrationRepeat,
                onCheckedChange = onVibrationRepeatChange
            )
        }
    }
}
