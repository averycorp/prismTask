package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.HabitLogDao
import com.averycorp.prismtask.data.local.dao.LeisureDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.preferences.A11yPreferences
import com.averycorp.prismtask.data.preferences.ArchivePreferences
import com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.LeisurePreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.MorningCheckInPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TabPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.VoicePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the v5 export/import additions:
 *  - `schemaVersion` detection,
 *  - `ImportOptions.restoreDerivedData` opt-out,
 *  - last-write-wins upsert on projects/habits,
 *  - orphan-row counting,
 *  - per-section `ReplaceScope`.
 */
class DataImporterV5Test {
    private lateinit var taskDao: TaskDao
    private lateinit var projectDao: ProjectDao
    private lateinit var tagDao: TagDao
    private lateinit var habitDao: HabitDao
    private lateinit var habitCompletionDao: HabitCompletionDao
    private lateinit var habitLogDao: HabitLogDao
    private lateinit var leisureDao: LeisureDao
    private lateinit var selfCareDao: SelfCareDao
    private lateinit var schoolworkDao: SchoolworkDao

    // Inline transaction runner — tests don't need real Room transactions,
    // just faithful block execution so every DAO call still happens.
    private val transactionRunner =
        object : DatabaseTransactionRunner(mockk(relaxed = true)) {
            override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
        }
    private lateinit var themePreferences: ThemePreferences
    private lateinit var archivePreferences: ArchivePreferences
    private lateinit var dashboardPreferences: DashboardPreferences
    private lateinit var tabPreferences: TabPreferences
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var habitListPreferences: HabitListPreferences
    private lateinit var leisurePreferences: LeisurePreferences
    private lateinit var medicationPreferences: MedicationPreferences
    private lateinit var userPreferencesDataStore: UserPreferencesDataStore
    private lateinit var taskCompletionDao: TaskCompletionDao
    private lateinit var a11yPreferences: A11yPreferences
    private lateinit var voicePreferences: VoicePreferences
    private lateinit var timerPreferences: TimerPreferences
    private lateinit var notificationPreferences: NotificationPreferences
    private lateinit var ndPreferencesDataStore: NdPreferencesDataStore
    private lateinit var dailyEssentialsPreferences: DailyEssentialsPreferences
    private lateinit var morningCheckInPreferences: MorningCheckInPreferences
    private lateinit var calendarSyncPreferences: CalendarSyncPreferences
    private lateinit var templatePreferences: TemplatePreferences
    private lateinit var importer: DataImporter

