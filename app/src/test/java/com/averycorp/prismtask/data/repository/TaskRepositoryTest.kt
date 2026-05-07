package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.calendar.CalendarPushDispatcher
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.local.entity.TaskWithTags
import com.averycorp.prismtask.data.preferences.EisenhowerPrefs
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.EisenhowerClassifier
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.notifications.ReminderScheduler
import com.averycorp.prismtask.widget.WidgetUpdateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * End-to-end style tests for [TaskRepository] using in-memory fake DAOs +
 * relaxed mocks for the side-effect collaborators (SyncTracker,
 * CalendarPushDispatcher, ReminderScheduler). We exercise the repository through
 * its public API and assert on the DAO state to make sure the right rows
 * landed in the right tables.
 */
class TaskRepositoryTest {
    private lateinit var taskDao: FakeTaskDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var syncTracker: SyncTracker
    private lateinit var calendarPushDispatcher: CalendarPushDispatcher
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var widgetUpdateManager: WidgetUpdateManager
    private lateinit var taskCompletionRepository: TaskCompletionRepository
    private lateinit var eisenhowerClassifier: EisenhowerClassifier
    private lateinit var userPreferences: UserPreferencesDataStore
    private lateinit var advancedTuningPreferences:
        com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
    private lateinit var automationEventBus:
        com.averycorp.prismtask.domain.automation.AutomationEventBus
    private lateinit var repo: TaskRepository

    @Before
    fun setUp() {
        taskDao = FakeTaskDao()
        tagDao = FakeTagDao()
        syncTracker = mockk(relaxed = true)
        calendarPushDispatcher = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        taskCompletionRepository = mockk(relaxed = true)
        eisenhowerClassifier = mockk(relaxed = true)
        // Default to auto-classify OFF for existing tests so the fire-and-forget
        // classifier doesn't race them. Eisenhower-specific tests flip it on.
        userPreferences = mockk {
            every { eisenhowerFlow } returns flowOf(EisenhowerPrefs(autoClassifyEnabled = false))
        }
        // Default custom keywords are blank — tests that exercise the
        // life-category resolver fall back to DEFAULT_KEYWORDS, matching
        // the behavior before keyword wiring was added.
        advancedTuningPreferences = mockk {
            every { getLifeCategoryCustomKeywords() } returns flowOf(
                com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords()
            )
        }
        coEvery { eisenhowerClassifier.classify(any()) } returns
            Result.failure(IllegalStateException("stub"))
        automationEventBus = com.averycorp.prismtask.domain.automation.AutomationEventBus()
        repo =
            TaskRepository(
                inlineTransactionRunner(),
                taskDao,
                tagDao,
                syncTracker,
                calendarPushDispatcher,
                reminderScheduler,
                widgetUpdateManager,
                taskCompletionRepository,
                eisenhowerClassifier,
                userPreferences,
                automationEventBus,
                advancedTuningPreferences
            )
    }

    private fun inlineTransactionRunner(): DatabaseTransactionRunner =
        object : DatabaseTransactionRunner(mockk(relaxed = true)) {
            override suspend fun <R> withTransaction(block: suspend () -> R): R = block()
        }

    // ---------------------------------------------------------------------
    // Create / update / delete
    // ---------------------------------------------------------------------

    @Test
    fun addTask_insertsTaskWithProvidedFieldsAndTracksCreate() = runBlocking {
        val id = repo.addTask(
            title = "Write report",
            description = "Q4 retrospective",
            dueDate = 1_700_000_000_000L,
            priority = 3,
            projectId = 7L
        )

        val task = taskDao.tasks.single { it.id == id }
        assertEquals("Write report", task.title)
        assertEquals("Q4 retrospective", task.description)
        assertEquals(1_700_000_000_000L, task.dueDate)
        assertEquals(3, task.priority)
        assertEquals(7L, task.projectId)
        assertFalse(task.isCompleted)
        coVerify { syncTracker.trackCreate(id, "task") }
    }

    /**
     * Regression for the AVD pair test Stage 7 (May 6) silent failure.
     * `addTask()` is the path used by every primary user-facing creation
     * surface (TaskList QuickAdd row, Today plan-for-today, AddEditTask
     * save-new, MultiCreate, Onboarding, Conversation extract). Without
     * this emit, automation rules with `TaskCreated` triggers silently
     * never fire for those paths — see
     * `docs/audits/D_AUTOMATION_ACTION_SILENT_FAILURE_AUDIT.md` § A1.
     */
    @Test
    fun addTask_emitsTaskCreatedOntoAutomationEventBus() = kotlinx.coroutines.test.runTest {
        val collected =
            mutableListOf<com.averycorp.prismtask.domain.automation.AutomationEvent>()
        val collector = this.launch {
            automationEventBus.events.collect { collected += it }
        }
        // Yield once so the collector is subscribed before we emit.
        kotlinx.coroutines.yield()

        val id = repo.addTask(title = "From quick-add")

        kotlinx.coroutines.yield()
        collector.cancel()

        val taskCreated = collected.filterIsInstance<
            com.averycorp.prismtask.domain.automation.AutomationEvent.TaskCreated
            >()
        assertEquals(1, taskCreated.size)
        assertEquals(id, taskCreated.first().taskId)
    }

