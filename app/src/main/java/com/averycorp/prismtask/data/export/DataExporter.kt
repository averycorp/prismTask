package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.preferences.A11yPreferences
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.ArchivePreferences
import com.averycorp.prismtask.data.preferences.CoachingPreferences
import com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.MorningCheckInPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.TabPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Options controlling what a backup includes.
 *
 * @property includeDerivedData If true, includes historical/derived collections
 *   (task completions, habit completions, habit logs, course completions, usage
 *   logs, calendar sync mappings, focus-release logs) under a `derived` block.
 *   If false, only user-authored core data is exported and streak fields are
 *   expected to be recomputed from history on import (see [DataImporter]).
 */
data class ExportOptions(
    val includeDerivedData: Boolean = true
)

/**
 * Exports all app data to JSON.
 *
 * === Export format (version 5) ===
 * Every entity is serialized using [com.google.gson.Gson.toJsonTree] directly on
 * the entity object. Whenever a new field is added to any `*Entity` class, it is
 * automatically included in the export without any changes to this file.
 *
 * v5 expands coverage to close gaps flagged by the 2026-04 audit:
 *   - new preferences: accessibility, voice, timer, notifications,
 *     neurodivergent modes, daily essentials, morning check-in, calendar sync,
 *     onboarding, templates, theme extras, habit-list extras, dashboard
 *     collapsed sections, user-preferences extras (task menu, card display,
 *     forgiveness, UI tier);
 *   - new entities: `daily_essential_slot_completions`, `usage_logs`,
 *     `calendar_sync` (the last two are derived/opt-in);
 *   - an [ExportOptions.includeDerivedData] knob that segregates
 *     history/analytics data under a top-level `derived` block so users can
 *     ship a minimal core-only backup.
 *
 * Foreign-key relationships (which reference auto-generated IDs that won't
 * match after import) are written as sibling helper fields prefixed with an
 * underscore, e.g. `_projectName`, `_tagNames`, `_habitName`, `_courseName`,
 * `_taskOldId`. Helper fields are resolved back to the correct IDs on import.
 *
 * === Backwards compatibility ===
 * Older exports (v1–v4) continue to import: the `version` field is detected
 * and the top-level layout is a strict superset. v3+ uses a generic Gson path
 * that tolerates missing or extra entity fields via [mergeEntityWithDefaults].
 *
 * === Intentional exclusions ===
 * Cloud-sync state, backend JWTs, Firebase auth, Play Billing cache, and
 * running-timer transient state are *not* exported by design. See
 * `docs/export_import_audit_2026-04-18.md` for the rationale.
 */
