package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.preferences.A11yPreferences
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.google.gson.JsonObject

/**
 * Importer for device/interaction-level config sections: accessibility,
 * voice, timer, and the wide notification block. Split out of
 * [ConfigImporter] because notification alone carries ~30 sub-keys and
 * the four together would push the dispatcher past the per-helper LOC
 * budget.
 */
internal class DeviceConfigImporter(
    private val a11yPreferences: A11yPreferences,
    private val voicePreferences: VoicePreferences,
    private val timerPreferences: TimerPreferences,
    private val notificationPreferences: NotificationPreferences
) {
    suspend fun importA11yConfig(config: JsonObject) {
        config.getAsJsonObject("a11y")?.let { a ->
            a.get("reduceMotion")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                a11yPreferences.setReduceMotion(it)
            }
            a.get("highContrast")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                a11yPreferences.setHighContrast(it)
            }
            a.get("largeTouchTargets")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                a11yPreferences.setLargeTouchTargets(it)
            }
        }
    }

    suspend fun importVoiceConfig(config: JsonObject) {
        config.getAsJsonObject("voice")?.let { v ->
            v.get("voiceInputEnabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                voicePreferences.setVoiceInputEnabled(it)
            }
            v.get("voiceFeedbackEnabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                voicePreferences.setVoiceFeedbackEnabled(it)
            }
            v.get("continuousModeEnabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                voicePreferences.setContinuousModeEnabled(it)
            }
        }
    }

    suspend fun importTimerConfig(config: JsonObject) {
        config.getAsJsonObject("timer")?.let { t ->
            t.get("workDurationSeconds")?.takeIf { !it.isJsonNull }?.asInt?.let {
                timerPreferences.setWorkDurationSeconds(it)
            }
            t.get("breakDurationSeconds")?.takeIf { !it.isJsonNull }?.asInt?.let {
                timerPreferences.setBreakDurationSeconds(it)
            }
            t.get("longBreakDurationSeconds")?.takeIf { !it.isJsonNull }?.asInt?.let {
                timerPreferences.setLongBreakDurationSeconds(it)
            }
            t.get("customDurationSeconds")?.takeIf { !it.isJsonNull }?.asInt?.let {
                timerPreferences.setCustomDurationSeconds(it)
            }
            t.get("pomodoroEnabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                timerPreferences.setPomodoroEnabled(it)
            }
            t.get("sessionsUntilLongBreak")?.takeIf { !it.isJsonNull }?.asInt?.let {
                timerPreferences.setSessionsUntilLongBreak(it)
            }
            t.get("autoStartBreaks")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                timerPreferences.setAutoStartBreaks(it)
            }
            t.get("autoStartWork")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                timerPreferences.setAutoStartWork(it)
            }
            t.get("pomodoroAvailableMinutes")?.takeIf { !it.isJsonNull }?.asInt?.let {
                timerPreferences.setPomodoroAvailableMinutes(it)
            }
            t.get("pomodoroFocusPreference")?.takeIf { !it.isJsonNull }?.asString?.let {
                timerPreferences.setPomodoroFocusPreference(it)
            }
            t.get("buzzUntilDismissed")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                timerPreferences.setBuzzUntilDismissed(it)
            }
            t.get("overrideVolume")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                timerPreferences.setOverrideVolume(it)
            }
            t.get("alarmVolumePercent")?.takeIf { !it.isJsonNull }?.asInt?.let {
                timerPreferences.setAlarmVolumePercent(it)
            }
        }
    }

    suspend fun importNotificationConfig(config: JsonObject) {
        val n = config.getAsJsonObject("notification") ?: return
        fun b(k: String): Boolean? = n.get(k)?.takeIf { !it.isJsonNull }?.asBoolean
        fun i(k: String): Int? = n.get(k)?.takeIf { !it.isJsonNull }?.asInt
        fun l(k: String): Long? = n.get(k)?.takeIf { !it.isJsonNull }?.asLong
        fun s(k: String): String? = n.get(k)?.takeIf { !it.isJsonNull }?.asString
        val p = notificationPreferences
        b("taskRemindersEnabled")?.let { p.setTaskRemindersEnabled(it) }
        b("timerAlertsEnabled")?.let { p.setTimerAlertsEnabled(it) }
        b("medicationRemindersEnabled")?.let { p.setMedicationRemindersEnabled(it) }
        b("dailyBriefingEnabled")?.let { p.setDailyBriefingEnabled(it) }
        b("eveningSummaryEnabled")?.let { p.setEveningSummaryEnabled(it) }
        b("weeklySummaryEnabled")?.let { p.setWeeklySummaryEnabled(it) }
        b("weeklyTaskSummaryEnabled")?.let { p.setWeeklyTaskSummaryEnabled(it) }
        b("overloadAlertsEnabled")?.let { p.setOverloadAlertsEnabled(it) }
        b("reengagementEnabled")?.let { p.setReengagementEnabled(it) }
        b("fullScreenNotificationsEnabled")?.let { p.setFullScreenNotificationsEnabled(it) }
        b("overrideVolumeEnabled")?.let { p.setOverrideVolumeEnabled(it) }
        b("repeatingVibrationEnabled")?.let { p.setRepeatingVibrationEnabled(it) }
        b("taskRemindersFollowSystem")?.let { p.setTaskRemindersFollowSystem(it) }
        b("taskRemindersVolumeLoud")?.let { p.setTaskRemindersVolumeLoud(it) }
        b("taskRemindersVibrationRepeat")?.let { p.setTaskRemindersVibrationRepeat(it) }
        b("timerAlertsFollowSystem")?.let { p.setTimerAlertsFollowSystem(it) }
        b("timerAlertsVolumeLoud")?.let { p.setTimerAlertsVolumeLoud(it) }
        b("timerAlertsVibrationRepeat")?.let { p.setTimerAlertsVibrationRepeat(it) }
        b("medicationRemindersFollowSystem")?.let { p.setMedicationRemindersFollowSystem(it) }
        b("medicationRemindersVolumeLoud")?.let { p.setMedicationRemindersVolumeLoud(it) }
        b("medicationRemindersVibrationRepeat")?.let { p.setMedicationRemindersVibrationRepeat(it) }
        s("importance")?.let { p.setImportance(it) }
        l("defaultReminderOffset")?.let { p.setDefaultReminderOffset(it) }
        l("activeProfileId")?.let { p.setActiveProfileId(it) }
        n.getAsJsonObject("categoryProfileOverrides")?.entrySet()?.forEach { (k, v) ->
            if (!v.isJsonNull) p.setCategoryProfileOverride(k, v.asLong)
        }
        b("streakAlertsEnabled")?.let { p.setStreakAlertsEnabled(it) }
        i("streakAtRiskLeadHours")?.let { p.setStreakAtRiskLeadHours(it) }
        i("briefingMorningHour")?.let { p.setBriefingMorningHour(it) }
        b("briefingMiddayEnabled")?.let { p.setBriefingMiddayEnabled(it) }
        i("briefingEveningHour")?.let { p.setBriefingEveningHour(it) }
        s("briefingTone")?.let { p.setBriefingTone(it) }
        n.getAsJsonArray("briefingSections")?.let { arr ->
            p.setBriefingSections(arr.mapNotNull { if (it.isJsonNull) null else it.asString }.toSet())
        }
        b("briefingReadAloud")?.let { p.setBriefingReadAloudEnabled(it) }
        s("collabDigestMode")?.let { p.setCollabDigestMode(it) }
        b("collabAssignedEnabled")?.let { p.setCollabAssignedEnabled(it) }
        b("collabMentionedEnabled")?.let { p.setCollabMentionedEnabled(it) }
        b("collabStatusEnabled")?.let { p.setCollabStatusEnabled(it) }
        b("collabCommentEnabled")?.let { p.setCollabCommentEnabled(it) }
        b("collabDueSoonEnabled")?.let { p.setCollabDueSoonEnabled(it) }
        s("watchSyncMode")?.let { p.setWatchSyncMode(it) }
        i("watchVolumePercent")?.let { p.setWatchVolumePercent(it) }
        s("watchHapticIntensity")?.let { p.setWatchHapticIntensity(it) }
        s("badgeMode")?.let { p.setBadgeMode(it) }
        s("toastPosition")?.let { p.setToastPosition(it) }
        b("highContrastNotifications")?.let { p.setHighContrastNotificationsEnabled(it) }
        i("habitNagSuppressionDays")?.let { p.setHabitNagSuppressionDays(it) }
        n.getAsJsonArray("snoozeDurationsMinutes")?.let { arr ->
            p.setSnoozeDurationsMinutes(arr.mapNotNull { if (it.isJsonNull) null else it.asInt })
        }
    }
}
