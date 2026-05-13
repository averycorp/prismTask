package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.HabitLogDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
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
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

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
 *
 * === Internal layout ===
 * This class is a thin coordinator. The real work lives in package-internal
 * helpers, each of which mutates a shared [ImportContext]:
 *  - [EntityImporters] — projects, tags, tasks, habits, task / habit
 *    completions, habit logs, leisure logs
 *  - [WellnessImporters] — self-care logs/steps, medications + doses (with
 *    tier-state backfill), schoolwork (courses, assignments, completions)
 *  - [MedicationTierBackfiller] — post-restore `medication_tier_states`
 *    normalization (D8 Item 8)
 *  - [ConfigImporter] / [UserPreferencesImporter] /
 *    [DeviceConfigImporter] / [AdvancedTuningImporter] — every
 *    `config.*` block (theme, archive, dashboard, etc.)
 *
 * Splitting the original 1,832-LOC file this way keeps the public API
 * (`importFromJson`) unchanged while each helper stays within the audit's
 * per-helper LOC budget.
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
    private val taskCompletionDao: TaskCompletionDao,
    habitLogDao: HabitLogDao,
    selfCareDao: SelfCareDao,
    schoolworkDao: SchoolworkDao,
    medicationDao: MedicationDao,
    medicationDoseDao: MedicationDoseDao,
    /**
     * D8 Item 8 — on-restore backfill of `medication_tier_states`. Mirrors
     * `MIGRATION_59_60` semantics so a v3 backup restored on a fresh
     * device sees the normalized table populated immediately rather than
     * waiting for the next live `setTierForTime` call.
     */
    medicationSlotDao: MedicationSlotDao,
    medicationTierStateDao: MedicationTierStateDao,
    private val transactionRunner: DatabaseTransactionRunner,
    themePreferences: ThemePreferences,
    archivePreferences: ArchivePreferences,
    dashboardPreferences: DashboardPreferences,
    tabPreferences: TabPreferences,
    taskBehaviorPreferences: TaskBehaviorPreferences,
    habitListPreferences: HabitListPreferences,
    medicationPreferences: MedicationPreferences,
    userPreferencesDataStore: UserPreferencesDataStore,
    // v5 additions — see audit doc
    a11yPreferences: A11yPreferences,
    voicePreferences: VoicePreferences,
    timerPreferences: TimerPreferences,
    notificationPreferences: NotificationPreferences,
    ndPreferencesDataStore: NdPreferencesDataStore,
    dailyEssentialsPreferences: DailyEssentialsPreferences,
    morningCheckInPreferences: MorningCheckInPreferences,
    calendarSyncPreferences: CalendarSyncPreferences,
    templatePreferences: TemplatePreferences,
    onboardingPreferences: OnboardingPreferences,
    coachingPreferences: CoachingPreferences,
    sortPreferences: SortPreferences,
    advancedTuningPreferences: AdvancedTuningPreferences
) {
    private val gson = Gson()

    private val tierBackfiller = MedicationTierBackfiller(
        selfCareDao,
        medicationDao,
        medicationSlotDao,
        medicationTierStateDao
    )

    private val entityImporters = EntityImporters(
        taskDao,
        projectDao,
        tagDao,
        habitDao,
        habitCompletionDao,
        taskCompletionDao,
        habitLogDao
    )

    private val wellnessImporters = WellnessImporters(
        selfCareDao,
        schoolworkDao,
        medicationDao,
        medicationDoseDao,
        tierBackfiller
    )

    private val userPrefsImporter = UserPreferencesImporter(userPreferencesDataStore, gson)
    private val deviceConfigImporter = DeviceConfigImporter(
        a11yPreferences,
        voicePreferences,
        timerPreferences,
        notificationPreferences
    )
    private val advancedTuningImporter = AdvancedTuningImporter(
        advancedTuningPreferences,
        ndPreferencesDataStore,
        gson
    )
    private val configImporter = ConfigImporter(
        themePreferences,
        archivePreferences,
        dashboardPreferences,
        tabPreferences,
        taskBehaviorPreferences,
        habitListPreferences,
        medicationPreferences,
        dailyEssentialsPreferences,
        morningCheckInPreferences,
        calendarSyncPreferences,
        templatePreferences,
        onboardingPreferences,
        coachingPreferences,
        sortPreferences,
        userPrefsImporter,
        deviceConfigImporter,
        advancedTuningImporter
    )

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

                val projectNameToId = entityImporters.importProjects(ctx, root, mode, existingProjects)
                val tagNameToId = entityImporters.importTags(ctx, root, mode, existingTags)
                entityImporters.importTasks(ctx, root, mode, existingTasks, projectNameToId, tagNameToId)
                if (options.restoreDerivedData) {
                    entityImporters.importTaskCompletions(ctx, root, mode)
                } else {
                    ctx.derivedDataSkipped = true
                }

                val habitNameToId = entityImporters.importHabits(ctx, root, mode)
                if (options.restoreDerivedData) {
                    entityImporters.importHabitCompletions(ctx, root, habitNameToId)
                    entityImporters.importHabitLogs(ctx, root, habitNameToId)
                } else {
                    ctx.derivedDataSkipped = true
                }

                entityImporters.importLeisureLogs(ctx, root)
                wellnessImporters.importSelfCareLogs(ctx, root)
                wellnessImporters.importSelfCareSteps(ctx, root)

                // medication_doses MUST come after medications because
                // it FK's to medication_id; we use the export-side id
                // as the join key via medIdRemap.
                val medIdRemap = wellnessImporters.importMedications(ctx, root, mode)
                if (options.restoreDerivedData) {
                    wellnessImporters.importMedicationDoses(ctx, root, medIdRemap)
                }

                val courseNameToId = wellnessImporters.importCourses(ctx, root, mode)
                wellnessImporters.importAssignments(ctx, root, courseNameToId)
                if (options.restoreDerivedData) {
                    wellnessImporters.importCourseCompletions(ctx, root, courseNameToId)
                }
            }

            configImporter.importConfig(ctx, root)
        } catch (e: Exception) {
            ctx.errors.add("Import failed: ${e.message}")
        }

        return ctx.toResult()
    }
}