    @Before
    fun setUp() {
        taskDao = mockk(relaxed = true)
        projectDao = mockk(relaxed = true)
        tagDao = mockk(relaxed = true)
        habitDao = mockk(relaxed = true)
        habitCompletionDao = mockk(relaxed = true)
        habitLogDao = mockk(relaxed = true)
        leisureDao = mockk(relaxed = true)
        selfCareDao = mockk(relaxed = true)
        schoolworkDao = mockk(relaxed = true)
        taskCompletionDao = mockk(relaxed = true)
        themePreferences = mockk(relaxed = true)
        archivePreferences = mockk(relaxed = true)
        dashboardPreferences = mockk(relaxed = true)
        tabPreferences = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        habitListPreferences = mockk(relaxed = true)
        leisurePreferences = mockk(relaxed = true)
        medicationPreferences = mockk(relaxed = true)
        userPreferencesDataStore = mockk(relaxed = true)
        a11yPreferences = mockk(relaxed = true)
        voicePreferences = mockk(relaxed = true)
        timerPreferences = mockk(relaxed = true)
        notificationPreferences = mockk(relaxed = true)
        ndPreferencesDataStore = mockk(relaxed = true)
        dailyEssentialsPreferences = mockk(relaxed = true)
        morningCheckInPreferences = mockk(relaxed = true)
        calendarSyncPreferences = mockk(relaxed = true)
        templatePreferences = mockk(relaxed = true)

        coEvery { taskDao.getAllTasksOnce() } returns emptyList()
        coEvery { projectDao.getAllProjectsOnce() } returns emptyList()
        coEvery { tagDao.getAllTagsOnce() } returns emptyList()
        coEvery { habitDao.getAllHabitsOnce() } returns emptyList()
        coEvery { habitCompletionDao.getAllCompletionsOnce() } returns emptyList()
        coEvery { taskCompletionDao.getAllCompletionsOnce() } returns emptyList()
        coEvery { habitLogDao.getAllLogsOnce() } returns emptyList()
        coEvery { leisureDao.getAllLogsOnce() } returns emptyList()
        coEvery { selfCareDao.getAllLogsOnce() } returns emptyList()
        coEvery { selfCareDao.getAllStepsOnce() } returns emptyList()
        coEvery { schoolworkDao.getAllCoursesOnce() } returns emptyList()
        coEvery { schoolworkDao.getAllAssignmentsOnce() } returns emptyList()
        coEvery { schoolworkDao.getAllCompletionsOnce() } returns emptyList()

        importer = DataImporter(
            taskDao, projectDao, tagDao, habitDao, habitCompletionDao,
            taskCompletionDao, habitLogDao, leisureDao, selfCareDao, schoolworkDao,
            // medicationDao + medicationDoseDao + medicationSlotDao + medicationTierStateDao
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            transactionRunner, themePreferences, archivePreferences, dashboardPreferences,
            tabPreferences, taskBehaviorPreferences, habitListPreferences,
            leisurePreferences, medicationPreferences, userPreferencesDataStore,
            a11yPreferences, voicePreferences, timerPreferences,
            notificationPreferences, ndPreferencesDataStore, dailyEssentialsPreferences,
            morningCheckInPreferences, calendarSyncPreferences, templatePreferences,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true)
        )
    }

    // ---------------------------------------------------------------------
    // Schema version detection
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_readsSchemaVersionFieldWhenPresent() = runBlocking {
        val result = importer.importFromJson(
            """{ "schemaVersion": 5, "version": 5 }""",
            ImportMode.MERGE
        )
        assertEquals(5, result.schemaVersion)
    }

    @Test
    fun importFromJson_fallsBackToLegacyVersionField() = runBlocking {
        val result = importer.importFromJson(
            """{ "version": 4 }""",
            ImportMode.MERGE
        )
        assertEquals(4, result.schemaVersion)
    }

    // ---------------------------------------------------------------------
    // Derived-data opt-out
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_restoreDerivedDataFalse_skipsTaskCompletions() = runBlocking {
        val json =
            """
            {
              "schemaVersion": 5,
              "taskCompletions": [ { "completedDate": 1700000000000 } ],
              "derived": { "taskCompletions": [ { "completedDate": 1700000000001 } ] }
            }
            """.trimIndent()

        val result = importer.importFromJson(
            json,
            ImportMode.MERGE,
            ImportOptions(restoreDerivedData = false)
        )

        assertEquals(0, result.taskCompletionsImported)
        assertTrue(result.derivedDataSkipped)
        coVerify(exactly = 0) { taskCompletionDao.insert(any()) }
    }

    @Test
    fun importFromJson_restoreDerivedDataTrue_prefersDerivedBlock() = runBlocking {
        // When both top-level and derived.taskCompletions exist, the v5 importer
        // prefers the nested block. One row under derived, none at top: we
        // should see exactly one insertion.
        val json =
            """
            {
              "schemaVersion": 5,
              "derived": { "taskCompletions": [ { "completedDate": 1700000000000 } ] }
            }
            """.trimIndent()

        val result = importer.importFromJson(
            json,
            ImportMode.MERGE,
            ImportOptions(restoreDerivedData = true)
        )

        assertEquals(1, result.taskCompletionsImported)
        assertFalse(result.derivedDataSkipped)
    }

    @Test
    fun importFromJson_habitCompletionsReadFromLegacyTopLevel() = runBlocking {
        // v3/v4 backups keep derived collections at the top level. v5 importers
        // must still pick them up when `derived.*` is absent.
        coEvery { habitDao.insert(any()) } returns 77L
        val json =
            """
            {
              "version": 4,
              "habits": [ { "name": "Meditate" } ],
              "habitCompletions": [ { "_habitName": "Meditate", "completedDate": 1700000000000 } ]
            }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.habitCompletionsImported)
    }

    // ---------------------------------------------------------------------
    // Last-write-wins
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_newerProject_overwritesExistingOnMerge() = runBlocking {
        val existing = ProjectEntity(id = 42L, name = "Home", color = "#000000", updatedAt = 1000L)
        coEvery { projectDao.getAllProjectsOnce() } returns listOf(existing)
        val updated = slot<ProjectEntity>()
        coEvery { projectDao.update(capture(updated)) } returns Unit

        val json =
            """
            {
              "projects": [
                { "name": "Home", "color": "#112233", "updatedAt": 5000 }
              ]
            }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.lwwOverwrites)
        assertEquals(0, result.projectsImported)
        assertEquals(0, result.duplicatesSkipped)
        assertEquals(42L, updated.captured.id)
        assertEquals("#112233", updated.captured.color)
    }

    @Test
    fun importFromJson_olderProject_countsAsDuplicate() = runBlocking {
        val existing = ProjectEntity(id = 42L, name = "Home", updatedAt = 9000L)
        coEvery { projectDao.getAllProjectsOnce() } returns listOf(existing)

        val json =
            """
            {
              "projects": [
                { "name": "Home", "color": "#112233", "updatedAt": 5000 }
              ]
            }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.duplicatesSkipped)
        assertEquals(0, result.lwwOverwrites)
        coVerify(exactly = 0) { projectDao.update(any()) }
    }

    @Test
    fun importFromJson_newerHabit_overwritesExistingOnMerge() = runBlocking {
        val existing = HabitEntity(id = 7L, name = "Meditate", updatedAt = 1000L)
        coEvery { habitDao.getAllHabitsOnce() } returns listOf(existing)
        val updated = slot<HabitEntity>()
        coEvery { habitDao.update(capture(updated)) } returns Unit

        val json =
            """
            {
              "habits": [
                { "name": "Meditate", "targetFrequency": 3, "updatedAt": 5000 }
              ]
            }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.lwwOverwrites)
        assertEquals(7L, updated.captured.id)
    }

    // ---------------------------------------------------------------------
    // Orphan handling
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_orphanHabitCompletion_countedAndErrorLogged() = runBlocking {
        val json =
            """
            {
              "habitCompletions": [
                { "_habitName": "GhostHabit", "completedDate": 1700000000000 }
              ]
            }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(0, result.habitCompletionsImported)
        assertEquals(1, result.orphansSkipped)
        assertTrue(result.errors.any { it.contains("GhostHabit", ignoreCase = true) })
    }

    // ---------------------------------------------------------------------
    // Per-section REPLACE scope
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_replaceWithHabitsScopeOnly_preservesTasks() = runBlocking {
        coEvery { taskDao.getAllTasksOnce() } returns listOf(
            com.averycorp.prismtask.data.local.entity.TaskEntity(id = 1L, title = "Keep me")
        )
        coEvery { habitDao.getAllHabitsOnce() } returns listOf(
            HabitEntity(id = 9L, name = "Drop me")
        )

        importer.importFromJson(
            """{}""",
            ImportMode.REPLACE,
            ImportOptions(replaceScope = setOf(ReplaceSection.HABITS_AND_HISTORY))
        )

        coVerify(exactly = 0) { taskDao.deleteById(any()) }
        coVerify { habitDao.delete(match { it.id == 9L }) }
    }

    @Test
    fun importFromJson_replaceWithTasksScopeOnly_preservesHabits() = runBlocking {
        coEvery { taskDao.getAllTasksOnce() } returns listOf(
            com.averycorp.prismtask.data.local.entity.TaskEntity(id = 1L, title = "Drop me")
        )
        coEvery { habitDao.getAllHabitsOnce() } returns listOf(
            HabitEntity(id = 9L, name = "Keep me")
        )

        importer.importFromJson(
            """{}""",
            ImportMode.REPLACE,
            ImportOptions(replaceScope = setOf(ReplaceSection.TASKS_PROJECTS))
        )

        coVerify { taskDao.deleteById(1L) }
        coVerify(exactly = 0) { habitDao.delete(any()) }
    }
}