    @Test
    fun updateTask_persistsFieldsAndBumpsUpdatedAtAndTracksUpdate() = runBlocking {
        val seededId = taskDao.insert(taskFixture(title = "Old title", updatedAt = 1L))
        val before = taskDao.tasks.single { it.id == seededId }

        repo.updateTask(before.copy(title = "New title"))

        val after = taskDao.tasks.single { it.id == seededId }
        assertEquals("New title", after.title)
        assertTrue("updatedAt should advance", after.updatedAt > before.updatedAt)
        coVerify { syncTracker.trackUpdate(seededId, "task") }
    }

    @Test
    fun updateTask_withDueDateAndReminderOffset_schedulesReminder() = runBlocking {
        val futureDueDate = System.currentTimeMillis() + (24L * 60 * 60 * 1000)
        val seededId = taskDao.insert(
            taskFixture(title = "Call dentist", dueDate = futureDueDate, reminderOffset = 60_000L)
        )
        val seeded = taskDao.tasks.single { it.id == seededId }

        repo.updateTask(seeded.copy(description = "Annual checkup"))

        coVerify {
            reminderScheduler.scheduleReminder(
                taskId = seededId,
                taskTitle = "Call dentist",
                taskDescription = "Annual checkup",
                dueDate = futureDueDate,
                reminderOffset = 60_000L
            )
        }
    }

    @Test
    fun updateTask_withDueTime_combinesDateAndTimeBeforeScheduling() = runBlocking {
        // dueDate is midnight of the due day; dueTime carries the HH:mm.
        // The scheduler must receive the combined instant (dueDate at HH:mm),
        // not raw midnight — otherwise a same-day reminder is computed as a
        // negative offset from yesterday and silently dropped.
        val zone = TimeZone.getDefault()
        val dueDate = Calendar.getInstance(zone).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dueTime = Calendar.getInstance(zone).apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val expectedCombined = ReminderScheduler.combineDateAndTime(dueDate, dueTime)

        val seededId = taskDao.insert(
            taskFixture(
                title = "Call dentist",
                dueDate = dueDate,
                dueTime = dueTime,
                reminderOffset = 10L * 60 * 1000
            )
        )
        val seeded = taskDao.tasks.single { it.id == seededId }

        repo.updateTask(seeded.copy(description = "Annual checkup"))

        coVerify {
            reminderScheduler.scheduleReminder(
                taskId = seededId,
                taskTitle = "Call dentist",
                taskDescription = "Annual checkup",
                dueDate = expectedCombined,
                reminderOffset = 10L * 60 * 1000
            )
        }
    }

    @Test
    fun deleteTask_removesTaskAndTracksDelete() = runBlocking {
        val id = taskDao.insert(taskFixture(title = "Temp"))

        repo.deleteTask(id)

        assertTrue(taskDao.tasks.none { it.id == id })
        coVerify { syncTracker.trackDelete(id, "task") }
        coVerify { calendarPushDispatcher.enqueueDeleteTaskEvent(id) }
    }

    // ---------------------------------------------------------------------
    // Completion
    // ---------------------------------------------------------------------

    @Test
    fun completeTask_setsIsCompletedAndCompletedAt() = runBlocking {
        val id = taskDao.insert(taskFixture(title = "Finish it"))

        repo.completeTask(id)

        val done = taskDao.tasks.single { it.id == id }
        assertTrue(done.isCompleted)
        assertNotNull(done.completedAt)
    }

    @Test
    fun completeTask_withDailyRecurrence_insertsNextOccurrence() = runBlocking {
        val dueDate = 1_700_000_000_000L
        val id = taskDao.insert(
            taskFixture(
                title = "Stand-up",
                dueDate = dueDate,
                recurrenceRule = """{"type":"DAILY","interval":1}"""
            )
        )

        repo.completeTask(id)

        // Original should be marked complete.
        val original = taskDao.tasks.single { it.id == id }
        assertTrue(original.isCompleted)

        // A new uncompleted task should exist for the next occurrence.
        val next = taskDao.tasks.single { it.title == "Stand-up" && it.id != id }
        assertFalse(next.isCompleted)
        assertNotNull(next.dueDate)
        assertTrue(
            "Next due date should be after the original",
            (next.dueDate ?: 0L) > dueDate
        )
    }

