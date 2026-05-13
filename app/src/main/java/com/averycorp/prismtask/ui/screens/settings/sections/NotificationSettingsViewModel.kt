package com.averycorp.prismtask.ui.screens.settings.sections

import android.content.Context
import android.util.Log
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.notifications.NotificationWorkerScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notification-related Settings state and setters.
 *
 * Extracted from [com.averycorp.prismtask.ui.screens.settings.SettingsViewModel]
 * as part of T1.2 of REFACTOR_TIERS_1_3_AUDIT.
 */
class NotificationSettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val notificationPreferences: NotificationPreferences,
    private val notificationWorkerScheduler: NotificationWorkerScheduler
) {
    private lateinit var scope: CoroutineScope

    lateinit var taskRemindersEnabled: StateFlow<Boolean>
        private set
    lateinit var timerAlertsEnabled: StateFlow<Boolean>
        private set
    lateinit var medicationRemindersEnabled: StateFlow<Boolean>
        private set
    lateinit var habitNagSuppressionDays: StateFlow<Int>
        private set
    lateinit var dailyBriefingEnabled: StateFlow<Boolean>
        private set
    lateinit var eveningSummaryEnabled: StateFlow<Boolean>
        private set
    lateinit var weeklySummaryEnabled: StateFlow<Boolean>
        private set
    lateinit var weeklyTaskSummaryEnabled: StateFlow<Boolean>
        private set
    lateinit var overloadAlertsEnabled: StateFlow<Boolean>
        private set
    lateinit var reengagementEnabled: StateFlow<Boolean>
        private set
    lateinit var fullScreenNotificationsEnabled: StateFlow<Boolean>
        private set
    lateinit var overrideVolumeEnabled: StateFlow<Boolean>
        private set
    lateinit var repeatingVibrationEnabled: StateFlow<Boolean>
        private set
    lateinit var notificationImportance: StateFlow<String>
        private set
    lateinit var defaultReminderOffset: StateFlow<Long>
        private set

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        val started = SharingStarted.WhileSubscribed(5000)
        taskRemindersEnabled = notificationPreferences.taskRemindersEnabled.stateIn(scope, started, true)
        timerAlertsEnabled = notificationPreferences.timerAlertsEnabled.stateIn(scope, started, true)
        medicationRemindersEnabled =
            notificationPreferences.medicationRemindersEnabled.stateIn(scope, started, true)
        habitNagSuppressionDays = notificationPreferences.habitNagSuppressionDays
            .stateIn(scope, started, NotificationPreferences.DEFAULT_HABIT_NAG_SUPPRESSION_DAYS)
        dailyBriefingEnabled = notificationPreferences.dailyBriefingEnabled.stateIn(scope, started, true)
        eveningSummaryEnabled = notificationPreferences.eveningSummaryEnabled.stateIn(scope, started, true)
        weeklySummaryEnabled = notificationPreferences.weeklySummaryEnabled.stateIn(scope, started, true)
        weeklyTaskSummaryEnabled =
            notificationPreferences.weeklyTaskSummaryEnabled.stateIn(scope, started, true)
        overloadAlertsEnabled = notificationPreferences.overloadAlertsEnabled.stateIn(scope, started, true)
        reengagementEnabled = notificationPreferences.reengagementEnabled.stateIn(scope, started, true)
        fullScreenNotificationsEnabled =
            notificationPreferences.fullScreenNotificationsEnabled.stateIn(scope, started, false)
        overrideVolumeEnabled =
            notificationPreferences.overrideVolumeEnabled.stateIn(scope, started, false)
        repeatingVibrationEnabled =
            notificationPreferences.repeatingVibrationEnabled.stateIn(scope, started, false)
        notificationImportance = notificationPreferences.importance
            .stateIn(scope, started, NotificationPreferences.DEFAULT_IMPORTANCE)
        defaultReminderOffset = notificationPreferences.defaultReminderOffset
            .stateIn(scope, started, NotificationPreferences.DEFAULT_REMINDER_OFFSET_MS)
    }

    fun setTaskRemindersEnabled(enabled: Boolean) {
        scope.launch { notificationPreferences.setTaskRemindersEnabled(enabled) }
    }

    fun setTimerAlertsEnabled(enabled: Boolean) {
        scope.launch { notificationPreferences.setTimerAlertsEnabled(enabled) }
    }

    fun setMedicationRemindersEnabled(enabled: Boolean) {
        scope.launch { notificationPreferences.setMedicationRemindersEnabled(enabled) }
    }

    fun setHabitNagSuppressionDays(days: Int) {
        scope.launch { notificationPreferences.setHabitNagSuppressionDays(days) }
    }

    fun setDailyBriefingEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setDailyBriefingEnabled(enabled)
            notificationWorkerScheduler.applyBriefing(enabled)
        }
    }

    fun setEveningSummaryEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setEveningSummaryEnabled(enabled)
            notificationWorkerScheduler.applyEveningSummary(enabled)
        }
    }

    fun setWeeklyHabitSummaryEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setWeeklySummaryEnabled(enabled)
            notificationWorkerScheduler.applyWeeklyHabitSummary(enabled)
        }
    }

    fun setWeeklyTaskSummaryEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setWeeklyTaskSummaryEnabled(enabled)
            notificationWorkerScheduler.applyWeeklyTaskSummary(enabled)
        }
    }

    fun setOverloadAlertsEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setOverloadAlertsEnabled(enabled)
            notificationWorkerScheduler.applyOverloadCheck(enabled)
        }
    }

    fun setReengagementEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setReengagementEnabled(enabled)
            notificationWorkerScheduler.applyReengagement(enabled)
        }
    }

    fun setFullScreenNotificationsEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setFullScreenNotificationsEnabled(enabled)
        }
    }

    fun setOverrideVolumeEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setOverrideVolumeEnabled(enabled)
            // Channel sound is immutable; recreate so the new alarm-stream
            // audio attributes take effect next reminder.
            try {
                com.averycorp.prismtask.notifications.NotificationHelper
                    .createNotificationChannel(appContext)
            } catch (_: Exception) {
            }
        }
    }

    fun setRepeatingVibrationEnabled(enabled: Boolean) {
        scope.launch {
            notificationPreferences.setRepeatingVibrationEnabled(enabled)
            // Channel vibration pattern is immutable; recreate so the new
            // pattern takes effect next reminder.
            try {
                com.averycorp.prismtask.notifications.NotificationHelper
                    .createNotificationChannel(appContext)
            } catch (_: Exception) {
            }
        }
    }

    fun setNotificationImportance(level: String) {
        scope.launch {
            notificationPreferences.setImportance(level)
            // Re-create the channel for the current importance — the helper
            // tears down the stale channel (whose importance is immutable)
            // and creates a fresh one tagged with the new importance suffix.
            try {
                com.averycorp.prismtask.notifications.NotificationHelper
                    .createNotificationChannel(appContext)
            } catch (e: Exception) {
                Log.w("SettingsVM", "Failed to recreate notification channel after importance change", e)
            }
        }
    }

    fun setDefaultReminderOffset(offsetMs: Long) {
        scope.launch { notificationPreferences.setDefaultReminderOffset(offsetMs) }
    }
}