@Singleton
class DataExporter
@Inject
constructor(
    private val database: PrismTaskDatabase,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val habitLogDao: com.averycorp.prismtask.data.local.dao.HabitLogDao,
    private val selfCareDao: SelfCareDao,
    private val schoolworkDao: SchoolworkDao,
    private val themePreferences: ThemePreferences,
    private val archivePreferences: ArchivePreferences,
    private val dashboardPreferences: DashboardPreferences,
    private val tabPreferences: TabPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val habitListPreferences: HabitListPreferences,
    private val medicationPreferences: MedicationPreferences,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    // v5 additions — see audit doc
    private val a11yPreferences: A11yPreferences,
    private val voicePreferences: VoicePreferences,
    private val timerPreferences: TimerPreferences,
    private val notificationPreferences: NotificationPreferences,
    private val ndPreferencesDataStore: NdPreferencesDataStore,
    private val dailyEssentialsPreferences: DailyEssentialsPreferences,
    private val morningCheckInPreferences: MorningCheckInPreferences,
    private val calendarSyncPreferences: CalendarSyncPreferences,
    private val onboardingPreferences: OnboardingPreferences,
    private val templatePreferences: TemplatePreferences,
    private val coachingPreferences: CoachingPreferences,
    private val sortPreferences: SortPreferences,
    private val advancedTuningPreferences: AdvancedTuningPreferences
) {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private val compactGson = Gson()

    /**
     * Exports the full backup with default options (derived data included).
     * Preserved as a no-arg overload so existing callers and tests continue to
     * compile without passing [ExportOptions].
     */
    suspend fun exportToJson(): String = exportToJson(ExportOptions())

    suspend fun exportToJson(options: ExportOptions): String {
        val root = JsonObject()
        root.addProperty("version", EXPORT_VERSION)
        root.addProperty("schemaVersion", EXPORT_VERSION)
        root.addProperty("exportedAt", System.currentTimeMillis())
        root.addProperty(
            "exportedAtIso",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())
        )
        root.addProperty("appVersion", BuildConfig.VERSION_NAME)
        root.addProperty("deviceModel", android.os.Build.MODEL ?: "")
        root.addProperty("includeDerivedData", options.includeDerivedData)

        // === Tasks ===
        val tasks = taskDao.getAllTasksOnce()
        val projects = projectDao.getAllProjectsOnce()
        val tags = tagDao.getAllTagsOnce()
        val projectNameById = projects.associate { it.id to it.name }

        val tasksArr = JsonArray()
        for (task in tasks) {
            val obj = gson.toJsonTree(task).asJsonObject
            // Helper fields for cross-table references (IDs won't survive a round trip).
            obj.addProperty("_projectName", task.projectId?.let { projectNameById[it] })
            // Original primary key + parent pointer so subtask hierarchies survive import.
            obj.addProperty("_oldId", task.id)
            if (task.parentTaskId != null) {
                obj.addProperty("_parentOldId", task.parentTaskId)
            }
            val tagNames = tagDao
                .getTagIdsForTaskOnce(task.id)
                .mapNotNull { id -> tags.find { it.id == id }?.name }
            obj.add("_tagNames", gson.toJsonTree(tagNames))
            tasksArr.add(obj)
        }
        root.add("tasks", tasksArr)

        root.add("projects", gson.toJsonTree(projects))
        root.add("tags", gson.toJsonTree(tags))

        // === Habits (core) ===
        val habits = habitDao.getAllHabitsOnce()
        root.add("habits", gson.toJsonTree(habits))
        val habitNameById = habits.associate { it.id to it.name }

        // === Leisure Budget v2.0 (Items 7+8) ===
        // v1.x leisure_logs table retired in migration 81→82. New v2.0
        // leisure_activities + leisure_sessions are surfaced under
        // dedicated keys instead. The legacy "leisureLogs" key is
        // omitted so importers don't try to re-hydrate the slot-pick
        // shape. TODO(post-v2.0): wire DAO-level export here once the
        // v2.0 leisure_activity/_session DAO injections are tidied
        // into this module.

        // === Self-Care Logs & Steps ===
        root.add("selfCareLogs", gson.toJsonTree(selfCareDao.getAllLogsOnce()))
        root.add("selfCareSteps", gson.toJsonTree(selfCareDao.getAllStepsOnce()))

        // === Courses / Assignments ===
        val courses = schoolworkDao.getAllCoursesOnce()
        val courseNameById = courses.associate { it.id to it.name }
        // Tag courses with their original ID so study_logs can rebuild the FK on import.
        val coursesArr = JsonArray()
        for (c in courses) {
            val obj = gson.toJsonTree(c).asJsonObject
            obj.addProperty("_oldId", c.id)
            coursesArr.add(obj)
        }
        root.add("courses", coursesArr)

        val assignments = schoolworkDao.getAllAssignmentsOnce()
        val assignmentsArr = JsonArray()
        for (a in assignments) {
            val obj = gson.toJsonTree(a).asJsonObject
            obj.addProperty("_courseName", courseNameById[a.courseId])
            obj.addProperty("_oldId", a.id)
            assignmentsArr.add(obj)
        }
        root.add("assignments", assignmentsArr)

        // === Derived / historical data ===
        //
        // Grouped under a top-level `derived` object when users opt in. The
        // individual collections are also mirrored at the top level for back-
        // compat with v3/v4 imports — new importers prefer `derived.*` but
        // fall back to the top-level keys when absent.
        val derived = JsonObject()
        if (options.includeDerivedData) {
            derived.add("taskCompletions", gson.toJsonTree(taskCompletionDao.getAllCompletionsOnce()))

            val habitCompletions = habitCompletionDao.getAllCompletionsOnce()
            val completionsArr = JsonArray()
            for (c in habitCompletions) {
                val obj = gson.toJsonTree(c).asJsonObject
                obj.addProperty("_habitName", habitNameById[c.habitId])
                completionsArr.add(obj)
            }
            derived.add("habitCompletions", completionsArr)

            val habitLogs = habitLogDao.getAllLogsOnce()
            val logsArr = JsonArray()
            for (log in habitLogs) {
                val obj = gson.toJsonTree(log).asJsonObject
                obj.addProperty("_habitName", habitNameById[log.habitId])
                logsArr.add(obj)
            }
            derived.add("habitLogs", logsArr)

            val courseCompletions = schoolworkDao.getAllCompletionsOnce()
            val courseCompletionsArr = JsonArray()
            for (c in courseCompletions) {
                val obj = gson.toJsonTree(c).asJsonObject
                obj.addProperty("_courseName", courseNameById[c.courseId])
                courseCompletionsArr.add(obj)
            }
            derived.add("courseCompletions", courseCompletionsArr)

            derived.add("focusReleaseLogs", exportFocusReleaseLogs())
            derived.add("studyLogs", exportStudyLogs(courseNameById, assignments.associate { it.id to it.title }))
            derived.add("usageLogs", gson.toJsonTree(database.usageLogDao().getAllOnce()))
            derived.add("calendarSync", gson.toJsonTree(database.calendarSyncDao().getAllOnce()))
            derived.add(
                "dailyEssentialSlotCompletions",
                gson.toJsonTree(database.dailyEssentialSlotCompletionDao().getAllOnce())
            )

            // Mirror at top level for v3/v4 backwards-compatible imports.
            root.add("taskCompletions", derived.get("taskCompletions"))
            root.add("habitCompletions", derived.get("habitCompletions"))
            root.add("habitLogs", derived.get("habitLogs"))
            root.add("courseCompletions", derived.get("courseCompletions"))
            root.add("focusReleaseLogs", derived.get("focusReleaseLogs"))
            root.add("studyLogs", derived.get("studyLogs"))
        }

        // === v4: previously omitted entities (core) ===
        root.add("attachments", exportAttachments())
        root.add("boundaryRules", gson.toJsonTree(database.boundaryRuleDao().getAll()))
        root.add("checkInLogs", gson.toJsonTree(database.checkInLogDao().getAllOnce()))
        root.add("moodEnergyLogs", gson.toJsonTree(database.moodEnergyLogDao().getAll()))
        root.add("weeklyReviews", gson.toJsonTree(database.weeklyReviewDao().getAllOnce()))
        root.add("medicationRefills", gson.toJsonTree(database.medicationRefillDao().getAll()))
        // v1.4 medication top-level entity (spec: SPEC_MEDICATIONS_TOP_LEVEL.md).
        // Exports alongside medicationRefills during the Phase 2 convergence
        // window — both will coexist until the cleanup migration drops
        // medication_refills.
        root.add("medications", gson.toJsonTree(database.medicationDao().getAllOnce()))
        root.add("medicationDoses", gson.toJsonTree(database.medicationDoseDao().getAllOnce()))
        root.add("nlpShortcuts", gson.toJsonTree(database.nlpShortcutDao().getAllOnce()))
        root.add("savedFilters", gson.toJsonTree(database.savedFilterDao().getAllOnce()))
        root.add("customSounds", gson.toJsonTree(database.customSoundDao().getAllOnce()))
        // Notification profiles: only export user-created (built-in are seeded fresh on each install).
        root.add(
            "notificationProfiles",
            gson.toJsonTree(database.notificationProfileDao().getAllOnce().filter { !it.isBuiltIn })
        )
        // Templates: export the project-name helper for task templates so the FK survives import.
        root.add("taskTemplates", exportTaskTemplates(projectNameById))
        root.add("habitTemplates", gson.toJsonTree(database.habitTemplateDao().getAllOnce()))
        root.add("projectTemplates", gson.toJsonTree(database.projectTemplateDao().getAllOnce()))

        // === Configurations / Preferences ===
        val config = JsonObject()

        // Theme
        val theme = JsonObject()
        theme.addProperty("themeMode", themePreferences.getThemeMode().first())
        theme.addProperty("accentColor", themePreferences.getAccentColor().first())
        theme.addProperty("backgroundColor", themePreferences.getBackgroundColor().first())
        theme.addProperty("surfaceColor", themePreferences.getSurfaceColor().first())
        theme.addProperty("errorColor", themePreferences.getErrorColor().first())
        theme.addProperty("fontScale", themePreferences.getFontScale().first())
        theme.addProperty("priorityColorNone", themePreferences.getPriorityColorNone().first())
        theme.addProperty("priorityColorLow", themePreferences.getPriorityColorLow().first())
        theme.addProperty("priorityColorMedium", themePreferences.getPriorityColorMedium().first())
        theme.addProperty("priorityColorHigh", themePreferences.getPriorityColorHigh().first())
        theme.addProperty("priorityColorUrgent", themePreferences.getPriorityColorUrgent().first())
        theme.add("recentCustomColors", gson.toJsonTree(themePreferences.getRecentCustomColors().first()))
        theme.addProperty("prismTheme", themePreferences.getPrismTheme().first())
        config.add("theme", theme)

        // Archive
        val archive = JsonObject()
        archive.addProperty("autoArchiveDays", archivePreferences.getAutoArchiveDays().first())
        config.add("archive", archive)

        // Dashboard
        val dashboard = JsonObject()
        dashboard.addProperty("sectionOrder", dashboardPreferences.getSectionOrder().first().joinToString(","))
        dashboard.add("hiddenSections", gson.toJsonTree(dashboardPreferences.getHiddenSections().first()))
        dashboard.addProperty("progressStyle", dashboardPreferences.getProgressStyle().first())
        dashboard.add("collapsedSections", gson.toJsonTree(dashboardPreferences.getCollapsedSections().first()))
        config.add("dashboard", dashboard)

        // Tabs
        val tabs = JsonObject()
        tabs.addProperty("tabOrder", tabPreferences.getTabOrder().first().joinToString(","))
        tabs.add("hiddenTabs", gson.toJsonTree(tabPreferences.getHiddenTabs().first()))
        config.add("tabs", tabs)

        // Task Behavior
        val taskBehavior = JsonObject()
        taskBehavior.addProperty("defaultSort", taskBehaviorPreferences.getDefaultSort().first())
        taskBehavior.addProperty("defaultViewMode", taskBehaviorPreferences.getDefaultViewMode().first())
        val weights = taskBehaviorPreferences.getUrgencyWeights().first()
        taskBehavior.addProperty("urgencyWeightDueDate", weights.dueDate)
        taskBehavior.addProperty("urgencyWeightPriority", weights.priority)
        taskBehavior.addProperty("urgencyWeightAge", weights.age)
        taskBehavior.addProperty("urgencyWeightSubtasks", weights.subtasks)
        taskBehavior.addProperty("reminderPresets", taskBehaviorPreferences.getReminderPresets().first().joinToString(","))
        taskBehavior.addProperty("firstDayOfWeek", taskBehaviorPreferences.getFirstDayOfWeek().first().name)
        taskBehavior.addProperty("dayStartHour", taskBehaviorPreferences.getDayStartHour().first())
        config.add("taskBehavior", taskBehavior)

        // Habit List
        val habitList = JsonObject()
        val sortOrders = habitListPreferences.getBuiltInSortOrders().first()
        habitList.addProperty("morningSortOrder", sortOrders.morning)
        habitList.addProperty("bedtimeSortOrder", sortOrders.bedtime)
        habitList.addProperty("medicationSortOrder", sortOrders.medication)
        habitList.addProperty("schoolSortOrder", sortOrders.school)
        habitList.addProperty("leisureSortOrder", sortOrders.leisure)
        habitList.addProperty("houseworkSortOrder", sortOrders.housework)
        habitList.addProperty("selfCareEnabled", habitListPreferences.isSelfCareEnabled().first())
        habitList.addProperty("medicationEnabled", habitListPreferences.isMedicationEnabled().first())
        habitList.addProperty("schoolEnabled", habitListPreferences.isSchoolEnabled().first())
        habitList.addProperty("leisureEnabled", habitListPreferences.isLeisureEnabled().first())
        habitList.addProperty("houseworkEnabled", habitListPreferences.isHouseworkEnabled().first())
        habitList.addProperty("streakMaxMissedDays", habitListPreferences.getStreakMaxMissedDays().first())
        habitList.addProperty(
            "todaySkipAfterCompleteDays",
            habitListPreferences.getTodaySkipAfterCompleteDays().first()
        )
        habitList.addProperty(
            "todaySkipBeforeScheduleDays",
            habitListPreferences.getTodaySkipBeforeScheduleDays().first()
        )
        config.add("habitList", habitList)

        // Leisure Budget v2.0: pool/settings export moved to dedicated
        // top-level keys (leisure_activities table → see TODO above).

        // Medication
        val medication = JsonObject()
        medication.addProperty("reminderIntervalMinutes", medicationPreferences.getReminderIntervalMinutesOnce())
        medication.addProperty("scheduleMode", medicationPreferences.getScheduleModeOnce().name)
        medication.add("specificTimes", gson.toJsonTree(medicationPreferences.getSpecificTimesOnce()))
        config.add("medication", medication)

        // User Preferences (v1.3.0 customizability)
        val userPrefs = JsonObject()
        val snapshot = userPreferencesDataStore.allFlow.first()
        val appearance = JsonObject()
        appearance.addProperty("compactMode", snapshot.appearance.compactMode)
        appearance.addProperty("showTaskCardBorders", snapshot.appearance.showTaskCardBorders)
        appearance.addProperty("cardCornerRadius", snapshot.appearance.cardCornerRadius)
        userPrefs.add("appearance", appearance)
        val swipe = JsonObject()
        swipe.addProperty("right", snapshot.swipe.right.name)
        swipe.addProperty("left", snapshot.swipe.left.name)
        userPrefs.add("swipe", swipe)
        val defaults = JsonObject()
        defaults.addProperty("defaultPriority", snapshot.taskDefaults.defaultPriority)
        defaults.addProperty("defaultReminderOffset", snapshot.taskDefaults.defaultReminderOffset)
        if (snapshot.taskDefaults.defaultProjectId != null) {
            defaults.addProperty("defaultProjectId", snapshot.taskDefaults.defaultProjectId)
        }
        defaults.addProperty("startOfWeek", snapshot.taskDefaults.startOfWeek.name)
        if (snapshot.taskDefaults.defaultDuration != null) {
            defaults.addProperty("defaultDuration", snapshot.taskDefaults.defaultDuration)
        }
        defaults.addProperty("autoSetDueDate", snapshot.taskDefaults.autoSetDueDate.name)
        defaults.addProperty("smartDefaultsEnabled", snapshot.taskDefaults.smartDefaultsEnabled)
        userPrefs.add("taskDefaults", defaults)
        val quickAdd = JsonObject()
        quickAdd.addProperty("showConfirmation", snapshot.quickAdd.showConfirmation)
        quickAdd.addProperty("autoAssignProject", snapshot.quickAdd.autoAssignProject)
        userPrefs.add("quickAdd", quickAdd)
        // Work-Life Balance (v1.4.0 V1)
        val wlb = JsonObject()
        wlb.addProperty("workTarget", snapshot.workLifeBalance.workTarget)
        wlb.addProperty("personalTarget", snapshot.workLifeBalance.personalTarget)
        wlb.addProperty("selfCareTarget", snapshot.workLifeBalance.selfCareTarget)
        wlb.addProperty("healthTarget", snapshot.workLifeBalance.healthTarget)
        wlb.addProperty("showBalanceBar", snapshot.workLifeBalance.showBalanceBar)
        wlb.addProperty("overloadThresholdPct", snapshot.workLifeBalance.overloadThresholdPct)
        userPrefs.add("workLifeBalance", wlb)

        // --- v5: previously-omitted user prefs ---
        val forgiveness = userPreferencesDataStore.forgivenessFlow.first()
        val forgivenessJson = JsonObject()
        forgivenessJson.addProperty("enabled", forgiveness.enabled)
        forgivenessJson.addProperty("gracePeriodDays", forgiveness.gracePeriodDays)
        forgivenessJson.addProperty("allowedMisses", forgiveness.allowedMisses)
        userPrefs.add("forgiveness", forgivenessJson)

        userPrefs.add(
            "taskMenuActions",
            compactGson.toJsonTree(userPreferencesDataStore.taskMenuActionsFlow.first())
        )
        userPrefs.add(
            "taskCardDisplay",
            compactGson.toJsonTree(userPreferencesDataStore.taskCardDisplayFlow.first())
        )

        config.add("userPreferences", userPrefs)

        // --- v5: net-new preference groups ---
        config.add("a11y", exportA11yConfig())
        config.add("voice", exportVoiceConfig())
        config.add("timer", exportTimerConfig())
        config.add("notification", exportNotificationConfig())
        config.add("nd", exportNdConfig())
        config.add("dailyEssentials", exportDailyEssentialsConfig())
        config.add("morningCheckIn", exportMorningCheckInConfig())
        config.add("calendarSync", exportCalendarSyncConfig())
        config.add("onboarding", exportOnboardingConfig())
        config.add("templates", exportTemplateConfig())
        config.add("coaching", exportCoachingConfig())
        config.add("sort", exportSortConfig())
        config.add("advancedTuning", exportAdvancedTuningConfig())

        root.add("config", config)

        if (options.includeDerivedData) {
            root.add("derived", derived)
        }

        return gson.toJson(root)
    }

    // --- v5 preference export helpers ---

    private suspend fun exportA11yConfig(): JsonObject = JsonObject().apply {
        addProperty("reduceMotion", a11yPreferences.getReduceMotion().first())
        addProperty("highContrast", a11yPreferences.getHighContrast().first())
        addProperty("largeTouchTargets", a11yPreferences.getLargeTouchTargets().first())
    }

    private suspend fun exportVoiceConfig(): JsonObject = JsonObject().apply {
        addProperty("voiceInputEnabled", voicePreferences.getVoiceInputEnabled().first())
        addProperty("voiceFeedbackEnabled", voicePreferences.getVoiceFeedbackEnabled().first())
        addProperty("continuousModeEnabled", voicePreferences.getContinuousModeEnabled().first())
    }

    private suspend fun exportTimerConfig(): JsonObject = JsonObject().apply {
        addProperty("workDurationSeconds", timerPreferences.getWorkDurationSeconds().first())
        addProperty("breakDurationSeconds", timerPreferences.getBreakDurationSeconds().first())
        addProperty("longBreakDurationSeconds", timerPreferences.getLongBreakDurationSeconds().first())
        addProperty("customDurationSeconds", timerPreferences.getCustomDurationSeconds().first())
        addProperty("pomodoroEnabled", timerPreferences.getPomodoroEnabled().first())
        addProperty("sessionsUntilLongBreak", timerPreferences.getSessionsUntilLongBreak().first())
        addProperty("autoStartBreaks", timerPreferences.getAutoStartBreaks().first())
        addProperty("autoStartWork", timerPreferences.getAutoStartWork().first())
        addProperty("pomodoroAvailableMinutes", timerPreferences.getPomodoroAvailableMinutes().first())
        addProperty("pomodoroFocusPreference", timerPreferences.getPomodoroFocusPreference().first())
        addProperty("buzzUntilDismissed", timerPreferences.getBuzzUntilDismissed().first())
        addProperty("overrideVolume", timerPreferences.getOverrideVolume().first())
        addProperty("alarmVolumePercent", timerPreferences.getAlarmVolumePercent().first())
    }

    private suspend fun exportNotificationConfig(): JsonObject = JsonObject().apply {
        val p = notificationPreferences
        addProperty("taskRemindersEnabled", p.taskRemindersEnabled.first())
        addProperty("timerAlertsEnabled", p.timerAlertsEnabled.first())
        addProperty("medicationRemindersEnabled", p.medicationRemindersEnabled.first())
        addProperty("dailyBriefingEnabled", p.dailyBriefingEnabled.first())
        addProperty("eveningSummaryEnabled", p.eveningSummaryEnabled.first())
        addProperty("weeklySummaryEnabled", p.weeklySummaryEnabled.first())
        addProperty("weeklyTaskSummaryEnabled", p.weeklyTaskSummaryEnabled.first())
        addProperty("overloadAlertsEnabled", p.overloadAlertsEnabled.first())
        addProperty("reengagementEnabled", p.reengagementEnabled.first())
        addProperty("fullScreenNotificationsEnabled", p.fullScreenNotificationsEnabled.first())
        addProperty("overrideVolumeEnabled", p.overrideVolumeEnabled.first())
        addProperty("repeatingVibrationEnabled", p.repeatingVibrationEnabled.first())
        addProperty("importance", p.importance.first())
        addProperty("defaultReminderOffset", p.defaultReminderOffset.first())
        addProperty("activeProfileId", p.activeProfileId.first())
        add("categoryProfileOverrides", gson.toJsonTree(p.categoryProfileOverrides.first()))
        addProperty("streakAlertsEnabled", p.streakAlertsEnabled.first())
        addProperty("streakAtRiskLeadHours", p.streakAtRiskLeadHours.first())
        addProperty("briefingMorningHour", p.briefingMorningHour.first())
        addProperty("briefingMiddayEnabled", p.briefingMiddayEnabled.first())
        addProperty("briefingEveningHour", p.briefingEveningHour.first())
        addProperty("briefingTone", p.briefingTone.first())
        add("briefingSections", gson.toJsonTree(p.briefingSections.first()))
        addProperty("briefingReadAloud", p.briefingReadAloudEnabled.first())
        addProperty("collabDigestMode", p.collabDigestMode.first())
        addProperty("collabAssignedEnabled", p.collabAssignedEnabled.first())
        addProperty("collabMentionedEnabled", p.collabMentionedEnabled.first())
        addProperty("collabStatusEnabled", p.collabStatusEnabled.first())
        addProperty("collabCommentEnabled", p.collabCommentEnabled.first())
        addProperty("collabDueSoonEnabled", p.collabDueSoonEnabled.first())
        addProperty("watchSyncMode", p.watchSyncMode.first())
        addProperty("watchVolumePercent", p.watchVolumePercent.first())
        addProperty("watchHapticIntensity", p.watchHapticIntensity.first())
        addProperty("badgeMode", p.badgeMode.first())
        addProperty("toastPosition", p.toastPosition.first())
        addProperty("highContrastNotifications", p.highContrastNotificationsEnabled.first())
        addProperty("habitNagSuppressionDays", p.habitNagSuppressionDays.first())
        add("snoozeDurationsMinutes", gson.toJsonTree(p.snoozeDurationsMinutes.first()))
    }

    private suspend fun exportNdConfig(): JsonObject {
        // The NdPreferences data class is the single source of truth; serialize it
        // directly so new fields flow through automatically.
        val nd = ndPreferencesDataStore.ndPreferencesFlow.first()
        return gson.toJsonTree(nd).asJsonObject
    }

    private suspend fun exportDailyEssentialsConfig(): JsonObject = JsonObject().apply {
        dailyEssentialsPreferences.houseworkHabitId.first()?.let { addProperty("houseworkHabitId", it) }
        dailyEssentialsPreferences.schoolworkHabitId.first()?.let { addProperty("schoolworkHabitId", it) }
        addProperty("hasSeenHint", dailyEssentialsPreferences.hasSeenHint.first())
    }

    private suspend fun exportMorningCheckInConfig(): JsonObject = JsonObject().apply {
        addProperty("featureEnabled", morningCheckInPreferences.featureEnabled().first())
    }

    private suspend fun exportCalendarSyncConfig(): JsonObject = JsonObject().apply {
        addProperty("calendarSyncEnabled", calendarSyncPreferences.isCalendarSyncEnabled().first())
        addProperty("syncCalendarId", calendarSyncPreferences.getSyncCalendarId().first())
        addProperty("syncDirection", calendarSyncPreferences.getSyncDirection().first())
        addProperty("showCalendarEvents", calendarSyncPreferences.getShowCalendarEvents().first())
        add(
            "selectedDisplayCalendarIds",
            gson.toJsonTree(calendarSyncPreferences.getSelectedDisplayCalendarIds().first())
        )
        addProperty("syncFrequency", calendarSyncPreferences.getSyncFrequency().first())
        addProperty("syncCompletedTasks", calendarSyncPreferences.getSyncCompletedTasks().first())
        // lastSyncTimestamp is intentionally excluded — derived watermark that
        // would suppress real deltas if restored.
    }

    private suspend fun exportOnboardingConfig(): JsonObject = JsonObject().apply {
        addProperty("hasCompletedOnboarding", onboardingPreferences.hasCompletedOnboarding().first())
        addProperty("onboardingCompletedAt", onboardingPreferences.getOnboardingCompletedAt().first())
        addProperty(
            "hasShownBatteryOptimizationPrompt",
            onboardingPreferences.hasShownBatteryOptimizationPrompt().first()
        )
    }

    private suspend fun exportTemplateConfig(): JsonObject = JsonObject().apply {
        addProperty("templatesSeeded", templatePreferences.isSeeded())
        addProperty("templatesFirstSyncDone", templatePreferences.isFirstSyncDone())
    }

    /**
     * Coaching state. Most keys are day-scoped transient state that resets
     * when the calendar date differs between export and import — only
     * `lastAppOpen` is meaningful across a backup/restore cycle. We still
     * include it for completeness since the user-facing guarantee is that
     * *every* preference key is synced and backed up.
     */
    private suspend fun exportCoachingConfig(): JsonObject = JsonObject().apply {
        addProperty("lastAppOpen", coachingPreferences.getLastAppOpen())
    }

    /**
     * Per-screen sort mode/direction selections. Mirrors the shape that
     * [com.averycorp.prismtask.data.remote.SortPreferencesSyncService] pushes
     * to Firestore, minus the internal sync-metadata keys. Per-project
     * entries (`sort_project_<localId>`) reference auto-generated project
     * row IDs and therefore may not survive a fresh-install restore; global
     * entries (e.g. `sort_today`, `sort_all_tasks`) round-trip cleanly.
     */
    private suspend fun exportSortConfig(): JsonObject = JsonObject().apply {
        for ((key, value) in sortPreferences.snapshot()) {
            if (value is String && value.isNotBlank()) {
                addProperty(key, value)
            }
        }
    }

    /**
     * Power-user tuning knobs from [AdvancedTuningPreferences]. Each key
     * corresponds to a typed config data class; the importer reverses the
     * mapping by reading sub-keys and calling the matching `set*` method.
     * Whole-object Gson dump (mirrors [exportNdConfig]) so new fields on any
     * config data class flow through automatically.
     */
    private suspend fun exportAdvancedTuningConfig(): JsonObject = JsonObject().apply {
        val p = advancedTuningPreferences
        add("urgencyBands", gson.toJsonTree(p.getUrgencyBands().first()))
        add("urgencyWindows", gson.toJsonTree(p.getUrgencyWindows().first()))
        add("burnoutWeights", gson.toJsonTree(p.getBurnoutWeights().first()))
        add("productivityWeights", gson.toJsonTree(p.getProductivityWeights().first()))
        add("moodCorrelation", gson.toJsonTree(p.getMoodCorrelationConfig().first()))
        add("refillUrgency", gson.toJsonTree(p.getRefillUrgencyConfig().first()))
        add("energyPomodoro", gson.toJsonTree(p.getEnergyPomodoroConfig().first()))
        add("goodEnoughTimer", gson.toJsonTree(p.getGoodEnoughTimerConfig().first()))
        add("suggestion", gson.toJsonTree(p.getSuggestionConfig().first()))
        add("extractor", gson.toJsonTree(p.getExtractorConfig().first()))
        add("smartDefaults", gson.toJsonTree(p.getSmartDefaultsConfig().first()))
        add("morningCheckInCutoff", gson.toJsonTree(p.getMorningCheckInPromptCutoff().first()))
        add("lifeCategoryKeywords", gson.toJsonTree(p.getLifeCategoryCustomKeywords().first()))
        add("taskModeKeywords", gson.toJsonTree(p.getTaskModeCustomKeywords().first()))
        add("cognitiveLoadKeywords", gson.toJsonTree(p.getCognitiveLoadCustomKeywords().first()))
        add("weeklySummary", gson.toJsonTree(p.getWeeklySummarySchedule().first()))
        add("reengagement", gson.toJsonTree(p.getReengagementConfig().first()))
        add("overloadCheck", gson.toJsonTree(p.getOverloadCheckSchedule().first()))
        add("batchUndo", gson.toJsonTree(p.getBatchUndoConfig().first()))
        add("habitReminderFallback", gson.toJsonTree(p.getHabitReminderFallback().first()))
        add("apiNetwork", gson.toJsonTree(p.getApiNetworkConfig().first()))
        add("widgetRefresh", gson.toJsonTree(p.getWidgetRefreshConfig().first()))
        add("productivityWidget", gson.toJsonTree(p.getProductivityWidgetThresholds().first()))
        add("editorFieldRows", gson.toJsonTree(p.getEditorFieldRows().first()))
        add("quickAddRows", gson.toJsonTree(p.getQuickAddRows().first()))
        add("searchPreview", gson.toJsonTree(p.getSearchPreview().first()))
        add("selfCareTierDefaults", gson.toJsonTree(p.getSelfCareTierDefaults().first()))
        add("habitBorderBrightness", gson.toJsonTree(p.getHabitBorderBrightness().first()))
    }

    private suspend fun exportAttachments(): JsonArray {
        val arr = JsonArray()
        database.attachmentDao().getAllOnce().forEach { att ->
            val obj = gson.toJsonTree(att).asJsonObject
            obj.addProperty("_taskOldId", att.taskId)
            arr.add(obj)
        }
        return arr
    }

    private suspend fun exportFocusReleaseLogs(): JsonArray {
        val arr = JsonArray()
        database.focusReleaseLogDao().getAllOnce().forEach { log ->
            val obj = gson.toJsonTree(log).asJsonObject
            if (log.taskId != null) {
                obj.addProperty("_taskOldId", log.taskId)
            }
            arr.add(obj)
        }
        return arr
    }

    private suspend fun exportStudyLogs(
        courseNameById: Map<Long, String>,
        assignmentTitleById: Map<Long, String>
    ): JsonArray {
        val arr = JsonArray()
        database.schoolworkDao().getAllStudyLogsOnce().forEach { log ->
            val obj = gson.toJsonTree(log).asJsonObject
            if (log.coursePick != null) {
                obj.addProperty("_courseName", courseNameById[log.coursePick])
                obj.addProperty("_courseOldId", log.coursePick)
            }
            if (log.assignmentPick != null) {
                obj.addProperty("_assignmentTitle", assignmentTitleById[log.assignmentPick])
                obj.addProperty("_assignmentOldId", log.assignmentPick)
            }
            arr.add(obj)
        }
        return arr
    }

    private suspend fun exportTaskTemplates(projectNameById: Map<Long, String>): JsonArray {
        val arr = JsonArray()
        database.taskTemplateDao().getAllTemplatesOnce().forEach { tpl ->
            val obj = gson.toJsonTree(tpl).asJsonObject
            if (tpl.templateProjectId != null) {
                obj.addProperty("_projectName", projectNameById[tpl.templateProjectId])
            }
            arr.add(obj)
        }
        return arr
    }

    suspend fun exportToCsv(): String {
        val tasks = taskDao.getAllTasksOnce()
        val projects = projectDao.getAllProjectsOnce()
        val tags = tagDao.getAllTagsOnce()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        val sb = StringBuilder()
        // Header note: CSV is task-only and lossy; JSON is the supported full backup.
        sb.appendLine("# PrismTask CSV export — task list only. Use JSON for a full backup.")
        sb.appendLine("Title,Description,Due Date,Due Time,Priority,Project,Tags,Status,Created,Completed")

        for (task in tasks) {
            val tagNames = tagDao
                .getTagIdsForTaskOnce(task.id)
                .mapNotNull { id -> tags.find { it.id == id }?.name }
                .joinToString("; ")
            val projectName = task.projectId?.let { pid -> projects.find { it.id == pid }?.name } ?: ""
            val status = if (task.isCompleted) "Completed" else "Incomplete"

            sb.appendLine(
                listOf(
                    csvEscape(task.title),
                    csvEscape(task.description ?: ""),
                    task.dueDate?.let { dateFormat.format(Date(it)) } ?: "",
                    task.dueTime?.let { dateFormat.format(Date(it)) } ?: "",
                    task.priority.toString(),
                    csvEscape(projectName),
                    csvEscape(tagNames),
                    status,
                    dateFormat.format(Date(task.createdAt)),
                    task.completedAt?.let { dateFormat.format(Date(it)) } ?: ""
                ).joinToString(",")
            )
        }

        return sb.toString()
    }

    private fun csvEscape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    companion object {
        /**
         * Current export format version.
         *
         * Bump this only for breaking structural changes. Purely additive changes
         * (new entity fields, new entity collections) do NOT require a version bump
         * because [DataImporter] tolerates missing fields via
         * [mergeEntityWithDefaults].
         *
         * - v5 (2026-04-18): audit pass — adds the `includeDerivedData` flag,
         *   `derived` split, `schemaVersion`/`exportedAtIso`/`deviceModel`
         *   metadata, and fills gaps in preference and entity coverage.
         */
        const val EXPORT_VERSION = 5
    }
}
