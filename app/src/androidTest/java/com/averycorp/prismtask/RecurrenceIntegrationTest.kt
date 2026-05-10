package com.averycorp.prismtask

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.EisenhowerPrefs
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.EisenhowerClassifier
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.data.repository.TaskCompletionRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.RecurrenceType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RecurrenceIntegrationTest {
    private lateinit var database: PrismTaskDatabase
    private lateinit var repository: TaskRepository

    private fun LocalDate.toMillis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setup() {
        database = Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                PrismTaskDatabase::class.java
            ).allowMainThreadQueries()
            .build()

        // Use a real TaskCompletionRepository so the spawned-recurrence-id
        // round-trips through `task_completions` end-to-end. The toggle-
        // uncomplete rollback path reads
        // `getLatestCompletionForTask(id).spawnedRecurrenceId`, which is
        // only populated when `recordCompletion` actually runs against
        // Room. The previous mocked repo silently returned null and made
        // those assertions vacuous.
        val syncTrackerMock = mockk<SyncTracker>(relaxed = true)
        val completionRepository = TaskCompletionRepository(
            taskCompletionDao = database.taskCompletionDao(),
            syncTracker = syncTrackerMock
        )

        repository = TaskRepository(
            transactionRunner = DatabaseTransactionRunner(database),
            taskDao = database.taskDao(),
            tagDao = database.tagDao(),
            syncTracker = syncTrackerMock,
            calendarPushDispatcher = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            taskCompletionRepository = completionRepository,
            eisenhowerClassifier = mockk<EisenhowerClassifier>(relaxed = true),
            userPreferences = mockk<UserPreferencesDataStore> {
                every { eisenhowerFlow } returns flowOf(EisenhowerPrefs(autoClassifyEnabled = false))
            },
            automationEventBus = com.averycorp.prismtask.domain.automation.AutomationEventBus(),
            advancedTuningPreferences = mockk(relaxed = true) {
                every { getLifeCategoryCustomKeywords() } returns flowOf(
                    com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords()
                )
            },
            habitRepositoryProvider = javax.inject.Provider { mockk(relaxed = true) }
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun test_completeRecurringTask_createsNextOccurrence() = runTest {
        val dueDate = LocalDate.of(2025, 1, 6).toMillis()
        val rule = RecurrenceRule(type = RecurrenceType.DAILY)
        val ruleJson = RecurrenceConverter.toJson(rule)

        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "Daily task",
                dueDate = dueDate,
                recurrenceRule = ruleJson
            )
        )

        repository.completeTask(taskId)

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(2, allTasks.size)

        val original = allTasks.find { it.id == taskId }!!
        assertTrue(original.isCompleted)

        val newTask = allTasks.find { it.id != taskId }!!
        assertFalse(newTask.isCompleted)
        assertEquals(LocalDate.of(2025, 1, 7).toMillis(), newTask.dueDate)

        val newRule = RecurrenceConverter.fromJson(newTask.recurrenceRule!!)!!
        assertEquals(1, newRule.occurrenceCount)
    }

    @Test
    fun test_completeRecurringTask_maxOccurrences() = runTest {
        val dueDate = LocalDate.of(2025, 1, 6).toMillis()
        val rule = RecurrenceRule(
            type = RecurrenceType.DAILY,
            maxOccurrences = 3,
            occurrenceCount = 3
        )
        val ruleJson = RecurrenceConverter.toJson(rule)

        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "Limited task",
                dueDate = dueDate,
                recurrenceRule = ruleJson
            )
        )

        repository.completeTask(taskId)

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(1, allTasks.size) // no new task created
        assertTrue(allTasks[0].isCompleted)
    }

    @Test
    fun test_completeNonRecurringTask() = runTest {
        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "One-time task",
                dueDate = LocalDate.of(2025, 1, 6).toMillis()
            )
        )

        repository.completeTask(taskId)

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(1, allTasks.size)
        assertTrue(allTasks[0].isCompleted)
    }

    @Test
    fun test_weeklyRecurrence_multiDay() = runTest {
        val dueDate = LocalDate.of(2025, 1, 6).toMillis() // Monday
        val rule = RecurrenceRule(
            type = RecurrenceType.WEEKLY,
            // Mon, Wed, Fri
            daysOfWeek = listOf(1, 3, 5)
        )
        val ruleJson = RecurrenceConverter.toJson(rule)

        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "MWF task",
                dueDate = dueDate,
                recurrenceRule = ruleJson
            )
        )

        repository.completeTask(taskId)

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(2, allTasks.size)

        val newTask = allTasks.find { !it.isCompleted }!!
        assertEquals(LocalDate.of(2025, 1, 8).toMillis(), newTask.dueDate) // Wednesday
    }

    // Audit: docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md (Item 1).
    @Test
    fun test_completeRecurringTask_isIdempotent() = runTest {
        val rule = RecurrenceRule(type = RecurrenceType.DAILY)
        val ruleJson = RecurrenceConverter.toJson(rule)
        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "Daily idempotent",
                dueDate = LocalDate.of(2025, 1, 6).toMillis(),
                recurrenceRule = ruleJson
            )
        )

        val firstSpawn = repository.completeTask(taskId)
        val secondSpawn = repository.completeTask(taskId)

        assertTrue("first call must spawn", firstSpawn != null)
        assertEquals("second call must be a no-op", null, secondSpawn)

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(
            "double-complete must NOT produce two next-day rows",
            2,
            allTasks.size
        )
    }

    // Audit: docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md (Item 2).
    @Test
    fun test_undoCompletion_rollsBackSpawnedRecurrence() = runTest {
        val rule = RecurrenceRule(type = RecurrenceType.DAILY)
        val ruleJson = RecurrenceConverter.toJson(rule)
        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "Daily undo-redo",
                dueDate = LocalDate.of(2025, 1, 6).toMillis(),
                recurrenceRule = ruleJson
            )
        )

        // Complete → undo → re-complete (snackbar UNDO + redo flow).
        val firstSpawn = repository.completeTask(taskId)
        repository.uncompleteTask(taskId, firstSpawn)
        val secondSpawn = repository.completeTask(taskId)

        assertTrue("first complete must spawn", firstSpawn != null)
        assertTrue("second complete must spawn after undo", secondSpawn != null)
        assertFalse(
            "the spawn ids must differ — undo deleted the first child",
            firstSpawn == secondSpawn
        )

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(
            "undo + redo must NOT leave a stale spawned child behind",
            2,
            allTasks.size
        )
        val original = allTasks.single { it.id == taskId }
        val onlyChild = allTasks.single { it.id != taskId }
        assertTrue(original.isCompleted)
        assertEquals(secondSpawn, onlyChild.id)
    }

    // Audit: docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md (Item 2
    // residual). Toggle-style uncomplete (checkbox, no Undo snackbar) reads
    // the latest completion entry's `spawned_recurrence_id` and rolls the
    // spawn back too. Without this, complete → uncomplete → re-complete on
    // the same daily-recurring row leaves the first spawn behind and
    // duplicates the next-day row on the second complete.
    @Test
    fun test_uncompleteWithoutSpawnedId_rollsBackViaCompletionLink() = runTest {
        val rule = RecurrenceRule(type = RecurrenceType.DAILY)
        val ruleJson = RecurrenceConverter.toJson(rule)
        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "Daily toggle",
                dueDate = LocalDate.of(2025, 1, 6).toMillis(),
                recurrenceRule = ruleJson
            )
        )

        repository.completeTask(taskId)
        repository.uncompleteTask(taskId)

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(
            "toggle-uncomplete must roll back the spawned next-instance",
            1,
            allTasks.size
        )
        assertFalse(allTasks.single { it.id == taskId }.isCompleted)
    }

    // Toggle complete → uncomplete → re-complete is the historical
    // duplication path that the residual fix targets. After this fix,
    // re-complete spawns a single fresh next-instance, not two.
    @Test
    fun test_toggleCompleteUncompleteRecomplete_doesNotDuplicate() = runTest {
        val rule = RecurrenceRule(type = RecurrenceType.DAILY)
        val ruleJson = RecurrenceConverter.toJson(rule)
        val taskId = database.taskDao().insert(
            TaskEntity(
                title = "Daily toggle-redo",
                dueDate = LocalDate.of(2025, 1, 6).toMillis(),
                recurrenceRule = ruleJson
            )
        )

        repository.completeTask(taskId)
        repository.uncompleteTask(taskId)
        repository.completeTask(taskId)

        val allTasks = database.taskDao().getAllTasks().first()
        assertEquals(
            "toggle-uncomplete + recomplete must NOT leave a duplicate next-instance",
            2,
            allTasks.size
        )
        val parent = allTasks.single { it.id == taskId }
        val child = allTasks.single { it.id != taskId }
        assertTrue(parent.isCompleted)
        assertFalse(child.isCompleted)
    }
}
