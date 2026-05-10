package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.HabitLogDao
import com.averycorp.prismtask.data.local.dao.LeisureDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.preferences.ArchivePreferences
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.LeisurePreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.TabPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [DataImporter]. The importer touches a large number of DAOs and
 * preferences, so collaborators are all relaxed-mocked and only the DAOs the
 * test exercises are stubbed with meaningful behavior. Insert calls are
 * captured via MockK slots to verify the shape of what the importer would
 * persist.
 */
class DataImporterTest {
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
        object : com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner(
            mockk(relaxed = true)
        ) {
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
    private lateinit var taskCompletionDao: com.averycorp.prismtask.data.local.dao.TaskCompletionDao
    private lateinit var a11yPreferences: com.averycorp.prismtask.data.preferences.A11yPreferences
    private lateinit var voicePreferences: com.averycorp.prismtask.data.preferences.VoicePreferences
    private lateinit var timerPreferences: com.averycorp.prismtask.data.preferences.TimerPreferences
    private lateinit var notificationPreferences: com.averycorp.prismtask.data.preferences.NotificationPreferences
    private lateinit var ndPreferencesDataStore: com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
    private lateinit var dailyEssentialsPreferences: com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
    private lateinit var morningCheckInPreferences: com.averycorp.prismtask.data.preferences.MorningCheckInPreferences
    private lateinit var calendarSyncPreferences: com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
    private lateinit var templatePreferences: com.averycorp.prismtask.data.preferences.TemplatePreferences
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

        // Default: no pre-existing data in any DAO.
        stubEmptyDaos()

        importer = DataImporter(
            taskDao,
            projectDao,
            tagDao,
            habitDao,
            habitCompletionDao,
            taskCompletionDao,
            habitLogDao,
            leisureDao,
            selfCareDao,
            schoolworkDao,
            // medicationDao
            mockk(relaxed = true),
            // medicationDoseDao
            mockk(relaxed = true),
            // medicationSlotDao
            mockk(relaxed = true),
            // medicationTierStateDao
            mockk(relaxed = true),
            transactionRunner,
            themePreferences,
            archivePreferences,
            dashboardPreferences,
            tabPreferences,
            taskBehaviorPreferences,
            habitListPreferences,
            leisurePreferences,
            medicationPreferences,
            userPreferencesDataStore,
            a11yPreferences,
            voicePreferences,
            timerPreferences,
            notificationPreferences,
            ndPreferencesDataStore,
            dailyEssentialsPreferences,
            morningCheckInPreferences,
            calendarSyncPreferences,
            templatePreferences,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
    }

    private fun stubEmptyDaos() {
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
    }

    // ---------------------------------------------------------------------
    // Basic input handling
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_emptyObject_returnsEmptyResult() = runBlocking {
        val result = importer.importFromJson("{}", ImportMode.MERGE)

        assertEquals(0, result.tasksImported)
        assertEquals(0, result.projectsImported)
        assertEquals(0, result.tagsImported)
        assertEquals(0, result.habitsImported)
        assertEquals(0, result.duplicatesSkipped)
        assertTrue("Empty JSON should not produce errors", result.errors.isEmpty())
    }

    @Test
    fun importFromJson_malformedJson_returnsErrorWithoutCrashing() = runBlocking {
        val result = importer.importFromJson("this is not json", ImportMode.MERGE)

        assertTrue("Malformed JSON should surface at least one error", result.errors.isNotEmpty())
        assertEquals(0, result.tasksImported)
    }

    // ---------------------------------------------------------------------
    // Happy-path imports
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_projectsAreInsertedAndCountedInMergeMode() = runBlocking {
        val insertedIds = mutableListOf<Long>()
        val projectSlot = slot<ProjectEntity>()
        coEvery { projectDao.insert(capture(projectSlot)) } answers {
            val next = 100L + insertedIds.size
            insertedIds.add(next)
            next
        }

        val json =
            """
            { "version": 3, "projects": [
                { "name": "Home", "color": "#FF0000", "icon": "🏠" },
                { "name": "Work", "color": "#00FF00", "icon": "💼" }
            ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(2, result.projectsImported)
        coVerify(exactly = 2) { projectDao.insert(any()) }
    }

    @Test
    fun importFromJson_duplicateProjectsAreSkippedInMergeMode() = runBlocking {
        coEvery { projectDao.getAllProjectsOnce() } returns listOf(
            ProjectEntity(id = 1L, name = "Home")
        )

        val json =
            """
            { "projects": [
                { "name": "Home", "color": "#FF0000" },
                { "name": "work", "color": "#00FF00" }
            ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.projectsImported)
        assertEquals(1, result.duplicatesSkipped)
        coVerify(exactly = 1) { projectDao.insert(any()) }
    }

    @Test
    fun importFromJson_tagsAreInsertedInMergeMode() = runBlocking {
        val json =
            """
            { "tags": [
                { "name": "urgent", "color": "#FF0000" },
                { "name": "later", "color": "#999999" }
            ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(2, result.tagsImported)
        coVerify(exactly = 2) { tagDao.insert(any()) }
    }

    @Test
    fun importFromJson_tasksWithoutTitleAreSkipped() = runBlocking {
        val json =
            """
            { "tasks": [
                { "title": "Valid task" },
                { "title": "" },
                { "description": "no title" }
            ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.tasksImported)
        coVerify(exactly = 1) { taskDao.insert(any()) }
    }

    @Test
    fun importFromJson_tasksResolveProjectIdFromProjectName() = runBlocking {
        // Seed a pre-existing project so the FK resolver has something to hit.
        coEvery { projectDao.getAllProjectsOnce() } returns listOf(
            ProjectEntity(id = 42L, name = "Home")
        )
        val inserted = slot<TaskEntity>()
        coEvery { taskDao.insert(capture(inserted)) } returns 1L

        val json =
            """
            { "tasks": [
                { "title": "Mow lawn", "_projectName": "Home" }
            ] }
            """.trimIndent()

        importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(42L, inserted.captured.projectId)
        assertEquals("Mow lawn", inserted.captured.title)
        assertEquals(0L, inserted.captured.id) // id reset on insert
    }

    @Test
    fun importFromJson_tasksAssignKnownTagsViaNameLookup() = runBlocking {
        coEvery { tagDao.getAllTagsOnce() } returns listOf(
            TagEntity(id = 7L, name = "urgent"),
            TagEntity(id = 8L, name = "home")
        )
        coEvery { taskDao.insert(any()) } returns 55L
        val crossRefSlot = slot<TaskTagCrossRef>()
        coEvery { tagDao.addTagToTask(capture(crossRefSlot)) } just Runs

        val json =
            """
            { "tasks": [
                { "title": "Pay bills", "_tagNames": ["urgent", "unknown"] }
            ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.tasksImported)
        // The known tag should be attached; the unknown one is silently dropped.
        coVerify(exactly = 1) { tagDao.addTagToTask(match { it.taskId == 55L && it.tagId == 7L }) }
    }

    @Test
    fun importFromJson_habitsAreInsertedAndHabitCompletionsAreResolvedByName() = runBlocking {
        val habitInsertedSlot = slot<HabitEntity>()
        coEvery { habitDao.insert(capture(habitInsertedSlot)) } returns 99L

        val json =
            """
            { "habits": [
                { "name": "Meditate", "targetFrequency": 1 }
              ],
              "habitCompletions": [
                { "_habitName": "Meditate", "completedDate": 1700000000000 }
              ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.habitsImported)
        assertEquals(1, result.habitCompletionsImported)
        assertEquals("Meditate", habitInsertedSlot.captured.name)
        coVerify {
            habitCompletionDao.insert(
                match {
                    it.habitId == 99L && it.completedDate == 1_700_000_000_000L
                }
            )
        }
    }

    // ---------------------------------------------------------------------
    // Replace mode
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_replaceMode_deletesExistingDataBeforeInsert() = runBlocking {
        val existing = listOf(
            TaskEntity(id = 1L, title = "Old task"),
            TaskEntity(id = 2L, title = "Old task 2")
        )
        coEvery { taskDao.getAllTasksOnce() } returns existing andThen emptyList()
        coEvery { projectDao.getAllProjectsOnce() } returns listOf(
            ProjectEntity(id = 10L, name = "Gone")
        ) andThen emptyList()
        coEvery { tagDao.getAllTagsOnce() } returns listOf(
            TagEntity(id = 20L, name = "Stale")
        ) andThen emptyList()
        coEvery { habitDao.getAllHabitsOnce() } returns listOf(
            HabitEntity(id = 30L, name = "Old habit")
        ) andThen emptyList()

        val json =
            """
            { "tasks": [ { "title": "Fresh" } ] }
            """.trimIndent()

        importer.importFromJson(json, ImportMode.REPLACE)

        coVerify { taskDao.deleteById(1L) }
        coVerify { taskDao.deleteById(2L) }
        coVerify { projectDao.delete(match { it.id == 10L }) }
        coVerify { tagDao.delete(match { it.id == 20L }) }
        coVerify { habitDao.delete(match { it.id == 30L }) }
        // And the fresh task should still land in the DAO.
        coVerify { taskDao.insert(match { it.title == "Fresh" }) }
    }

    // ---------------------------------------------------------------------
    // Dedupe
    // ---------------------------------------------------------------------

    @Test
    fun importFromJson_tasksWithSameTitleAndCreatedAtAreSkipped() = runBlocking {
        val existingCreatedAt = 1_700_000_000_000L
        coEvery { taskDao.getAllTasksOnce() } returns listOf(
            TaskEntity(id = 1L, title = "Exists", createdAt = existingCreatedAt)
        )

        val json =
            """
            { "tasks": [
                { "title": "Exists", "createdAt": $existingCreatedAt },
                { "title": "Fresh",  "createdAt": $existingCreatedAt }
            ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.tasksImported)
        assertEquals(1, result.duplicatesSkipped)
    }

    @Test
    fun importFromJson_duplicateHabitCompletionsByKeyAreSkipped() = runBlocking {
        coEvery { habitCompletionDao.getAllCompletionsOnce() } returns listOf(
            com.averycorp.prismtask.data.local.entity.HabitCompletionEntity(
                id = 1L,
                habitId = 99L,
                completedDate = 1_700_000_000_000L
            )
        )
        coEvery { habitDao.insert(any()) } returns 99L

        val json =
            """
            { "habits": [
                { "name": "Meditate" }
              ],
              "habitCompletions": [
                { "_habitName": "Meditate", "completedDate": 1700000000000 },
                { "_habitName": "Meditate", "completedDate": 1800000000000 }
              ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(1, result.habitCompletionsImported)
        assertEquals(1, result.duplicatesSkipped)
    }

    @Test
    fun importFromJson_missingHabitForCompletionIsSilentlyDropped() = runBlocking {
        val json =
            """
            { "habitCompletions": [
                { "_habitName": "GhostHabit", "completedDate": 1700000000000 }
            ] }
            """.trimIndent()

        val result = importer.importFromJson(json, ImportMode.MERGE)

        assertEquals(0, result.habitCompletionsImported)
        coVerify(exactly = 0) { habitCompletionDao.insert(any()) }
    }
}