    @Test
    fun uncompleteTask_clearsCompletedState() = runBlocking {
        val id = taskDao.insert(taskFixture(title = "Rework", isCompleted = true, completedAt = 999L))

        repo.uncompleteTask(id)

        val reverted = taskDao.tasks.single { it.id == id }
        assertFalse(reverted.isCompleted)
        assertNull(reverted.completedAt)
    }

    // Audit: docs/audits/COULDNT_UPDATE_TASK_AUDIT.md (Item 2). A re-tap of an
    // already-completed task must not enqueue calendar deletes / sync updates /
    // widget refreshes — those side effects are gated on the transaction
    // actually flipping is_completed = 1.
    @Test
    fun completeTask_alreadyCompleted_skipsPostTransactionSideEffects() = runBlocking {
        val id = taskDao.insert(
            taskFixture(title = "Already done", isCompleted = true, completedAt = 555L)
        )

        val result = repo.completeTask(id)

        assertNull("No spawn for already-completed task", result)
        io.mockk.verify(exactly = 0) { calendarPushDispatcher.enqueueDeleteTaskEvent(id) }
        coVerify(exactly = 0) { syncTracker.trackUpdate(id, "task") }
        coVerify(exactly = 0) { widgetUpdateManager.updateTaskWidgets() }
    }

    // Audit: docs/audits/COULDNT_UPDATE_TASK_AUDIT.md (Item 2). Symmetric guard
    // for the toggle-uncomplete path: an uncomplete on a never-completed row
    // must skip side effects too.
    @Test
    fun uncompleteTask_neverCompleted_skipsSideEffects() = runBlocking {
        val id = taskDao.insert(taskFixture(title = "Fresh task", isCompleted = false))

        repo.uncompleteTask(id)

        coVerify(exactly = 0) { syncTracker.trackUpdate(id, "task") }
        io.mockk.verify(exactly = 0) { calendarPushDispatcher.enqueuePushTask(id) }
        coVerify(exactly = 0) { widgetUpdateManager.updateTaskWidgets() }
    }

    // ---------------------------------------------------------------------
    // Reschedule / reminders
    // ---------------------------------------------------------------------

    @Test
    fun rescheduleTask_updatesDueDateAndReschedulesReminder() = runBlocking {
        val id = taskDao.insert(
            taskFixture(title = "Pay bills", dueDate = 100L, reminderOffset = 60_000L)
        )

        repo.rescheduleTask(id, newDueDate = 500L)

        val updated = taskDao.tasks.single { it.id == id }
        assertEquals(500L, updated.dueDate)
        coVerify {
            reminderScheduler.scheduleReminder(
                taskId = id,
                taskTitle = "Pay bills",
                taskDescription = null,
                dueDate = 500L,
                reminderOffset = 60_000L
            )
        }
    }

    @Test
    fun rescheduleTask_withNullDate_cancelsReminder() = runBlocking {
        val id = taskDao.insert(
            taskFixture(title = "Clear date", dueDate = 100L, reminderOffset = 60_000L)
        )

        repo.rescheduleTask(id, newDueDate = null)

        assertNull(taskDao.tasks.single { it.id == id }.dueDate)
        coVerify { reminderScheduler.cancelReminder(id) }
    }

