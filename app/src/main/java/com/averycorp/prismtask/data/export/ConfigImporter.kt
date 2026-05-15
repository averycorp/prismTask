package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.preferences.ArchivePreferences
import com.averycorp.prismtask.data.preferences.BuiltInSortOrders
import com.averycorp.prismtask.data.preferences.CoachingPreferences
import com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.MedicationScheduleMode
import com.averycorp.prismtask.data.preferences.MorningCheckInPreferences
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.TabPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.UrgencyWeights
import com.google.gson.JsonObject
import java.time.DayOfWeek

/**
 * Dispatcher for every `config.*` block produced by [DataExporter]:
 * theme, archive, dashboard, tabs, task behavior, habit list, medication,
 * daily-essentials, morning check-in, calendar sync, templates,
 * onboarding, coaching, and sort. The cross-cutting `userPreferences`
 * block lives in [UserPreferencesImporter]; accessibility / voice /
 * timer / notification live in [DeviceConfigImporter]; the power-user
 * tuning + ND blocks live in [AdvancedTuningImporter]. The split keeps
 * each helper under the per-helper LOC budget.
 */
internal class ConfigImporter(
    private val themePreferences: ThemePreferences,
    private val archivePreferences: ArchivePreferences,
    private val dashboardPreferences: DashboardPreferences,
    private val tabPreferences: TabPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val habitListPreferences: HabitListPreferences,
    private val medicationPreferences: MedicationPreferences,
    private val dailyEssentialsPreferences: DailyEssentialsPreferences,
    private val morningCheckInPreferences: MorningCheckInPreferences,
    private val calendarSyncPreferences: CalendarSyncPreferences,
    private val templatePreferences: TemplatePreferences,
    private val onboardingPreferences: OnboardingPreferences,
    private val coachingPreferences: CoachingPreferences,
    private val sortPreferences: SortPreferences,
    private val userPrefsImporter: UserPreferencesImporter,
    private val deviceConfig: DeviceConfigImporter,
    private val advancedTuning: AdvancedTuningImporter
) {
    suspend fun importConfig(ctx: ImportContext, root: JsonObject) {
        root.getAsJsonObject("config")?.let { config ->
            try {
                importThemeConfig(config)
                importArchiveConfig(config)
                importDashboardConfig(config)
                importTabsConfig(config)
                importTaskBehaviorConfig(config)
                importHabitListConfig(config)
                importLeisureConfig(ctx, config)
                importMedicationConfig(config)
                userPrefsImporter.importUserPreferencesConfig(config)
                deviceConfig.importA11yConfig(config)
                deviceConfig.importVoiceConfig(config)
                deviceConfig.importTimerConfig(config)
                deviceConfig.importNotificationConfig(config)
                advancedTuning.importNdConfig(config)
                importDailyEssentialsConfig(config)
                importMorningCheckInConfig(config)
                importCalendarSyncConfig(config)
                importTemplatesConfig(config)
                importOnboardingConfig(config)
                importCoachingConfig(config)
                importSortConfig(config)
                advancedTuning.importAdvancedTuningConfig(config)
                ctx.configImported = true
            } catch (e: Exception) {
                ctx.errors.add("Failed to import config: ${e.message}")
            }
        }
    }

    private suspend fun importThemeConfig(config: JsonObject) {
        config.getAsJsonObject("theme")?.let { theme ->
            theme.get("themeMode")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setThemeMode(it) }
            theme.get("accentColor")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setAccentColor(it) }
            theme.get("backgroundColor")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setBackgroundColor(it) }
            theme.get("surfaceColor")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setSurfaceColor(it) }
            theme.get("errorColor")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setErrorColor(it) }
            theme.get("fontScale")?.takeIf { !it.isJsonNull }?.asFloat
                ?.let { themePreferences.setFontScale(it) }
            theme.get("priorityColorNone")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setPriorityColor(0, it) }
            theme.get("priorityColorLow")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setPriorityColor(1, it) }
            theme.get("priorityColorMedium")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setPriorityColor(2, it) }
            theme.get("priorityColorHigh")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setPriorityColor(3, it) }
            theme.get("priorityColorUrgent")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setPriorityColor(4, it) }
            theme.getAsJsonArray("recentCustomColors")?.forEach { elem ->
                if (!elem.isJsonNull) themePreferences.addRecentCustomColor(elem.asString)
            }
            theme.get("prismTheme")?.takeIf { !it.isJsonNull }?.asString
                ?.let { themePreferences.setPrismTheme(it) }
        }
    }

    private suspend fun importArchiveConfig(config: JsonObject) {
        config.getAsJsonObject("archive")?.let { archive ->
            archive.get("autoArchiveDays")?.takeIf { !it.isJsonNull }?.asInt
                ?.let { archivePreferences.setAutoArchiveDays(it) }
        }
    }

    private suspend fun importDashboardConfig(config: JsonObject) {
        config.getAsJsonObject("dashboard")?.let { dashboard ->
            dashboard.get("sectionOrder")?.takeIf { !it.isJsonNull }?.asString?.let { order ->
                dashboardPreferences.setSectionOrder(order.split(",").filter { it.isNotBlank() })
            }
            dashboard.getAsJsonArray("hiddenSections")?.let { arr ->
                dashboardPreferences.setHiddenSections(arr.mapNotNull { if (it.isJsonNull) null else it.asString }.toSet())
            }
            dashboard.get("progressStyle")?.takeIf { !it.isJsonNull }?.asString
                ?.let { dashboardPreferences.setProgressStyle(it) }
            dashboard.get("showProgressPercentage")?.takeIf { !it.isJsonNull }
                ?.let { dashboardPreferences.setShowProgressPercentage(it.asBoolean) }
            dashboard.getAsJsonArray("collapsedSections")?.forEach { elem ->
                if (!elem.isJsonNull) {
                    dashboardPreferences.setSectionCollapsed(elem.asString, true)
                }
            }
        }
    }

    private suspend fun importTabsConfig(config: JsonObject) {
        config.getAsJsonObject("tabs")?.let { tabs ->
            tabs.get("tabOrder")?.takeIf { !it.isJsonNull }?.asString?.let { order ->
                tabPreferences.setTabOrder(order.split(",").filter { it.isNotBlank() })
            }
            tabs.getAsJsonArray("hiddenTabs")?.let { arr ->
                tabPreferences.setHiddenTabs(arr.mapNotNull { if (it.isJsonNull) null else it.asString }.toSet())
            }
        }
    }

    private suspend fun importTaskBehaviorConfig(config: JsonObject) {
        config.getAsJsonObject("taskBehavior")?.let { tb ->
            tb.get("defaultSort")?.takeIf { !it.isJsonNull }?.asString
                ?.let { taskBehaviorPreferences.setDefaultSort(it) }
            tb.get("defaultViewMode")?.takeIf { !it.isJsonNull }?.asString
                ?.let { taskBehaviorPreferences.setDefaultViewMode(it) }
            val dueDate = tb.get("urgencyWeightDueDate")?.takeIf { !it.isJsonNull }?.asFloat ?: 0.40f
            val priority = tb.get("urgencyWeightPriority")?.takeIf { !it.isJsonNull }?.asFloat ?: 0.30f
            val age = tb.get("urgencyWeightAge")?.takeIf { !it.isJsonNull }?.asFloat ?: 0.15f
            val subtasks = tb.get("urgencyWeightSubtasks")?.takeIf { !it.isJsonNull }?.asFloat ?: 0.15f
            taskBehaviorPreferences.setUrgencyWeights(UrgencyWeights(dueDate, priority, age, subtasks))
            tb.get("reminderPresets")?.takeIf { !it.isJsonNull }?.asString?.let { presets ->
                taskBehaviorPreferences.setReminderPresets(presets.split(",").mapNotNull { it.trim().toLongOrNull() })
            }
            tb.get("firstDayOfWeek")?.takeIf { !it.isJsonNull }?.asString?.let {
                try {
                    taskBehaviorPreferences.setFirstDayOfWeek(DayOfWeek.valueOf(it))
                } catch (_: Exception) {
                }
            }
            tb.get("dayStartHour")?.takeIf { !it.isJsonNull }?.asInt
                ?.let { taskBehaviorPreferences.setDayStartHour(it) }
        }
    }

    private suspend fun importHabitListConfig(config: JsonObject) {
        config.getAsJsonObject("habitList")?.let { hl ->
            habitListPreferences.setBuiltInSortOrders(
                BuiltInSortOrders(
                    morning = hl.get("morningSortOrder")?.takeIf { !it.isJsonNull }?.asInt ?: -6,
                    bedtime = hl.get("bedtimeSortOrder")?.takeIf { !it.isJsonNull }?.asInt ?: -5,
                    medication = hl.get("medicationSortOrder")?.takeIf { !it.isJsonNull }?.asInt ?: -4,
                    school = hl.get("schoolSortOrder")?.takeIf { !it.isJsonNull }?.asInt ?: -2,
                    leisure = hl.get("leisureSortOrder")?.takeIf { !it.isJsonNull }?.asInt ?: -1,
                    housework = hl.get("houseworkSortOrder")?.takeIf { !it.isJsonNull }?.asInt ?: -3
                )
            )
            hl.get("selfCareEnabled")?.takeIf { !it.isJsonNull }?.asBoolean
                ?.let { habitListPreferences.setSelfCareEnabled(it) }
            hl.get("medicationEnabled")?.takeIf { !it.isJsonNull }?.asBoolean
                ?.let { habitListPreferences.setMedicationEnabled(it) }
            hl.get("schoolEnabled")?.takeIf { !it.isJsonNull }?.asBoolean
                ?.let { habitListPreferences.setSchoolEnabled(it) }
            hl.get("leisureEnabled")?.takeIf { !it.isJsonNull }?.asBoolean
                ?.let { habitListPreferences.setLeisureEnabled(it) }
            hl.get("houseworkEnabled")?.takeIf { !it.isJsonNull }?.asBoolean
                ?.let { habitListPreferences.setHouseworkEnabled(it) }
            hl.get("streakMaxMissedDays")?.takeIf { !it.isJsonNull }?.asInt
                ?.let { habitListPreferences.setStreakMaxMissedDays(it) }
            hl.get("todaySkipAfterCompleteDays")?.takeIf { !it.isJsonNull }?.asInt
                ?.let { habitListPreferences.setTodaySkipAfterCompleteDays(it) }
            hl.get("todaySkipBeforeScheduleDays")?.takeIf { !it.isJsonNull }?.asInt
                ?.let { habitListPreferences.setTodaySkipBeforeScheduleDays(it) }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun importLeisureConfig(ctx: ImportContext, config: JsonObject) {
        // Leisure Budget v2.0: v1.x leisure config (per-slot custom
        // music/flex activities) is retired. The new pool is imported
        // via the leisure_activities entity payload instead. Pre-v82
        // backups carrying the legacy `leisure.customMusicActivities`
        // payload are silently skipped — no error surfaced because the
        // v1.x payload was never a hard guarantee.
    }

    private suspend fun importMedicationConfig(config: JsonObject) {
        config.getAsJsonObject("medication")?.let { med ->
            med.get("reminderIntervalMinutes")?.takeIf { !it.isJsonNull }?.asInt
                ?.let { medicationPreferences.setReminderIntervalMinutes(it) }
            med.get("scheduleMode")?.takeIf { !it.isJsonNull }?.asString?.let {
                try {
                    medicationPreferences.setScheduleMode(MedicationScheduleMode.valueOf(it))
                } catch (_: Exception) {
                }
            }
            med.getAsJsonArray("specificTimes")?.let { arr ->
                medicationPreferences.setSpecificTimes(arr.mapNotNull { if (it.isJsonNull) null else it.asString }.toSet())
            }
        }
    }

    private suspend fun importDailyEssentialsConfig(config: JsonObject) {
        config.getAsJsonObject("dailyEssentials")?.let { d ->
            d.get("houseworkHabitId")?.takeIf { !it.isJsonNull }?.asLong?.let {
                dailyEssentialsPreferences.setHouseworkHabit(it)
            }
            d.get("schoolworkHabitId")?.takeIf { !it.isJsonNull }?.asLong?.let {
                dailyEssentialsPreferences.setSchoolworkHabit(it)
            }
            if (d.get("hasSeenHint")?.takeIf { !it.isJsonNull }?.asBoolean == true) {
                dailyEssentialsPreferences.markHintSeen()
            }
        }
    }

    private suspend fun importMorningCheckInConfig(config: JsonObject) {
        config.getAsJsonObject("morningCheckIn")?.let { m ->
            m.get("featureEnabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                morningCheckInPreferences.setFeatureEnabled(it)
            }
        }
    }

    private suspend fun importCalendarSyncConfig(config: JsonObject) {
        config.getAsJsonObject("calendarSync")?.let { c ->
            c.get("calendarSyncEnabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                calendarSyncPreferences.setCalendarSyncEnabled(it)
            }
            c.get("syncCalendarId")?.takeIf { !it.isJsonNull }?.asString?.let {
                calendarSyncPreferences.setSyncCalendarId(it)
            }
            c.get("syncDirection")?.takeIf { !it.isJsonNull }?.asString?.let {
                calendarSyncPreferences.setSyncDirection(it)
            }
            c.get("showCalendarEvents")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                calendarSyncPreferences.setShowCalendarEvents(it)
            }
            c.getAsJsonArray("selectedDisplayCalendarIds")?.let { arr ->
                calendarSyncPreferences.setSelectedDisplayCalendarIds(
                    arr.mapNotNull { if (it.isJsonNull) null else it.asString }.toSet()
                )
            }
            c.get("syncFrequency")?.takeIf { !it.isJsonNull }?.asString?.let {
                calendarSyncPreferences.setSyncFrequency(it)
            }
            c.get("syncCompletedTasks")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                calendarSyncPreferences.setSyncCompletedTasks(it)
            }
        }
    }

    private suspend fun importTemplatesConfig(config: JsonObject) {
        config.getAsJsonObject("templates")?.let { t ->
            t.get("templatesSeeded")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                templatePreferences.setSeeded(it)
            }
            t.get("templatesFirstSyncDone")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                templatePreferences.setFirstSyncDone(it)
            }
        }
    }

    /**
     * Restores onboarding state from a v5+ backup. Writes the original
     * `completed_at` timestamp verbatim rather than re-stamping to `now`,
     * so a restored install doesn't look like it just finished onboarding.
     */
    private suspend fun importOnboardingConfig(config: JsonObject) {
        config.getAsJsonObject("onboarding")?.let { o ->
            val completed = o.get("hasCompletedOnboarding")?.takeIf { !it.isJsonNull }?.asBoolean
                ?: return
            val completedAt = o.get("onboardingCompletedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val batteryPromptShown =
                o.get("hasShownBatteryOptimizationPrompt")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?: false
            onboardingPreferences.restoreImportedState(
                hasCompletedOnboarding = completed,
                onboardingCompletedAt = completedAt,
                hasShownBatteryOptimizationPrompt = batteryPromptShown
            )
        }
    }

    /**
     * Restores coaching state from a v5+ backup. Most keys are day-scoped
     * (today's AI-breakdown count, today's energy check-in, today's
     * welcome-back dismissal) and effectively reset when the calendar date
     * differs between export and import, but `last_app_open` carries real
     * signal for welcome-back detection.
     */
    private suspend fun importCoachingConfig(config: JsonObject) {
        config.getAsJsonObject("coaching")?.let { c ->
            c.get("lastAppOpen")?.takeIf { !it.isJsonNull }?.asLong?.let {
                coachingPreferences.setLastAppOpen(it)
            }
        }
    }

    /**
     * Restores per-screen sort mode/direction selections from a v5+ backup.
     * Uses [SortPreferences.applyRemoteSnapshot] so cloud-id keys
     * (`sort_project_cloud_<cloudId>`) produced by
     * [com.averycorp.prismtask.data.remote.SortPreferencesSyncService] push
     * paths also round-trip correctly.
     */
    private suspend fun importSortConfig(config: JsonObject) {
        config.getAsJsonObject("sort")?.let { s ->
            val keys = mutableMapOf<String, String>()
            for ((key, value) in s.entrySet()) {
                if (value.isJsonNull) continue
                val str = value.asString ?: continue
                if (str.isBlank()) continue
                keys[key] = str
            }
            if (keys.isNotEmpty()) {
                sortPreferences.applyRemoteSnapshot(keys, System.currentTimeMillis())
            }
        }
    }
}
