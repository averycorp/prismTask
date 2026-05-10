package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.LeisureDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.CourseCompletionEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.LeisureLogEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.preferences.A11yPreferences
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.ApiNetworkConfig
import com.averycorp.prismtask.data.preferences.ArchivePreferences
import com.averycorp.prismtask.data.preferences.BatchUndoConfig
import com.averycorp.prismtask.data.preferences.BuiltInSortOrders
import com.averycorp.prismtask.data.preferences.BurnoutWeights
import com.averycorp.prismtask.data.preferences.CoachingPreferences
import com.averycorp.prismtask.data.preferences.CognitiveLoadCustomKeywords
import com.averycorp.prismtask.data.preferences.CustomLeisureActivity
import com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.data.preferences.EditorFieldRows
import com.averycorp.prismtask.data.preferences.EnergyPomodoroConfig
import com.averycorp.prismtask.data.preferences.ExtractorConfig
import com.averycorp.prismtask.data.preferences.ForgivenessPrefs
import com.averycorp.prismtask.data.preferences.GoodEnoughTimerConfig
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.HabitReminderFallback
import com.averycorp.prismtask.data.preferences.LeisurePreferences
import com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.MedicationScheduleMode
import com.averycorp.prismtask.data.preferences.MoodCorrelationConfig
import com.averycorp.prismtask.data.preferences.MorningCheckInPreferences
import com.averycorp.prismtask.data.preferences.MorningCheckInPromptCutoff
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.OverloadCheckSchedule
import com.averycorp.prismtask.data.preferences.ProductivityWeights
import com.averycorp.prismtask.data.preferences.ProductivityWidgetThresholds
import com.averycorp.prismtask.data.preferences.QuickAddRows
import com.averycorp.prismtask.data.preferences.ReengagementConfig
import com.averycorp.prismtask.data.preferences.RefillUrgencyConfig
import com.averycorp.prismtask.data.preferences.SearchPreview
import com.averycorp.prismtask.data.preferences.SelfCareTierDefaults
import com.averycorp.prismtask.data.preferences.SmartDefaultsConfig
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.SuggestionConfig
import com.averycorp.prismtask.data.preferences.TabPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TaskModeCustomKeywords
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.data.preferences.UrgencyBands
import com.averycorp.prismtask.data.preferences.UrgencyWeights
import com.averycorp.prismtask.data.preferences.UrgencyWindows
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.averycorp.prismtask.data.preferences.WeeklySummarySchedule
import com.averycorp.prismtask.data.preferences.WidgetRefreshConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class ImportMode { MERGE, REPLACE }

/**
 * Top-level sections that can be selectively wiped when importing in
 * [ImportMode.REPLACE]. When no explicit scope is passed, all sections are
 * wiped (matches pre-v5 behavior).
 *
 *  - [TASKS_PROJECTS]: tasks, projects, tags, attachments, study logs, usage logs
 *  - [HABITS_AND_HISTORY]: habits, habit completions, habit logs
 *  - [TASK_COMPLETIONS]: task completion history
 *  - [SCHOOLWORK]: courses, assignments, course completions
 *  - [CONFIG]: all preferences (theme, notifications, ND modes, etc.)
 */
enum class ReplaceSection {
    TASKS_PROJECTS,
    HABITS_AND_HISTORY,
    TASK_COMPLETIONS,
    SCHOOLWORK,
    CONFIG;

    companion object {
        val ALL: Set<ReplaceSection> = values().toSet()
    }
}

/**
 * Options controlling import behavior.
 *
 * @property restoreDerivedData When false, derived collections (completions,
 *   logs, usage, calendar sync mappings) in the export are silently skipped
 *   even if present. Streak values on habits are recomputed from history on
 *   next read rather than trusted from the import.
 * @property replaceScope In [ImportMode.REPLACE], limits the wipe to the
 *   listed sections so a user can replace their task list while preserving
 *   habit history. Ignored in [ImportMode.MERGE].
 */
data class ImportOptions(
    val restoreDerivedData: Boolean = true,
    val replaceScope: Set<ReplaceSection> = ReplaceSection.ALL
)

data class ImportResult(
    val tasksImported: Int = 0,
    val projectsImported: Int = 0,
    val tagsImported: Int = 0,
    val taskCompletionsImported: Int = 0,
    val habitsImported: Int = 0,
    val habitCompletionsImported: Int = 0,
    val medicationsImported: Int = 0,
    val medicationDosesImported: Int = 0,
    val habitLogsImported: Int = 0,
    val leisureLogsImported: Int = 0,
    val selfCareLogsImported: Int = 0,
    val selfCareStepsImported: Int = 0,
    val coursesImported: Int = 0,
    val assignmentsImported: Int = 0,
    val courseCompletionsImported: Int = 0,
    val configImported: Boolean = false,
    val duplicatesSkipped: Int = 0,
    val lwwOverwrites: Int = 0,
    val orphansSkipped: Int = 0,
    val derivedDataSkipped: Boolean = false,
    val schemaVersion: Int = 0,
    val errors: List<String> = emptyList()
)

/**
 * Imports app data from a JSON file produced by [DataExporter].
 *
 * === Version handling & backwards compatibility ===
 *  - `version >= 3`: generic Gson path. Each entity JSON object is overlaid onto a
 *    freshly-constructed default instance via [mergeEntityWithDefaults]. This means any
 *    fields added to an entity *after* the file was exported automatically get their
 *    Kotlin constructor default values on import — so old backups keep working as the
 *    schema evolves.
 *  - `version < 3` (or missing): legacy flat-field path preserved verbatim so users
 *    can still restore backups produced by earlier app versions.
 *
 * The v3 format does not require any changes to this file when a new field is added to
 * an existing entity. Only adding a brand-new entity/collection requires a new branch
 * in the import loop.
 */