    @Test
    fun rescheduleTask_unknownTaskId_doesNothing() = runBlocking {
        repo.rescheduleTask(taskId = 9999L, newDueDate = 1L)
        // No crash, no DAO changes.
        assertTrue(taskDao.tasks.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Archive / unarchive / plan-for-today
    // ---------------------------------------------------------------------

    @Test
    fun archiveAndUnarchive_togglesArchivedAt() = runBlocking {
        val id = taskDao.insert(taskFixture(title = "Archivable"))
        repo.archiveTask(id)
        assertNotNull(taskDao.tasks.single { it.id == id }.archivedAt)

        repo.unarchiveTask(id)
        assertNull(taskDao.tasks.single { it.id == id }.archivedAt)
    }

    @Test
    fun planTaskForToday_setsPlannedDateAndTracksUpdate() = runBlocking {
        val id = taskDao.insert(taskFixture(title = "Pin to today"))
        repo.planTaskForToday(id)
        assertNotNull(taskDao.tasks.single { it.id == id }.plannedDate)
        coVerify { syncTracker.trackUpdate(id, "task") }
    }

    // ---------------------------------------------------------------------
    // Flagging
    // ---------------------------------------------------------------------

    @Test
    fun toggleFlag_flipsFlaggedStateAndReturnsNewValue() = runBlocking {
        val id = taskDao.insert(taskFixture(title = "Flag me", isFlagged = false))
        val after = repo.toggleFlag(id)
        assertEquals(true, after)
        assertTrue(taskDao.tasks.single { it.id == id }.isFlagged)

        val afterSecond = repo.toggleFlag(id)
        assertEquals(false, afterSecond)
        assertFalse(taskDao.tasks.single { it.id == id }.isFlagged)
    }

    @Test
    fun toggleFlag_unknownTask_returnsNull() = runBlocking {
        assertNull(repo.toggleFlag(9999L))
    }

    // ---------------------------------------------------------------------
    // Batch operations
    // ---------------------------------------------------------------------

    @Test
    fun batchUpdatePriority_updatesEveryTaskAndSkipsEmptyList() = runBlocking {
        val id1 = taskDao.insert(taskFixture(title = "A", priority = 0))
        val id2 = taskDao.insert(taskFixture(title = "B", priority = 1))

        repo.batchUpdatePriority(listOf(id1, id2), priority = 4)
        assertEquals(4, taskDao.tasks.single { it.id == id1 }.priority)
        assertEquals(4, taskDao.tasks.single { it.id == id2 }.priority)

        // Empty list — should bail out.
        repo.batchUpdatePriority(emptyList(), priority = 0)
    }

    @Test
    fun batchMoveToProject_updatesProjectIdOnEveryTask() = runBlocking {
        val id1 = taskDao.insert(taskFixture(title = "A", projectId = null))
        val id2 = taskDao.insert(taskFixture(title = "B", projectId = 5L))

        repo.batchMoveToProject(listOf(id1, id2), newProjectId = 9L)
        assertEquals(9L, taskDao.tasks.single { it.id == id1 }.projectId)
        assertEquals(9L, taskDao.tasks.single { it.id == id2 }.projectId)
    }

    // ---------------------------------------------------------------------
    // Subtasks
    // ---------------------------------------------------------------------

    @Test
    fun addSubtask_setsParentAndInheritsProject() = runBlocking {
        val parentId = taskDao.insert(taskFixture(title = "Project X", projectId = 11L))

        val subId = repo.addSubtask(title = "Step 1", parentTaskId = parentId)

        val sub = taskDao.tasks.single { it.id == subId }
        assertEquals(parentId, sub.parentTaskId)
        assertEquals(11L, sub.projectId)
        assertEquals(0, sub.sortOrder)
    }

    // ---------------------------------------------------------------------
    // Duplication (exercises companion helpers through the public API)
    // ---------------------------------------------------------------------

    @Test
    fun duplicateTask_copiesFieldsAndPrefixesTitle() = runBlocking {
        val original = taskDao.insert(
            taskFixture(
                title = "Launch",
                description = "Shippable",
                priority = 4,
                projectId = 2L
            )
        )

        val duplicateId = repo.duplicateTask(original)
        val dup = taskDao.tasks.single { it.id == duplicateId }

        assertEquals("Copy of Launch", dup.title)
        assertEquals("Shippable", dup.description)
        assertEquals(4, dup.priority)
        assertEquals(2L, dup.projectId)
        assertFalse(dup.isCompleted)
        assertNull(dup.dueDate)
    }

    @Test
    fun duplicateTask_unknownId_returnsNegativeOne() = runBlocking {
        assertEquals(-1L, repo.duplicateTask(9999L))
    }

    // ---------------------------------------------------------------------
    // Eisenhower auto-classification
    // ---------------------------------------------------------------------

    @Test
    fun addTask_isInitiallyUnclassified() = runBlocking {
        // Auto-classify off (setUp default): new task lands with a null quadrant
        // and userOverrodeQuadrant=false — i.e. UNCLASSIFIED per the domain enum.
        val id = repo.addTask(title = "New task")

        val stored = taskDao.tasks.first { it.id == id }
        assertNull(stored.eisenhowerQuadrant)
        assertFalse(stored.userOverrodeQuadrant)
    }

    @Test
    fun addTask_autoClassifyOn_updatesQuadrantOnSuccess() = runBlocking {
        enableAutoClassify()
        coEvery { eisenhowerClassifier.classify(any()) } returns Result.success(
            EisenhowerClassifier.Classification(
                quadrant = com.averycorp.prismtask.domain.model.EisenhowerQuadrant.URGENT_IMPORTANT,
                reason = "Due soon"
            )
        )

        val id = repo.addTask(title = "Urgent")
        // Background launches on Dispatchers.IO; yield long enough for it to
        // finish against the in-memory DAO.
        kotlinx.coroutines.delay(50)

        val stored = taskDao.tasks.first { it.id == id }
        assertEquals("Q1", stored.eisenhowerQuadrant)
        assertEquals("Due soon", stored.eisenhowerReason)
    }

    @Test
    fun addTask_autoClassifyOn_leavesUnclassifiedOnFailure() = runBlocking {
        enableAutoClassify()
        coEvery { eisenhowerClassifier.classify(any()) } returns Result.failure(IllegalStateException("offline"))

        val id = repo.addTask(title = "Offline task")
        kotlinx.coroutines.delay(50)

        val stored = taskDao.tasks.first { it.id == id }
        assertNull(stored.eisenhowerQuadrant)
    }

    @Test
    fun setQuadrantManual_marksUserOverrodeTrue() = runBlocking {
        val id = repo.addTask(title = "Manual")

        repo.setQuadrantManual(
            taskId = id,
            quadrant = com.averycorp.prismtask.domain.model.EisenhowerQuadrant.NOT_URGENT_IMPORTANT
        )

        val stored = taskDao.tasks.first { it.id == id }
        assertEquals("Q2", stored.eisenhowerQuadrant)
        assertTrue(stored.userOverrodeQuadrant)
        assertEquals("Manually moved", stored.eisenhowerReason)
    }

    @Test
    fun classifyInBackground_skipsWhenOverrideStillSet() = runBlocking {
        // Direct DAO-level proof that the SQL guard holds: with
        // userOverrodeQuadrant=1, updateEisenhowerQuadrantIfNotOverridden is
        // a no-op. TaskRepository.classifyInBackground relies on this.
        taskDao.tasks.add(
            taskFixture(id = 77L, title = "pinned")
                .copy(
                    eisenhowerQuadrant = "Q4",
                    userOverrodeQuadrant = true
                )
        )

        val rows = taskDao.updateEisenhowerQuadrantIfNotOverridden(
            id = 77L,
            quadrant = "Q1",
            reason = "AI says Q1"
        )

        assertEquals(0, rows)
        val stored = taskDao.tasks.first { it.id == 77L }
        assertEquals("Q4", stored.eisenhowerQuadrant)
        assertTrue(stored.userOverrodeQuadrant)
    }

    @Test
    fun reclassify_clearsOverrideAndFiresClassify() = runBlocking {
        // Explicit reclassify bypasses the autoClassifyEnabled preference
        // and runs the classifier inline so the caller can surface
        // success/failure to the user — no enableAutoClassify() needed.
        coEvery { eisenhowerClassifier.classify(any()) } returns Result.success(
            EisenhowerClassifier.Classification(
                quadrant = com.averycorp.prismtask.domain.model.EisenhowerQuadrant.NOT_URGENT_NOT_IMPORTANT,
                reason = "Low stakes"
            )
        )
        taskDao.tasks.add(
            taskFixture(id = 55L, title = "maybe")
                .copy(eisenhowerQuadrant = "Q1", userOverrodeQuadrant = true)
        )

        val result = repo.reclassify(55L)

        assertTrue(result.isSuccess)
        val stored = taskDao.tasks.first { it.id == 55L }
        assertFalse(stored.userOverrodeQuadrant)
        assertEquals("Q4", stored.eisenhowerQuadrant)
        coVerify { eisenhowerClassifier.classify(any()) }
    }

    @Test
    fun reclassify_propagatesClassifierFailure() = runBlocking {
        coEvery { eisenhowerClassifier.classify(any()) } returns
            Result.failure(IllegalStateException("Backend offline"))
        taskDao.tasks.add(
            taskFixture(id = 56L, title = "AI down")
                .copy(eisenhowerQuadrant = "Q1", userOverrodeQuadrant = true)
        )

        val result = repo.reclassify(56L)

        assertTrue(result.isFailure)
        // Manual override is still cleared (the user's intent was honored)
        // even though the AI call failed — the next successful classify
        // can land cleanly.
        val stored = taskDao.tasks.first { it.id == 56L }
        assertFalse(stored.userOverrodeQuadrant)
    }

    @Test
    fun classify_skipsWhenPreferenceDisabled() = runBlocking {
        // Default setUp already disables auto-classify.
        repo.addTask(title = "Nobody classify me")
        kotlinx.coroutines.delay(50)
        coVerify(exactly = 0) { eisenhowerClassifier.classify(any()) }
    }

    private fun enableAutoClassify() {
        every { userPreferences.eisenhowerFlow } returns flowOf(
            com.averycorp.prismtask.data.preferences.EisenhowerPrefs(autoClassifyEnabled = true)
        )
    }

    // ---------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------

    private fun taskFixture(
        id: Long = 0,
        title: String = "Task",
        description: String? = null,
        dueDate: Long? = null,
        dueTime: Long? = null,
        priority: Int = 0,
        projectId: Long? = null,
        parentTaskId: Long? = null,
        isCompleted: Boolean = false,
        completedAt: Long? = null,
        recurrenceRule: String? = null,
        reminderOffset: Long? = null,
        isFlagged: Boolean = false,
        updatedAt: Long = 0L
    ) = TaskEntity(
        id = id,
        title = title,
        description = description,
        dueDate = dueDate,
        dueTime = dueTime,
        priority = priority,
        projectId = projectId,
        parentTaskId = parentTaskId,
        isCompleted = isCompleted,
        completedAt = completedAt,
        recurrenceRule = recurrenceRule,
        reminderOffset = reminderOffset,
        isFlagged = isFlagged,
        createdAt = 0L,
        updatedAt = updatedAt
    )

    /** Minimal in-memory [TaskDao] for the repository methods we exercise. */
    private class FakeTaskDao : TaskDao {
        val tasks = mutableListOf<TaskEntity>()
        private var nextId = 1L

        override suspend fun insert(task: TaskEntity): Long {
            val id = if (task.id == 0L) nextId++ else task.id.also { nextId = maxOf(nextId, it + 1) }
            tasks.removeAll { it.id == id }
            tasks.add(task.copy(id = id))
            return id
        }

        override suspend fun update(task: TaskEntity) {
            val idx = tasks.indexOfFirst { it.id == task.id }
            if (idx >= 0) tasks[idx] = task
        }

        override suspend fun delete(task: TaskEntity) {
            tasks.removeAll { it.id == task.id }
        }

        override suspend fun deleteById(id: Long) {
            tasks.removeAll { it.id == id }
        }

        override suspend fun getTasksInHorizonOnce(
            startMillis: Long,
            endMillis: Long
        ): List<TaskEntity> = emptyList()

        override suspend fun getScheduledTasksInHorizonOnce(
            startMillis: Long,
            endMillis: Long
        ): List<TaskEntity> = emptyList()

        override fun getTaskById(id: Long): Flow<TaskEntity?> =
            flowOf(tasks.firstOrNull { it.id == id })

        override suspend fun getTaskByIdOnce(id: Long): TaskEntity? =
            tasks.firstOrNull { it.id == id }

        override suspend fun getTasksForPhaseOnce(phaseId: Long): List<TaskEntity> =
            tasks
                .filter { it.phaseId == phaseId && it.archivedAt == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))

        override suspend fun getUnphasedTasksForProjectOnce(projectId: Long): List<TaskEntity> =
            tasks
                .filter { it.projectId == projectId && it.phaseId == null && it.archivedAt == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))

        override suspend fun getIdByCloudId(cloudId: String): Long? =
            tasks.firstOrNull { it.cloudId == cloudId }?.id

        override suspend fun markCompleted(id: Long, completedAt: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                tasks[idx] = tasks[idx].copy(
                    isCompleted = true,
                    completedAt = completedAt,
                    updatedAt = completedAt
                )
            }
        }

        override suspend fun markIncomplete(id: Long, now: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                tasks[idx] = tasks[idx].copy(
                    isCompleted = false,
                    completedAt = null,
                    updatedAt = now
                )
            }
        }

        override suspend fun archiveTask(id: Long, archivedAt: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                tasks[idx] = tasks[idx].copy(
                    archivedAt = archivedAt,
                    updatedAt = archivedAt
                )
            }
        }

        override suspend fun unarchiveTask(id: Long, updatedAt: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                tasks[idx] = tasks[idx].copy(
                    archivedAt = null,
                    updatedAt = updatedAt
                )
            }
        }

        override suspend fun setPlanDate(id: Long, plannedDate: Long?, now: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                tasks[idx] = tasks[idx].copy(
                    plannedDate = plannedDate,
                    updatedAt = now
                )
            }
        }

        override suspend fun getMaxSubtaskSortOrder(parentTaskId: Long): Int =
            tasks.filter { it.parentTaskId == parentTaskId }.maxOfOrNull { it.sortOrder } ?: -1

        override suspend fun getMaxRootSortOrder(): Int =
            tasks.filter { it.parentTaskId == null }.maxOfOrNull { it.sortOrder } ?: -1

        override suspend fun getSubtasksOnce(parentTaskId: Long): List<TaskEntity> =
            tasks.filter { it.parentTaskId == parentTaskId }.sortedBy { it.sortOrder }

        override suspend fun batchUpdatePriorityQuery(taskIds: List<Long>, priority: Int, now: Long) {
            tasks.replaceAll { t ->
                if (t.id in taskIds) t.copy(priority = priority, updatedAt = now) else t
            }
        }

        override suspend fun batchUpdateDueDateQuery(taskIds: List<Long>, newDueDate: Long?, now: Long) {
            tasks.replaceAll { t ->
                if (t.id in taskIds) t.copy(dueDate = newDueDate, updatedAt = now) else t
            }
        }

        override suspend fun batchUpdateProjectQuery(taskIds: List<Long>, newProjectId: Long?, now: Long) {
            tasks.replaceAll { t ->
                if (t.id in taskIds) t.copy(projectId = newProjectId, updatedAt = now) else t
            }
        }

        override suspend fun batchTouchTasksQuery(taskIds: List<Long>, now: Long) {
            tasks.replaceAll { t ->
                if (t.id in taskIds) t.copy(updatedAt = now) else t
            }
        }

        override suspend fun batchInsertTaskTagsQuery(taskIds: List<Long>, tagId: Long) {}

        override suspend fun batchDeleteTaskTagsQuery(taskIds: List<Long>, tagId: Long) {}

        override suspend fun updateSortOrder(id: Long, sortOrder: Int, now: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) tasks[idx] = tasks[idx].copy(sortOrder = sortOrder, updatedAt = now)
        }

        // --- Unused methods ---
        override fun getAllTasks(): Flow<List<TaskEntity>> = flowOf(tasks.toList())

        override fun getAllTasksByCustomOrder(): Flow<List<TaskEntity>> = flowOf(tasks.toList())

        override fun getTasksByProject(projectId: Long): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { it.projectId == projectId })

        override suspend fun getTasksByProjectOnce(projectId: Long): List<TaskEntity> =
            tasks.filter { it.projectId == projectId }

        override suspend fun deleteTasksByProjectId(projectId: Long) {
            tasks.removeAll { it.projectId == projectId }
        }

        override fun getSubtasks(parentTaskId: Long): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { it.parentTaskId == parentTaskId })

        override fun getIncompleteTasks(): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { !it.isCompleted })

        override fun getIncompleteRootTasks(): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { !it.isCompleted && it.parentTaskId == null })

        override fun getTasksDueOnDate(startOfDay: Long, endOfDay: Long): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { (it.dueDate ?: 0L) in startOfDay until endOfDay })

        override fun getTasksForAnalyticsRange(startMillis: Long, endMillis: Long): Flow<List<TaskEntity>> =
            flowOf(
                tasks.filter { task ->
                    (task.dueDate?.let { it in startMillis until endMillis } == true) ||
                        (task.completedAt?.let { it in startMillis until endMillis } == true)
                }
            )

        override fun getOverdueTasks(now: Long): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { (it.dueDate ?: Long.MAX_VALUE) < now && !it.isCompleted })

        override suspend fun getAllTasksOnce(): List<TaskEntity> = tasks.toList()

        override suspend fun getIncompleteTasksWithReminders(): List<TaskEntity> =
            tasks.filter { !it.isCompleted && it.reminderOffset != null && it.dueDate != null }

        override fun getTasksWithTags(): Flow<List<TaskWithTags>> = flowOf(emptyList())

        override fun searchTasks(query: String): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { it.title.contains(query, true) })

        override fun getArchivedTasks(): Flow<List<TaskEntity>> =
            flowOf(tasks.filter { it.archivedAt != null })

        override suspend fun permanentlyDelete(id: Long) {
            tasks.removeAll { it.id == id }
        }

        override suspend fun archiveCompletedBefore(cutoffDate: Long, now: Long) {}

        override fun getArchivedCount(): Flow<Int> =
            flowOf(tasks.count { it.archivedAt != null })

        override fun searchArchivedTasks(query: String): Flow<List<TaskEntity>> = flowOf(emptyList())

        override fun getOverdueRootTasks(startOfToday: Long): Flow<List<TaskEntity>> = flowOf(emptyList())

        override fun getTodayTasks(startOfToday: Long, endOfToday: Long): Flow<List<TaskEntity>> = flowOf(emptyList())

        override fun getPlannedForToday(startOfToday: Long, endOfToday: Long): Flow<List<TaskEntity>> = flowOf(emptyList())

        override fun getCompletedToday(startOfToday: Long): Flow<List<TaskEntity>> = flowOf(emptyList())

        override suspend fun getOverdueRootTasksOnce(startOfToday: Long): List<TaskEntity> = emptyList()

        override suspend fun getTodayTasksOnce(startOfToday: Long, endOfToday: Long): List<TaskEntity> = emptyList()

        override suspend fun getCompletedTodayOnce(startOfToday: Long): List<TaskEntity> = emptyList()

        override suspend fun getIncompleteRootTasksOnce(): List<TaskEntity> =
            tasks.filter { !it.isCompleted && it.parentTaskId == null }

        override suspend fun getInboxCandidatesOnce(limit: Int): List<TaskEntity> =
            tasks
                .filter {
                    !it.isCompleted && it.parentTaskId == null && it.archivedAt == null &&
                        it.projectId == null && it.dueDate == null
                }
                .sortedByDescending { it.createdAt }
                .take(limit)

        override fun getTasksNotInToday(startOfToday: Long, endOfToday: Long): Flow<List<TaskEntity>> = flowOf(emptyList())

        override suspend fun clearExpiredPlans(startOfToday: Long, now: Long) {}

        override suspend fun updateDueDate(id: Long, newDate: Long?, now: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) tasks[idx] = tasks[idx].copy(dueDate = newDate, updatedAt = now)
        }

        override suspend fun getTasksForHabitInRangeOnce(habitId: Long, startDate: Long, endDate: Long): List<TaskEntity> = emptyList()

        override suspend fun updateEisenhowerQuadrant(id: Long, quadrant: String?, reason: String?, updatedAt: Long) {
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                tasks[idx] = tasks[idx].copy(
                    eisenhowerQuadrant = quadrant,
                    eisenhowerReason = reason,
                    eisenhowerUpdatedAt = updatedAt,
                    updatedAt = updatedAt
                )
            }
        }

        override suspend fun updateEisenhowerQuadrantIfNotOverridden(
            id: Long,
            quadrant: String?,
            reason: String?,
            updatedAt: Long
        ): Int {
            val idx = tasks.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return 0
            if (tasks[idx].userOverrodeQuadrant) return 0
            tasks[idx] = tasks[idx].copy(
                eisenhowerQuadrant = quadrant,
                eisenhowerReason = reason,
                eisenhowerUpdatedAt = updatedAt,
                updatedAt = updatedAt
            )
            return 1
        }

        override suspend fun setManualQuadrant(
            id: Long,
            quadrant: String?,
            reason: String?,
            updatedAt: Long
        ) {
            val idx = tasks.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
            tasks[idx] = tasks[idx].copy(
                eisenhowerQuadrant = quadrant,
                eisenhowerReason = reason,
                eisenhowerUpdatedAt = updatedAt,
                userOverrodeQuadrant = true,
                updatedAt = updatedAt
            )
        }

        override suspend fun clearManualQuadrantOverride(id: Long, updatedAt: Long) {
            val idx = tasks.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
            tasks[idx] = tasks[idx].copy(
                userOverrodeQuadrant = false,
                updatedAt = updatedAt
            )
        }

        override fun getCategorizedTasks(): Flow<List<TaskEntity>> = flowOf(emptyList())

        override suspend fun updatePlannedDateAndSortOrder(id: Long, plannedDate: Long, sortOrder: Int, now: Long) {}

        override suspend fun getCompletedTasksInRange(startOfDay: Long, endOfDay: Long): List<TaskEntity> = emptyList()

        override suspend fun getIncompleteTodayCount(endOfDay: Long): Int = 0

        override suspend fun getLastCompletedTask(): TaskEntity? = null

        override suspend fun getIncompleteTaskCount(): Int = tasks.count { !it.isCompleted }

        override suspend fun deleteAll() {
            tasks.clear()
        }

        override suspend fun deleteAllTaskTagCrossRefs() {}
    }

    private class FakeTagDao : TagDao {
        val crossRefs = mutableListOf<TaskTagCrossRef>()

        override suspend fun addTagToTask(crossRef: TaskTagCrossRef) {
            crossRefs.add(crossRef)
        }

        override suspend fun getTagIdsForTaskOnce(taskId: Long): List<Long> =
            crossRefs.filter { it.taskId == taskId }.map { it.tagId }

        override suspend fun getTagNamesForTaskOnce(taskId: Long): List<String> = emptyList()

        override fun getAllTags(): Flow<List<TagEntity>> = flowOf(emptyList())

        override fun getTagById(id: Long): Flow<TagEntity?> = flowOf(null)

        override fun getTagsForTask(taskId: Long): Flow<List<TagEntity>> = flowOf(emptyList())

        override suspend fun getAllTagsOnce(): List<TagEntity> = emptyList()

        override suspend fun getTagByIdOnce(id: Long): TagEntity? = null

        override suspend fun getTagByNameOnce(name: String): TagEntity? = null

        override suspend fun insert(tag: TagEntity): Long = 0L

        override suspend fun update(tag: TagEntity) {}

        override suspend fun delete(tag: TagEntity) {}

        override fun searchTags(query: String): Flow<List<TagEntity>> = flowOf(emptyList())

        override suspend fun removeTagFromTask(taskId: Long, tagId: Long) {
            crossRefs.removeAll { it.taskId == taskId && it.tagId == tagId }
        }

        override suspend fun removeAllTagsFromTask(taskId: Long) {
            crossRefs.removeAll { it.taskId == taskId }
        }

        override suspend fun deleteAll() {}

        override suspend fun deleteAllCrossRefs() {
            crossRefs.clear()
        }
    }
}