@Singleton
class DataImporter
@Inject
constructor(
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val taskCompletionDao: com.averycorp.prismtask.data.local.dao.TaskCompletionDao,
    private val habitLogDao: com.averycorp.prismtask.data.local.dao.HabitLogDao,
    private val leisureDao: LeisureDao,
    private val selfCareDao: SelfCareDao,
    private val schoolworkDao: SchoolworkDao,
    private val medicationDao: com.averycorp.prismtask.data.local.dao.MedicationDao,
    private val medicationDoseDao: com.averycorp.prismtask.data.local.dao.MedicationDoseDao,
    /**
     * D8 Item 8 — on-restore backfill of `medication_tier_states`. Mirrors
     * `MIGRATION_59_60` semantics so a v3 backup restored on a fresh
     * device sees the normalized table populated immediately rather than
     * waiting for the next live `setTierForTime` call.
     */
    private val medicationSlotDao: com.averycorp.prismtask.data.local.dao.MedicationSlotDao,
    private val medicationTierStateDao: com.averycorp.prismtask.data.local.dao.MedicationTierStateDao,
    private val transactionRunner: com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner,
    private val themePreferences: ThemePreferences,
    private val archivePreferences: ArchivePreferences,
    private val dashboardPreferences: DashboardPreferences,
    private val tabPreferences: TabPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val habitListPreferences: HabitListPreferences,
    private val leisurePreferences: LeisurePreferences,
    private val medicationPreferences: MedicationPreferences,
    private val userPreferencesDataStore: com.averycorp.prismtask.data.preferences.UserPreferencesDataStore,
    // v5 additions — see audit doc
    private val a11yPreferences: A11yPreferences,
    private val voicePreferences: VoicePreferences,
    private val timerPreferences: TimerPreferences,
    private val notificationPreferences: NotificationPreferences,
    private val ndPreferencesDataStore: NdPreferencesDataStore,
    private val dailyEssentialsPreferences: DailyEssentialsPreferences,
    private val morningCheckInPreferences: MorningCheckInPreferences,
    private val calendarSyncPreferences: CalendarSyncPreferences,
    private val templatePreferences: TemplatePreferences,
    private val onboardingPreferences: OnboardingPreferences,
    private val coachingPreferences: CoachingPreferences,
    private val sortPreferences: SortPreferences,
    private val advancedTuningPreferences: AdvancedTuningPreferences
) {
    private val gson = Gson()

    /** Mutable accumulator for import counts, shared across sub-methods. */
    private class ImportContext(val options: ImportOptions) {
        val errors = mutableListOf<String>()
        var tasksImported = 0
        var projectsImported = 0
        var tagsImported = 0
        var taskCompletionsImported = 0
        var habitsImported = 0
        var habitCompletionsImported = 0
        var habitLogsImported = 0
        var medicationsImported = 0
        var medicationDosesImported = 0
        var leisureLogsImported = 0
        var selfCareLogsImported = 0
        var selfCareStepsImported = 0
        var coursesImported = 0
        var assignmentsImported = 0
        var courseCompletionsImported = 0
        var configImported = false
        var duplicatesSkipped = 0
        var lwwOverwrites = 0
        var orphansSkipped = 0
        var schemaVersion = 0
        var derivedDataSkipped = false
        // D8 Item 8 — count of `medication_tier_states` rows
        // inserted/updated by the post-restore backfill. Surfaced via
        // logging only; not part of the public ImportResult contract.
        var medicationTierStatesBackfilled = 0

        fun toResult() = ImportResult(
            tasksImported = tasksImported,
            projectsImported = projectsImported,
            tagsImported = tagsImported,
            taskCompletionsImported = taskCompletionsImported,
            habitsImported = habitsImported,
            habitCompletionsImported = habitCompletionsImported,
            habitLogsImported = habitLogsImported,
            medicationsImported = medicationsImported,
            medicationDosesImported = medicationDosesImported,
            leisureLogsImported = leisureLogsImported,
            selfCareLogsImported = selfCareLogsImported,
            selfCareStepsImported = selfCareStepsImported,
            coursesImported = coursesImported,
            assignmentsImported = assignmentsImported,
            courseCompletionsImported = courseCompletionsImported,
            configImported = configImported,
            duplicatesSkipped = duplicatesSkipped,
            lwwOverwrites = lwwOverwrites,
            orphansSkipped = orphansSkipped,
            derivedDataSkipped = derivedDataSkipped,
            schemaVersion = schemaVersion,
            errors = errors
        )
    }

    /**
     * Back-compat entry point. Equivalent to
     * `importFromJson(jsonString, mode, ImportOptions())`.
     */
    suspend fun importFromJson(jsonString: String, mode: ImportMode): ImportResult =
        importFromJson(jsonString, mode, ImportOptions())

    suspend fun importFromJson(
        jsonString: String,
        mode: ImportMode,
        options: ImportOptions
    ): ImportResult {
        val ctx = ImportContext(options)

        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            ctx.schemaVersion =
                root.get("schemaVersion")?.takeIf { !it.isJsonNull }?.asInt
                    ?: root.get("version")?.takeIf { !it.isJsonNull }?.asInt
                    ?: 0

            // DB mutations are wrapped in a single transaction so a mid-import
            // failure rolls back cleanly — no orphan rows, no half-imported
            // tasks missing their tag/subtask links. Config (DataStore) writes
            // happen outside the transaction because DataStore has its own
            // atomicity model.
            transactionRunner.withTransaction {
                if (mode == ImportMode.REPLACE) {
                    val scope = options.replaceScope
                    if (ReplaceSection.TASKS_PROJECTS in scope) {
                        taskDao.getAllTasksOnce().forEach { taskDao.deleteById(it.id) }
                        projectDao.getAllProjectsOnce().forEach { projectDao.delete(it) }
                        tagDao.getAllTagsOnce().forEach { tagDao.delete(it) }
                    }
                    if (ReplaceSection.HABITS_AND_HISTORY in scope) {
                        habitCompletionDao.deleteAll()
                        habitDao.getAllHabitsOnce().forEach { habitDao.delete(it) }
                    }
                    if (ReplaceSection.TASK_COMPLETIONS in scope) {
                        taskCompletionDao.getAllCompletionsOnce().forEach {
                            taskCompletionDao.deleteByTaskId(it.taskId ?: -1)
                        }
                    }
                }

                val existingProjects = projectDao.getAllProjectsOnce()
                val existingTags = tagDao.getAllTagsOnce()
                val existingTasks = taskDao.getAllTasksOnce()

                val projectNameToId = importProjects(ctx, root, mode, existingProjects)
                val tagNameToId = importTags(ctx, root, mode, existingTags)
                importTasks(ctx, root, mode, existingTasks, projectNameToId, tagNameToId)
                if (options.restoreDerivedData) {
                    importTaskCompletions(ctx, root, mode)
                } else {
                    ctx.derivedDataSkipped = true
                }

                val habitNameToId = importHabits(ctx, root, mode)
                if (options.restoreDerivedData) {
                    importHabitCompletions(ctx, root, habitNameToId)
                    importHabitLogs(ctx, root, habitNameToId)
                } else {
                    ctx.derivedDataSkipped = true
                }

                importLeisureLogs(ctx, root)
                importSelfCareLogs(ctx, root)
                importSelfCareSteps(ctx, root)

                // v1.4 medications — imported after self-care so the
                // dual-write shim's later migration runs see the real
                // names in place. medication_doses MUST come after
                // medications because it FK's to medication_id; we use
                // the export-side id as the join key via medIdRemap.
                val medIdRemap = importMedications(ctx, root, mode)
                if (options.restoreDerivedData) {
                    importMedicationDoses(ctx, root, medIdRemap)
                }

                val courseNameToId = importCourses(ctx, root, mode)
                importAssignments(ctx, root, courseNameToId)
                if (options.restoreDerivedData) {
                    importCourseCompletions(ctx, root, courseNameToId)
                }
            }

            importConfig(ctx, root)
        } catch (e: Exception) {
            ctx.errors.add("Import failed: ${e.message}")
        }

        return ctx.toResult()
    }

    private suspend fun importProjects(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode,
        existingProjects: List<ProjectEntity>
    ): MutableMap<String, Long> {
        val projectNameToId = mutableMapOf<String, Long>()
        val existingByNameLower = existingProjects.associateBy { it.name.lowercase() }
        existingProjects.forEach { projectNameToId[it.name.lowercase()] = it.id }

        root.getAsJsonArray("projects")?.forEach { elem ->
            val obj = elem.asJsonObject
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val existing = existingByNameLower[name.lowercase()]
            if (mode == ImportMode.MERGE && existing != null) {
                // Last-write-wins: if the incoming row is newer than what we
                // already have, overlay the imported fields onto the existing
                // row (keeping its DB id). Otherwise count as a duplicate.
                val incomingUpdatedAt = obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                if (incomingUpdatedAt > existing.updatedAt) {
                    val merged = mergeEntityWithDefaults(ProjectEntity(name = name), obj)
                    projectDao.update(merged.copy(id = existing.id))
                    ctx.lwwOverwrites++
                } else {
                    ctx.duplicatesSkipped++
                }
            } else {
                val default = ProjectEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val project = merged.copy(id = 0, updatedAt = System.currentTimeMillis())
                val id = projectDao.insert(project)
                projectNameToId[name.lowercase()] = id
                ctx.projectsImported++
            }
        }
        return projectNameToId
    }

    private suspend fun importTags(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode,
        existingTags: List<TagEntity>
    ): MutableMap<String, Long> {
        val tagNameToId = mutableMapOf<String, Long>()
        existingTags.forEach { tagNameToId[it.name.lowercase()] = it.id }

        root.getAsJsonArray("tags")?.forEach { elem ->
            val obj = elem.asJsonObject
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            if (mode == ImportMode.MERGE && name.lowercase() in tagNameToId) {
                ctx.duplicatesSkipped++
            } else {
                val default = TagEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val tag = merged.copy(id = 0)
                val id = tagDao.insert(tag)
                tagNameToId[name.lowercase()] = id
                ctx.tagsImported++
            }
        }
        return tagNameToId
    }

    private suspend fun importTasks(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode,
        existingTasks: List<TaskEntity>,
        projectNameToId: Map<String, Long>,
        tagNameToId: Map<String, Long>
    ) {
        root.getAsJsonArray("tasks")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                if (title.isBlank()) return@forEach

                if (mode == ImportMode.MERGE) {
                    val createdAt = obj.get("createdAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0
                    val isDup = existingTasks.any { it.title == title && abs(it.createdAt - createdAt) < 60000 }
                    if (isDup) {
                        ctx.duplicatesSkipped++
                        return@forEach
                    }
                }

                // Foreign key resolution: v3 uses "_projectName" helper, v2 uses "project".
                val projectName = (obj.get("_projectName") ?: obj.get("project"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                val projectId = projectName?.let { projectNameToId[it.lowercase()] }

                val default = TaskEntity(title = title)
                val merged = mergeEntityWithDefaults(default, obj)
                val task = merged.copy(
                    id = 0,
                    projectId = projectId,
                    parentTaskId = null,
                    sourceHabitId = null,
                    updatedAt = System.currentTimeMillis()
                )
                val taskId = taskDao.insert(task)

                // Tags: v3 uses "_tagNames", v2 uses "tags".
                val tagArr = obj.getAsJsonArray("_tagNames") ?: obj.getAsJsonArray("tags")
                tagArr?.forEach { tagElem ->
                    if (tagElem.isJsonNull) return@forEach
                    val tagName = tagElem.asString
                    val tagId = tagNameToId[tagName.lowercase()]
                    if (tagId != null) {
                        tagDao.addTagToTask(TaskTagCrossRef(taskId = taskId, tagId = tagId))
                    }
                }

                ctx.tasksImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import task: ${e.message}")
            }
        }
    }

    private suspend fun importHabits(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode
    ): MutableMap<String, Long> {
        val habitNameToId = mutableMapOf<String, Long>()
        val existingByNameLower = mutableMapOf<String, HabitEntity>()
        if (mode == ImportMode.MERGE) {
            habitDao.getAllHabitsOnce().forEach {
                habitNameToId[it.name.lowercase()] = it.id
                existingByNameLower[it.name.lowercase()] = it
            }
        }

        root.getAsJsonArray("habits")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val existing = existingByNameLower[name.lowercase()]
                if (mode == ImportMode.MERGE && existing != null) {
                    val incomingUpdatedAt = obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    if (incomingUpdatedAt > existing.updatedAt) {
                        val merged = mergeEntityWithDefaults(HabitEntity(name = name), obj)
                        habitDao.update(merged.copy(id = existing.id))
                        ctx.lwwOverwrites++
                    } else {
                        ctx.duplicatesSkipped++
                    }
                    return@forEach
                }
                val default = HabitEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val habit = merged.copy(id = 0, updatedAt = System.currentTimeMillis())
                val id = habitDao.insert(habit)
                habitNameToId[name.lowercase()] = id
                ctx.habitsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import habit: ${e.message}")
            }
        }
        return habitNameToId
    }

    /**
     * Finds a derived collection by name. Prefers `derived.<name>` (v5+)
     * and falls back to the top-level key (v3/v4 back-compat).
     */
    private fun derivedArray(root: JsonObject, name: String) =
        root.getAsJsonObject("derived")?.getAsJsonArray(name)
            ?: root.getAsJsonArray(name)

    private suspend fun importTaskCompletions(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode
    ) {
        derivedArray(root, "taskCompletions")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val completedDate = obj.get("completedDate")?.takeIf { !it.isJsonNull }?.asLong
                    ?: return@forEach
                val default = TaskCompletionEntity(completedDate = completedDate)
                val merged = mergeEntityWithDefaults(default, obj)
                val completion = merged.copy(id = 0)
                taskCompletionDao.insert(completion)
                ctx.taskCompletionsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import task completion: ${e.message}")
            }
        }
    }

    private suspend fun importHabitCompletions(
        ctx: ImportContext,
        root: JsonObject,
        habitNameToId: Map<String, Long>
    ) {
        val existingCompletionKeys = habitCompletionDao
            .getAllCompletionsOnce()
            .map {
                it.habitId to (it.completedDateLocal ?: epochToLocalDateString(it.completedDate))
            }
            .toMutableSet()
        derivedArray(root, "habitCompletions")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val habitName = (obj.get("_habitName") ?: obj.get("habitName"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                if (habitName == null) {
                    ctx.orphansSkipped++
                    ctx.errors.add("Skipped habit completion: no _habitName")
                    return@forEach
                }
                val habitId = habitNameToId[habitName.lowercase()]
                if (habitId == null) {
                    ctx.orphansSkipped++
                    ctx.errors.add("Skipped habit completion: unknown habit '$habitName'")
                    return@forEach
                }
                val completedDate = obj.get("completedDate")?.takeIf { !it.isJsonNull }?.asLong
                    ?: return@forEach
                val completedDateLocal = obj.get("completedDateLocal")
                    ?.takeIf { !it.isJsonNull }?.asString
                    ?: epochToLocalDateString(completedDate)
                val key = habitId to completedDateLocal
                if (key in existingCompletionKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = HabitCompletionEntity(
                    habitId = habitId,
                    completedDate = completedDate,
                    completedDateLocal = completedDateLocal
                )
                val merged = mergeEntityWithDefaults(default, obj)
                val completion = merged.copy(
                    id = 0,
                    habitId = habitId,
                    completedDateLocal = completedDateLocal
                )
                habitCompletionDao.insert(completion)
                existingCompletionKeys.add(key)
                ctx.habitCompletionsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import habit completion: ${e.message}")
            }
        }
    }

    private fun epochToLocalDateString(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    private suspend fun importHabitLogs(
        ctx: ImportContext,
        root: JsonObject,
        habitNameToId: Map<String, Long>
    ) {
        val existingHabitLogKeys = habitLogDao
            .getAllLogsOnce()
            .map { it.habitId to it.date }
            .toMutableSet()
        derivedArray(root, "habitLogs")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val habitName = obj.get("_habitName")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("habitName")?.takeIf { !it.isJsonNull }?.asString
                val habitId = if (habitName != null) habitNameToId[habitName.lowercase()] else null
                if (habitId == null) return@forEach
                val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                val key = habitId to date
                if (key in existingHabitLogKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = com.averycorp.prismtask.data.local.entity
                    .HabitLogEntity(habitId = habitId, date = date)
                val merged = mergeEntityWithDefaults(default, obj)
                val log = merged.copy(id = 0, habitId = habitId)
                habitLogDao.insertLog(log)
                existingHabitLogKeys.add(key)
                ctx.habitLogsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import habit log: ${e.message}")
            }
        }
    }

    private suspend fun importLeisureLogs(ctx: ImportContext, root: JsonObject) {
        val existingLeisureDates = leisureDao.getAllLogsOnce().map { it.date }.toMutableSet()
        root.getAsJsonArray("leisureLogs")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                if (date in existingLeisureDates) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = LeisureLogEntity(date = date)
                val merged = mergeEntityWithDefaults(default, obj)
                leisureDao.insertLog(merged.copy(id = 0))
                existingLeisureDates.add(date)
                ctx.leisureLogsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import leisure log: ${e.message}")
            }
        }
    }

    private suspend fun importSelfCareLogs(ctx: ImportContext, root: JsonObject) {
        val existingSelfCareLogKeys = selfCareDao
            .getAllLogsOnce()
            .map { it.routineType to it.date }
            .toMutableSet()
        var importedAnyMedicationLog = false
        root.getAsJsonArray("selfCareLogs")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val routineType = obj.get("routineType")?.takeIf { !it.isJsonNull }?.asString
                    ?: return@forEach
                val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                val key = routineType to date
                if (key in existingSelfCareLogKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = SelfCareLogEntity(routineType = routineType, date = date)
                val merged = mergeEntityWithDefaults(default, obj)
                selfCareDao.insertLog(merged.copy(id = 0))
                existingSelfCareLogKeys.add(key)
                ctx.selfCareLogsImported++
                if (routineType == "medication" && merged.tiersByTime.isNotBlank() &&
                    merged.tiersByTime != "{}"
                ) {
                    importedAnyMedicationLog = true
                }
            } catch (e: Exception) {
                ctx.errors.add("Failed to import self-care log: ${e.message}")
            }
        }
        if (importedAnyMedicationLog) {
            runCatching { backfillMedicationTierStatesAfterRestore(ctx) }
                .onFailure {
                    ctx.errors.add(
                        "Failed to backfill medication_tier_states after restore: ${it.message}"
                    )
                }
        }
    }

    /**
     * D8 Item 8 — after a v3 backup restore that re-introduces legacy
     * `tiers_by_time` JSON content, populate `medication_tier_states` so
     * forward consumers (Firestore sync, future readers) see the
     * normalized state without waiting for the user to touch the
     * medication card. Mirrors `MIGRATION_59_60` semantics: DEFAULT slot
     * only, max-tier across timeOfDay entries, `tier_source = "computed"`.
     *
     * Idempotent — if a row already exists for `(med, default_slot, date)`
     * its tier is updated only when different. Restores executed before
     * any medications exist (or before the DEFAULT slot is seeded) skip
     * silently; the next `setTierForTime` call will dual-write the row.
     */
    private suspend fun backfillMedicationTierStatesAfterRestore(ctx: ImportContext) {
        val defaultSlot = medicationSlotDao.getByNameOnce("Default") ?: return
        val activeMeds = medicationDao.getActiveOnce()
        if (activeMeds.isEmpty()) return
        val medicationLogs = selfCareDao.getAllLogsOnce()
            .filter { it.routineType == "medication" }
        val tierOrder = listOf("essential", "prescription", "complete")
        val now = System.currentTimeMillis()
        for (log in medicationLogs) {
            val raw = log.tiersByTime
            if (raw.isBlank() || raw == "{}") continue
            val parsedTiers = parseTiersByTimeRaw(raw)
            val maxTier = parsedTiers.values
                .filter { it in tierOrder }
                .maxByOrNull { tierOrder.indexOf(it) }
                ?: continue
            val date = epochMillisToLocalDateString(log.date)
            for (med in activeMeds) {
                val existing = medicationTierStateDao.getForTripleOnce(med.id, date, defaultSlot.id)
                if (existing == null) {
                    medicationTierStateDao.insert(
                        com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity(
                            medicationId = med.id,
                            slotId = defaultSlot.id,
                            logDate = date,
                            tier = maxTier,
                            tierSource = "computed",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    ctx.medicationTierStatesBackfilled++
                } else if (existing.tier != maxTier) {
                    medicationTierStateDao.update(
                        existing.copy(tier = maxTier, updatedAt = now)
                    )
                    ctx.medicationTierStatesBackfilled++
                }
            }
        }
    }

    private fun parseTiersByTimeRaw(json: String): Map<String, String> = try {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        obj.entrySet().associate { (k, v) ->
            k to (v?.takeIf { !it.isJsonNull }?.asString ?: "")
        }.filterValues { it.isNotBlank() }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun epochMillisToLocalDateString(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        return java.time.LocalDate.ofInstant(instant, java.time.ZoneId.systemDefault()).toString()
    }

    private suspend fun importSelfCareSteps(ctx: ImportContext, root: JsonObject) {
        val existingStepIds = selfCareDao.getAllStepsOnce().map { it.stepId }.toMutableSet()
        root.getAsJsonArray("selfCareSteps")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val stepId = obj.get("stepId")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                if (stepId in existingStepIds) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val routineType = obj.get("routineType")?.takeIf { !it.isJsonNull }?.asString
                    ?: return@forEach
                val label = obj.get("label")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val duration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asString ?: "0"
                val tier = obj.get("tier")?.takeIf { !it.isJsonNull }?.asString ?: "solid"
                val phase = obj.get("phase")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val default = SelfCareStepEntity(
                    stepId = stepId,
                    routineType = routineType,
                    label = label,
                    duration = duration,
                    tier = tier,
                    phase = phase
                )
                val merged = mergeEntityWithDefaults(default, obj)
                selfCareDao.insertStep(merged.copy(id = 0))
                existingStepIds.add(stepId)
                ctx.selfCareStepsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import self-care step: ${e.message}")
            }
        }
    }

    /**
     * Imports top-level medications (v1.4+). Returns a map from the
     * exported primary-key id → new local id so [importMedicationDoses]
     * can remap the FK. MERGE mode dedups by unique normalized name
     * (lower-cased); newer `updatedAt` overwrites the existing row in
     * place.
     */
    private suspend fun importMedications(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode
    ): Map<Long, Long> {
        val medIdRemap = mutableMapOf<Long, Long>()
        val existingByNameLower = medicationDao.getAllOnce()
            .associateBy { it.name.trim().lowercase() }

        root.getAsJsonArray("medications")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                    ?: return@forEach
                val exportedId = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val existing = existingByNameLower[name.trim().lowercase()]

                if (mode == ImportMode.MERGE && existing != null) {
                    val incomingUpdatedAt =
                        obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    if (incomingUpdatedAt > existing.updatedAt) {
                        val merged = mergeEntityWithDefaults(
                            com.averycorp.prismtask.data.local.entity.MedicationEntity(name = name),
                            obj
                        )
                        medicationDao.update(merged.copy(id = existing.id))
                        ctx.lwwOverwrites++
                    } else {
                        ctx.duplicatesSkipped++
                    }
                    if (exportedId != 0L) medIdRemap[exportedId] = existing.id
                    return@forEach
                }

                val default =
                    com.averycorp.prismtask.data.local.entity.MedicationEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val med = merged.copy(
                    id = 0,
                    // Drop the exported cloud_id so the UNIQUE(cloud_id)
                    // index doesn't collide if this DB already has a
                    // row with the same cloud_id from a prior sync.
                    // Next sync push re-mints a cloud_id anyway.
                    cloudId = null,
                    updatedAt = System.currentTimeMillis()
                )
                val newId = medicationDao.insert(med)
                if (exportedId != 0L) medIdRemap[exportedId] = newId
                ctx.medicationsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import medication: ${e.message}")
            }
        }
        return medIdRemap
    }

    /**
     * Imports medication dose history. Skips any row whose
     * `medication_id` can't be remapped through the provided map
     * (parent row didn't import, likely due to an export-side data
     * issue — counted under `orphansSkipped`).
     */
    private suspend fun importMedicationDoses(
        ctx: ImportContext,
        root: JsonObject,
        medIdRemap: Map<Long, Long>
    ) {
        root.getAsJsonArray("medicationDoses")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val exportedMedId = obj.get("medicationId")
                    ?.takeIf { !it.isJsonNull }?.asLong
                val customMedicationName = obj.get("customMedicationName")
                    ?.takeIf { !it.isJsonNull }?.asString
                // A dose must reference either a tracked medication (FK
                // remap-able) or carry a custom-medication name. Rows
                // missing both are export-side data corruption — skip.
                if (exportedMedId == null && customMedicationName.isNullOrBlank()) {
                    return@forEach
                }
                val localMedId = exportedMedId?.let { medIdRemap[it] }
                if (exportedMedId != null && localMedId == null) {
                    // Tracked-medication dose whose parent didn't import.
                    ctx.orphansSkipped++
                    return@forEach
                }
                val slotKey = obj.get("slotKey")?.takeIf { !it.isJsonNull }?.asString
                    ?: "anytime"
                val takenAt = obj.get("takenAt")?.takeIf { !it.isJsonNull }?.asLong
                    ?: return@forEach
                val takenDateLocal =
                    obj.get("takenDateLocal")?.takeIf { !it.isJsonNull }?.asString
                        ?: return@forEach
                val default = com.averycorp.prismtask.data.local.entity.MedicationDoseEntity(
                    medicationId = localMedId,
                    customMedicationName = customMedicationName,
                    slotKey = slotKey,
                    takenAt = takenAt,
                    takenDateLocal = takenDateLocal
                )
                val merged = mergeEntityWithDefaults(default, obj)
                medicationDoseDao.insert(
                    merged.copy(
                        id = 0,
                        cloudId = null,
                        medicationId = localMedId,
                        customMedicationName = customMedicationName
                    )
                )
                ctx.medicationDosesImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import medication dose: ${e.message}")
            }
        }
    }

    private suspend fun importCourses(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode
    ): MutableMap<String, Long> {
        val courseNameToId = mutableMapOf<String, Long>()
        if (mode == ImportMode.MERGE) {
            schoolworkDao.getAllCoursesOnce().forEach { courseNameToId[it.name.lowercase()] = it.id }
        }

        root.getAsJsonArray("courses")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                if (mode == ImportMode.MERGE && name.lowercase() in courseNameToId) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val code = obj.get("code")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val default = CourseEntity(name = name, code = code)
                val merged = mergeEntityWithDefaults(default, obj)
                val id = schoolworkDao.insertCourse(merged.copy(id = 0))
                courseNameToId[name.lowercase()] = id
                ctx.coursesImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import course: ${e.message}")
            }
        }
        return courseNameToId
    }

    private suspend fun importAssignments(
        ctx: ImportContext,
        root: JsonObject,
        courseNameToId: Map<String, Long>
    ) {
        val existingAssignmentKeys = schoolworkDao
            .getAllAssignmentsOnce()
            .map { Triple(it.courseId, it.title, it.dueDate) }
            .toMutableSet()
        root.getAsJsonArray("assignments")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val courseName = (obj.get("_courseName") ?: obj.get("courseName"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString ?: return@forEach
                val courseId = courseNameToId[courseName.lowercase()] ?: return@forEach
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val dueDate = obj.get("dueDate")?.takeIf { !it.isJsonNull }?.asLong
                val key = Triple(courseId, title, dueDate)
                if (key in existingAssignmentKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = AssignmentEntity(courseId = courseId, title = title)
                val merged = mergeEntityWithDefaults(default, obj)
                schoolworkDao.insertAssignment(merged.copy(id = 0, courseId = courseId))
                existingAssignmentKeys.add(key)
                ctx.assignmentsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import assignment: ${e.message}")
            }
        }
    }

    private suspend fun importCourseCompletions(
        ctx: ImportContext,
        root: JsonObject,
        courseNameToId: Map<String, Long>
    ) {
        val existingCourseCompletionKeys = schoolworkDao
            .getAllCompletionsOnce()
            .map { it.courseId to it.date }
            .toMutableSet()
        derivedArray(root, "courseCompletions")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val courseName = (obj.get("_courseName") ?: obj.get("courseName"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString ?: return@forEach
                val courseId = courseNameToId[courseName.lowercase()] ?: return@forEach
                val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                val key = courseId to date
                if (key in existingCourseCompletionKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = CourseCompletionEntity(date = date, courseId = courseId)
                val merged = mergeEntityWithDefaults(default, obj)
                schoolworkDao.insertCompletion(merged.copy(id = 0, courseId = courseId))
                existingCourseCompletionKeys.add(key)
                ctx.courseCompletionsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import course completion: ${e.message}")
            }
        }
    }

    private suspend fun importConfig(ctx: ImportContext, root: JsonObject) {
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
                importUserPreferencesConfig(config)
                // --- v5 additions ---
                importA11yConfig(config)
                importVoiceConfig(config)
                importTimerConfig(config)
                importNotificationConfig(config)
                importNdConfig(config)
                importDailyEssentialsConfig(config)
                importMorningCheckInConfig(config)
                importCalendarSyncConfig(config)
                importTemplatesConfig(config)
                importOnboardingConfig(config)
                importCoachingConfig(config)
                importSortConfig(config)
                importAdvancedTuningConfig(config)
                ctx.configImported = true
            } catch (e: Exception) {
                ctx.errors.add("Failed to import config: ${e.message}")
            }
        }
    }

    private suspend fun importThemeConfig(config: JsonObject) {
        config.getAsJsonObject("theme")?.let { theme ->
            theme
                .get("themeMode")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setThemeMode(it) }
            theme
                .get("accentColor")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setAccentColor(it) }
            theme
                .get("backgroundColor")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setBackgroundColor(it) }
            theme
                .get("surfaceColor")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setSurfaceColor(it) }
            theme
                .get("errorColor")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setErrorColor(it) }
            theme
                .get("fontScale")
                ?.takeIf { !it.isJsonNull }
                ?.asFloat
                ?.let { themePreferences.setFontScale(it) }
            theme
                .get("priorityColorNone")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setPriorityColor(0, it) }
            theme
                .get("priorityColorLow")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setPriorityColor(1, it) }
            theme
                .get("priorityColorMedium")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setPriorityColor(2, it) }
            theme
                .get("priorityColorHigh")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setPriorityColor(3, it) }
            theme
                .get("priorityColorUrgent")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setPriorityColor(4, it) }
            theme.getAsJsonArray("recentCustomColors")?.forEach { elem ->
                if (!elem.isJsonNull) themePreferences.addRecentCustomColor(elem.asString)
            }
            theme
                .get("prismTheme")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { themePreferences.setPrismTheme(it) }
        }
    }

    private suspend fun importArchiveConfig(config: JsonObject) {
        config.getAsJsonObject("archive")?.let { archive ->
            archive
                .get("autoArchiveDays")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
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
            dashboard
                .get("progressStyle")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { dashboardPreferences.setProgressStyle(it) }
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
            tb
                .get("defaultSort")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.let { taskBehaviorPreferences.setDefaultSort(it) }
            tb
                .get("defaultViewMode")
                ?.takeIf { !it.isJsonNull }
                ?.asString
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
            tb
                .get("dayStartHour")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
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
            hl
                .get("selfCareEnabled")
                ?.takeIf { !it.isJsonNull }
                ?.asBoolean
                ?.let { habitListPreferences.setSelfCareEnabled(it) }
            hl
                .get("medicationEnabled")
                ?.takeIf { !it.isJsonNull }
                ?.asBoolean
                ?.let { habitListPreferences.setMedicationEnabled(it) }
            hl
                .get("schoolEnabled")
                ?.takeIf { !it.isJsonNull }
                ?.asBoolean
                ?.let { habitListPreferences.setSchoolEnabled(it) }
            hl
                .get("leisureEnabled")
                ?.takeIf { !it.isJsonNull }
                ?.asBoolean
                ?.let { habitListPreferences.setLeisureEnabled(it) }
            hl
                .get("houseworkEnabled")
                ?.takeIf { !it.isJsonNull }
                ?.asBoolean
                ?.let { habitListPreferences.setHouseworkEnabled(it) }
            hl
                .get("streakMaxMissedDays")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
                ?.let { habitListPreferences.setStreakMaxMissedDays(it) }
            hl
                .get("todaySkipAfterCompleteDays")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
                ?.let { habitListPreferences.setTodaySkipAfterCompleteDays(it) }
            hl
                .get("todaySkipBeforeScheduleDays")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
                ?.let { habitListPreferences.setTodaySkipBeforeScheduleDays(it) }
        }
    }

    private suspend fun importLeisureConfig(ctx: ImportContext, config: JsonObject) {
        config.getAsJsonObject("leisure")?.let { lp ->
            val listType = object : TypeToken<List<CustomLeisureActivity>>() {}.type
            lp.getAsJsonArray("customMusicActivities")?.let { arr ->
                val activities: List<CustomLeisureActivity> = gson.fromJson(arr, listType)
                val existingLabels = leisurePreferences
                    .getCustomMusicActivities()
                    .first()
                    .map { it.label.lowercase() }
                    .toMutableSet()
                activities.forEach {
                    if (it.label.lowercase() !in existingLabels) {
                        leisurePreferences.addMusicActivity(it.label, it.icon)
                        existingLabels.add(it.label.lowercase())
                    } else {
                        ctx.duplicatesSkipped++
                    }
                }
            }
            lp.getAsJsonArray("customFlexActivities")?.let { arr ->
                val activities: List<CustomLeisureActivity> = gson.fromJson(arr, listType)
                val existingLabels = leisurePreferences
                    .getCustomFlexActivities()
                    .first()
                    .map { it.label.lowercase() }
                    .toMutableSet()
                activities.forEach {
                    if (it.label.lowercase() !in existingLabels) {
                        leisurePreferences.addFlexActivity(it.label, it.icon)
                        existingLabels.add(it.label.lowercase())
                    } else {
                        ctx.duplicatesSkipped++
                    }
                }
            }
        }
    }

    private suspend fun importMedicationConfig(config: JsonObject) {
        config.getAsJsonObject("medication")?.let { med ->
            med
                .get(
                    "reminderIntervalMinutes"
                )?.takeIf { !it.isJsonNull }
                ?.asInt
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

    private suspend fun importUserPreferencesConfig(config: JsonObject) {
        config.getAsJsonObject("userPreferences")?.let { userPrefs ->
            importAppearancePrefs(userPrefs)
            importSwipePrefs(userPrefs)
            importTaskDefaultsPrefs(userPrefs)
            importQuickAddPrefs(userPrefs)
            importWorkLifeBalancePrefs(userPrefs)
            importForgivenessPrefs(userPrefs)
            importTaskMenuActionsPrefs(userPrefs)
            importTaskCardDisplayPrefs(userPrefs)
        }
    }

    private suspend fun importForgivenessPrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("forgiveness")?.let { f ->
            val current = userPreferencesDataStore.forgivenessFlow.first()
            userPreferencesDataStore.setForgivenessPrefs(
                ForgivenessPrefs(
                    enabled = f.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.enabled,
                    gracePeriodDays = f.get("gracePeriodDays")?.takeIf { !it.isJsonNull }?.asInt
                        ?: current.gracePeriodDays,
                    allowedMisses = f.get("allowedMisses")?.takeIf { !it.isJsonNull }?.asInt
                        ?: current.allowedMisses
                )
            )
        }
    }

    private suspend fun importTaskMenuActionsPrefs(userPrefs: JsonObject) {
        val arr = userPrefs.getAsJsonArray("taskMenuActions") ?: return
        try {
            val listType = TypeToken.getParameterized(
                List::class.java,
                com.averycorp.prismtask.domain.model.TaskMenuAction::class.java
            ).type
            val actions: List<com.averycorp.prismtask.domain.model.TaskMenuAction> =
                gson.fromJson(arr, listType)
            userPreferencesDataStore.setTaskMenuActions(actions)
        } catch (_: Exception) {
            // Malformed — fall back to defaults (already the DataStore behavior).
        }
    }

    private suspend fun importTaskCardDisplayPrefs(userPrefs: JsonObject) {
        val obj = userPrefs.getAsJsonObject("taskCardDisplay") ?: return
        try {
            val cfg = gson.fromJson(
                obj,
                com.averycorp.prismtask.domain.model.TaskCardDisplayConfig::class.java
            )
            if (cfg != null) userPreferencesDataStore.setTaskCardDisplay(cfg)
        } catch (_: Exception) {
            // Malformed — ignore.
        }
    }

    private suspend fun importAppearancePrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("appearance")?.let { a ->
            val current = userPreferencesDataStore.appearanceFlow.first()
            userPreferencesDataStore.setAppearance(
                com.averycorp.prismtask.data.preferences.AppearancePrefs(
                    compactMode = a.get("compactMode")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.compactMode,
                    showTaskCardBorders =
                    a.get("showTaskCardBorders")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.showTaskCardBorders,
                    cardCornerRadius = a.get("cardCornerRadius")?.takeIf { !it.isJsonNull }?.asInt ?: current.cardCornerRadius
                )
            )
        }
    }

    private suspend fun importSwipePrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("swipe")?.let { s ->
            userPreferencesDataStore.setSwipe(
                com.averycorp.prismtask.data.preferences.SwipePrefs(
                    right = com.averycorp.prismtask.domain.model.SwipeAction.fromName(
                        s.get("right")?.takeIf { !it.isJsonNull }?.asString
                    ),
                    left = com.averycorp.prismtask.domain.model.SwipeAction.fromName(
                        s.get("left")?.takeIf { !it.isJsonNull }?.asString
                            ?: com.averycorp.prismtask.domain.model.SwipeAction.DELETE.name
                    )
                )
            )
        }
    }

    private suspend fun importTaskDefaultsPrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("taskDefaults")?.let { d ->
            val current = userPreferencesDataStore.taskDefaultsFlow.first()
            userPreferencesDataStore.setTaskDefaults(
                com.averycorp.prismtask.data.preferences.TaskDefaults(
                    defaultPriority = d.get("defaultPriority")?.takeIf { !it.isJsonNull }?.asInt ?: current.defaultPriority,
                    defaultReminderOffset =
                    d.get("defaultReminderOffset")?.takeIf { !it.isJsonNull }?.asLong ?: current.defaultReminderOffset,
                    defaultProjectId = d.get("defaultProjectId")?.takeIf { !it.isJsonNull }?.asLong ?: current.defaultProjectId,
                    startOfWeek = com.averycorp.prismtask.domain.model.StartOfWeek.fromName(
                        d.get("startOfWeek")?.takeIf { !it.isJsonNull }?.asString
                    ),
                    defaultDuration = d.get("defaultDuration")?.takeIf { !it.isJsonNull }?.asInt ?: current.defaultDuration,
                    autoSetDueDate = com.averycorp.prismtask.domain.model.AutoDueDate.fromName(
                        d.get("autoSetDueDate")?.takeIf { !it.isJsonNull }?.asString
                    ),
                    smartDefaultsEnabled =
                    d.get("smartDefaultsEnabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.smartDefaultsEnabled
                )
            )
        }
    }

    private suspend fun importQuickAddPrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("quickAdd")?.let { q ->
            userPreferencesDataStore.setQuickAdd(
                com.averycorp.prismtask.data.preferences.QuickAddPrefs(
                    showConfirmation = q.get("showConfirmation")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    autoAssignProject = q.get("autoAssignProject")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                )
            )
        }
    }

    private suspend fun importWorkLifeBalancePrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("workLifeBalance")?.let { w ->
            val current = userPreferencesDataStore.workLifeBalanceFlow.first()
            userPreferencesDataStore.setWorkLifeBalance(
                com.averycorp.prismtask.data.preferences.WorkLifeBalancePrefs(
                    workTarget = w.get("workTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.workTarget,
                    personalTarget = w.get("personalTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.personalTarget,
                    selfCareTarget = w.get("selfCareTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.selfCareTarget,
                    healthTarget = w.get("healthTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.healthTarget,
                    showBalanceBar = w.get("showBalanceBar")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.showBalanceBar,
                    overloadThresholdPct =
                    w.get("overloadThresholdPct")?.takeIf { !it.isJsonNull }?.asInt ?: current.overloadThresholdPct
                )
            )
        }
    }

    // ---------------------------------------------------------------------
    // v5 preference importers
    // ---------------------------------------------------------------------

    private suspend fun importA11yConfig(config: JsonObject) {
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

    private suspend fun importVoiceConfig(config: JsonObject) {
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

    private suspend fun importTimerConfig(config: JsonObject) {
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
        }
    }

    private suspend fun importNotificationConfig(config: JsonObject) {
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

    private suspend fun importNdConfig(config: JsonObject) {
        val nd = config.getAsJsonObject("nd") ?: return
        // Route every known ND key through updateNdPreference so validation,
        // enum coercion, and coupling (mode toggles flip sub-settings) live in
        // one place. Unknown keys are skipped silently.
        nd.entrySet().forEach { (key, value) ->
            if (value.isJsonNull) return@forEach
            val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@forEach
            // Map our camelCase data-class field names to the keys
            // updateNdPreference() expects (snake_case + "_enabled" suffixes).
            val mapped = NdCamelToUpdateKey[key] ?: return@forEach
            val coerced: Any? = when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asNumber.toInt()
                primitive.isString -> primitive.asString
                else -> null
            }
            if (coerced != null) {
                runCatching { ndPreferencesDataStore.updateNdPreference(mapped, coerced) }
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

    /**
     * Restores power-user tuning knobs ([AdvancedTuningPreferences]) from a
     * v5+ backup. Each sub-key maps to a typed config data class; missing
     * sub-keys (older backups) silently no-op so the local default stands.
     * Each setter applies its own clamping, so out-of-range stored values
     * cannot land in the live preference.
     */
    private suspend fun importAdvancedTuningConfig(config: JsonObject) {
        val a = config.getAsJsonObject("advancedTuning") ?: return
        val p = advancedTuningPreferences
        a.getAsJsonObject("urgencyBands")?.let {
            p.setUrgencyBands(gson.fromJson(it, UrgencyBands::class.java))
        }
        a.getAsJsonObject("urgencyWindows")?.let {
            p.setUrgencyWindows(gson.fromJson(it, UrgencyWindows::class.java))
        }
        a.getAsJsonObject("burnoutWeights")?.let {
            p.setBurnoutWeights(gson.fromJson(it, BurnoutWeights::class.java))
        }
        a.getAsJsonObject("productivityWeights")?.let {
            p.setProductivityWeights(gson.fromJson(it, ProductivityWeights::class.java))
        }
        a.getAsJsonObject("moodCorrelation")?.let {
            p.setMoodCorrelationConfig(gson.fromJson(it, MoodCorrelationConfig::class.java))
        }
        a.getAsJsonObject("refillUrgency")?.let {
            p.setRefillUrgencyConfig(gson.fromJson(it, RefillUrgencyConfig::class.java))
        }
        a.getAsJsonObject("energyPomodoro")?.let {
            p.setEnergyPomodoroConfig(gson.fromJson(it, EnergyPomodoroConfig::class.java))
        }
        a.getAsJsonObject("goodEnoughTimer")?.let {
            p.setGoodEnoughTimerConfig(gson.fromJson(it, GoodEnoughTimerConfig::class.java))
        }
        a.getAsJsonObject("suggestion")?.let {
            p.setSuggestionConfig(gson.fromJson(it, SuggestionConfig::class.java))
        }
        a.getAsJsonObject("extractor")?.let {
            p.setExtractorConfig(gson.fromJson(it, ExtractorConfig::class.java))
        }
        a.getAsJsonObject("smartDefaults")?.let {
            p.setSmartDefaultsConfig(gson.fromJson(it, SmartDefaultsConfig::class.java))
        }
        a.getAsJsonObject("morningCheckInCutoff")?.let {
            p.setMorningCheckInPromptCutoff(gson.fromJson(it, MorningCheckInPromptCutoff::class.java))
        }
        a.getAsJsonObject("lifeCategoryKeywords")?.let {
            p.setLifeCategoryCustomKeywords(gson.fromJson(it, LifeCategoryCustomKeywords::class.java))
        }
        a.getAsJsonObject("taskModeKeywords")?.let {
            p.setTaskModeCustomKeywords(gson.fromJson(it, TaskModeCustomKeywords::class.java))
        }
        a.getAsJsonObject("cognitiveLoadKeywords")?.let {
            p.setCognitiveLoadCustomKeywords(gson.fromJson(it, CognitiveLoadCustomKeywords::class.java))
        }
        a.getAsJsonObject("weeklySummary")?.let {
            p.setWeeklySummarySchedule(gson.fromJson(it, WeeklySummarySchedule::class.java))
        }
        a.getAsJsonObject("reengagement")?.let {
            p.setReengagementConfig(gson.fromJson(it, ReengagementConfig::class.java))
        }
        a.getAsJsonObject("overloadCheck")?.let {
            p.setOverloadCheckSchedule(gson.fromJson(it, OverloadCheckSchedule::class.java))
        }
        a.getAsJsonObject("batchUndo")?.let {
            p.setBatchUndoConfig(gson.fromJson(it, BatchUndoConfig::class.java))
        }
        a.getAsJsonObject("habitReminderFallback")?.let {
            p.setHabitReminderFallback(gson.fromJson(it, HabitReminderFallback::class.java))
        }
        a.getAsJsonObject("apiNetwork")?.let {
            p.setApiNetworkConfig(gson.fromJson(it, ApiNetworkConfig::class.java))
        }
        a.getAsJsonObject("widgetRefresh")?.let {
            p.setWidgetRefreshConfig(gson.fromJson(it, WidgetRefreshConfig::class.java))
        }
        a.getAsJsonObject("productivityWidget")?.let {
            p.setProductivityWidgetThresholds(gson.fromJson(it, ProductivityWidgetThresholds::class.java))
        }
        a.getAsJsonObject("editorFieldRows")?.let {
            p.setEditorFieldRows(gson.fromJson(it, EditorFieldRows::class.java))
        }
        a.getAsJsonObject("quickAddRows")?.let {
            p.setQuickAddRows(gson.fromJson(it, QuickAddRows::class.java))
        }
        a.getAsJsonObject("searchPreview")?.let {
            p.setSearchPreview(gson.fromJson(it, SearchPreview::class.java))
        }
        a.getAsJsonObject("selfCareTierDefaults")?.let {
            p.setSelfCareTierDefaults(gson.fromJson(it, SelfCareTierDefaults::class.java))
        }
    }

    companion object {
        /**
         * Maps serialized `NdPreferences` field names (camelCase) to the keys
         * accepted by [NdPreferencesDataStore.updateNdPreference] (snake_case,
         * some with an "_enabled" suffix).
         */
        private val NdCamelToUpdateKey: Map<String, String> = mapOf(
            "adhdModeEnabled" to "adhd_mode_enabled",
            "calmModeEnabled" to "calm_mode_enabled",
            "focusReleaseModeEnabled" to "focus_release_mode_enabled",
            "reduceAnimations" to "reduce_animations",
            "mutedColorPalette" to "muted_color_palette",
            "quietMode" to "quiet_mode",
            "reduceHaptics" to "reduce_haptics",
            "softContrast" to "soft_contrast",
            "checkInIntervalMinutes" to "check_in_interval_minutes",
            "completionAnimations" to "completion_animations",
            "streakCelebrations" to "streak_celebrations",
            "showProgressBars" to "show_progress_bars",
            "forgivenessStreaks" to "forgiveness_streaks",
            "goodEnoughTimersEnabled" to "good_enough_timers_enabled",
            "defaultGoodEnoughMinutes" to "default_good_enough_minutes",
            "goodEnoughEscalation" to "good_enough_escalation",
            "antiReworkEnabled" to "anti_rework_enabled",
            "softWarningEnabled" to "soft_warning_enabled",
            "coolingOffEnabled" to "cooling_off_enabled",
            "coolingOffMinutes" to "cooling_off_minutes",
            "revisionCounterEnabled" to "revision_counter_enabled",
            "maxRevisions" to "max_revisions",
            "shipItCelebrationsEnabled" to "ship_it_celebrations_enabled",
            "celebrationIntensity" to "celebration_intensity"
        )
    }
}
