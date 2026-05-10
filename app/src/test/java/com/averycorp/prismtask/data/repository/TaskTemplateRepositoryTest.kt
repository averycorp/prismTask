package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.local.entity.TaskWithTags
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TaskTemplateRepository]. Split into two layers:
 *
 *  - Pure companion helpers (buildTaskFromTemplate, parseSubtaskTitles, etc.)
 *    are exercised directly, mirroring the style of [DuplicateTaskTest].
 *
 *  - End-to-end repository flows (createTaskFromTemplate, createTemplateFromTask,
 *    incrementUsage) are exercised against in-memory fake DAOs so we can assert
 *    the full orchestration — task inserts, subtask inserts, tag cross-ref
 *    inserts, and usage-counter updates — without standing up a real Room db.
 */
/**
 * Default-keyword stub for [TaskTemplateRepository]'s
 * [AdvancedTuningPreferences] dependency. The repository now reads
 * user-supplied life-category keywords on every classifier build; tests
 * that don't exercise customization keep behaving like the pre-wiring
 * world by emitting an empty [LifeCategoryCustomKeywords].
 */
private fun fakeAdvancedTuningPreferences(): AdvancedTuningPreferences = mockk {
    every { getLifeCategoryCustomKeywords() } returns flowOf(LifeCategoryCustomKeywords())
}

class TaskTemplateRepositoryTest {
    // ---------------------------------------------------------------------
    // Pure helper tests — edge cases that are painful to express in the
    // end-to-end flow.
    // ---------------------------------------------------------------------

    @Test
    fun buildTaskFromTemplate_fallsBackToTemplateNameWhenTitleIsNull() {
        // When a template was created with just a name (no distinct
        // templateTitle), the resulting task should use the template name
        // as its title so the task isn't silently blank.
        val template = sampleTemplate(
            name = "Weekly Review",
            templateTitle = null
        )

        val task = TaskTemplateRepository.buildTaskFromTemplate(
            template = template,
            dueDateOverride = null,
            projectIdOverride = null,
            now = 0L
        )

        assertEquals("Weekly Review", task.title)
    }

    @Test
    fun updateTemplate_flipsIsBuiltInFalseWhenUserEditsDefault() = runBlocking {
        // Editing a built-in template should demote it to a plain user
        // template — the point of shipping the bit is "this is exactly what
        // we gave you", so any edit invalidates that claim.
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val id = templateDao.insertTemplate(
            sampleTemplate(
                name = "Morning Routine",
                templateTitle = "Morning Routine"
            ).copy(isBuiltIn = true)
        )

        val original = templateDao.getTemplateById(id)!!
        assertTrue(
            "Precondition: inserted template should be marked as built-in",
            original.isBuiltIn
        )

        repo.updateTemplate(original.copy(name = "My Morning Routine"))

        val stored = templateDao.getTemplateById(id)!!
        assertEquals("My Morning Routine", stored.name)
        assertEquals(
            "Editing a built-in template should flip isBuiltIn to false",
            false,
            stored.isBuiltIn
        )
    }

    @Test
    fun findBestMatchByName_prefersExactMatchOverSubstring() {
        val templates = listOf(
            sampleTemplate(name = "Morning Routine"),
            sampleTemplate(name = "Routine", usageCount = 10)
        )
        val match = TaskTemplateRepository.findBestMatchByName(templates, "morning routine")
        assertNotNull(match)
        assertEquals("Morning Routine", match!!.name)
    }

    @Test
    fun findBestMatchByName_fallsBackToSubstringAndTokenPrefix() {
        val templates = listOf(
            sampleTemplate(name = "Weekly Review"),
            sampleTemplate(name = "Deep Clean"),
            sampleTemplate(name = "Grocery Shopping")
        )
        // Substring match — "review" appears inside "Weekly Review".
        val reviewMatch = TaskTemplateRepository.findBestMatchByName(templates, "review")
        assertEquals("Weekly Review", reviewMatch?.name)

        // Token-prefix match — "groc" is a prefix of "Grocery".
        val groceryMatch = TaskTemplateRepository.findBestMatchByName(templates, "groc")
        assertEquals("Grocery Shopping", groceryMatch?.name)

        // Nothing matches — return null rather than guessing.
        val noMatch = TaskTemplateRepository.findBestMatchByName(templates, "pizza party")
        assertTrue(noMatch == null)
    }

    @Test
    fun mergeTemplatesByName_keepsHigherUsageCountOnCollision() {
        val local = listOf(
            sampleTemplate(name = "Morning Routine", usageCount = 8),
            sampleTemplate(name = "Deep Clean", usageCount = 2)
        )
        val remote = listOf(
            // Remote "Morning Routine" has lower usage, so local wins.
            sampleTemplate(name = "morning routine", usageCount = 3),
            // Remote "Deep Clean" has higher usage, so remote wins.
            sampleTemplate(name = "Deep Clean", usageCount = 11),
            // Remote-only template — should be included as-is.
            sampleTemplate(name = "Weekly Review", usageCount = 0)
        )

        val merged = TaskTemplateRepository
            .mergeTemplatesByName(local, remote)
            .associateBy { it.name.lowercase() }

        assertEquals(3, merged.size)
        assertEquals(8, merged["morning routine"]?.usageCount)
        assertEquals(11, merged["deep clean"]?.usageCount)
        assertNotNull(merged["weekly review"])
    }

    @Test
    fun parseSubtaskTitles_returnsEmptyListForNullBlankOrMalformedInput() {
        // Templates can land in the DB with null / empty / malformed JSON
        // (e.g., from an older schema or a bad pull). createTaskFromTemplate
        // must treat each of these as "no subtasks" rather than crashing.
        assertTrue(TaskTemplateRepository.parseSubtaskTitles(null).isEmpty())
        assertTrue(TaskTemplateRepository.parseSubtaskTitles("").isEmpty())
        assertTrue(TaskTemplateRepository.parseSubtaskTitles("   ").isEmpty())
        assertTrue(TaskTemplateRepository.parseSubtaskTitles("not json").isEmpty())
    }

    // ---------------------------------------------------------------------
    // End-to-end tests against fake DAOs
    // ---------------------------------------------------------------------

    @Test
    fun createTaskFromTemplate_createsTaskWithCorrectFields() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val templateId = templateDao.insertTemplate(
            sampleTemplate(
                name = "Daily Standup",
                templateTitle = "Daily Standup: today",
                templateDescription = "What I did, what I'll do, blockers",
                templatePriority = 1,
                templateProjectId = 3L,
                templateRecurrenceJson = "{\"type\":\"DAILY\"}",
                templateDuration = 15
            )
        )

        val newTaskId = repo.createTaskFromTemplate(templateId)
        val task = taskDao.tasks.first { it.id == newTaskId }

        assertEquals("Daily Standup: today", task.title)
        assertEquals("What I did, what I'll do, blockers", task.description)
        assertEquals(1, task.priority)
        assertEquals(3L, task.projectId)
        assertEquals("{\"type\":\"DAILY\"}", task.recurrenceRule)
        assertEquals(15, task.estimatedDuration)
    }

    @Test
    fun createTaskFromTemplate_createsSubtasksFromJson() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val templateId = templateDao.insertTemplate(
            sampleTemplate(
                templateTitle = "Launch Feature",
                templateSubtasksJson = Gson().toJson(listOf("Design", "Build", "Ship"))
            )
        )

        val newTaskId = repo.createTaskFromTemplate(templateId)

        val subtasks = taskDao.tasks.filter { it.parentTaskId == newTaskId }
        assertEquals(3, subtasks.size)
        assertEquals(listOf("Design", "Build", "Ship"), subtasks.map { it.title })
        // Subtasks should be ordered via sortOrder 0..N
        assertEquals(listOf(0, 1, 2), subtasks.map { it.sortOrder })
        // Each subtask should point at the new root task.
        assertTrue(subtasks.all { it.parentTaskId == newTaskId })
    }

    @Test
    fun createTaskFromTemplate_assignsTagsFromJson() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val templateId = templateDao.insertTemplate(
            sampleTemplate(
                templateTitle = "Read Paper",
                templateTagsJson = Gson().toJson(listOf(11L, 22L, 33L))
            )
        )

        val newTaskId = repo.createTaskFromTemplate(templateId)

        val crossRefs = tagDao.crossRefs.filter { it.taskId == newTaskId }
        assertEquals(3, crossRefs.size)
        assertEquals(listOf(11L, 22L, 33L), crossRefs.map { it.tagId })
    }

    @Test
    fun createTaskFromTemplate_incrementsTemplateUsageCountAndTimestamp() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val templateId = templateDao.insertTemplate(
            sampleTemplate(templateTitle = "Standup", usageCount = 4)
        )

        val beforeMillis = System.currentTimeMillis()
        repo.createTaskFromTemplate(templateId)
        val afterMillis = System.currentTimeMillis()

        val stored = templateDao.getTemplateById(templateId)!!
        assertEquals(5, stored.usageCount)
        val lastUsed = stored.lastUsedAt
        assertNotNull("lastUsedAt should be set after createTaskFromTemplate", lastUsed)
        assertTrue(
            "lastUsedAt should be within the window of the call",
            lastUsed!! in beforeMillis..afterMillis
        )
    }

    @Test
    fun createTaskFromTemplate_quickUseDefaultsToTodayDueDate() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val templateId = templateDao.insertTemplate(
            sampleTemplate(templateTitle = "Quick Task")
        )

        val newTaskId = repo.createTaskFromTemplate(templateId, quickUse = true)
        val task = taskDao.tasks.first { it.id == newTaskId }

        // Quick-use with no dueDateOverride should default to start of today
        val startOfToday = com.averycorp.prismtask.domain.usecase.DateShortcuts
            .today()
        assertEquals(startOfToday, task.dueDate)
    }

    @Test
    fun createTaskFromTemplate_quickUseFalseLeavesDueDateNull() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val templateId = templateDao.insertTemplate(
            sampleTemplate(templateTitle = "Editor Task")
        )

        val newTaskId = repo.createTaskFromTemplate(templateId, quickUse = false)
        val task = taskDao.tasks.first { it.id == newTaskId }

        // Non-quick-use with no dueDateOverride should leave dueDate null
        assertEquals(null, task.dueDate)
    }

    @Test
    fun createTaskFromTemplate_quickUseWithOverrideUsesOverride() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        val templateId = templateDao.insertTemplate(
            sampleTemplate(templateTitle = "Scheduled Task")
        )

        val customDate = 1_700_000_000_000L
        val newTaskId = repo.createTaskFromTemplate(
            templateId,
            dueDateOverride = customDate,
            quickUse = true
        )
        val task = taskDao.tasks.first { it.id == newTaskId }

        // Explicit override should take precedence over quick-use default
        assertEquals(customDate, task.dueDate)
    }

    @Test
    fun createTemplateFromTask_capturesAllTaskFields() = runBlocking {
        val templateDao = FakeTemplateDao()
        val taskDao = FakeTaskDao()
        val tagDao = FakeTagDao()
        val repo = TaskTemplateRepository(templateDao, taskDao, tagDao, fakeAdvancedTuningPreferences())

        // Seed a task + two subtasks + two tag cross-refs.
        val parentId = taskDao.insert(
            TaskEntity(
                title = "Publish Blog Post",
                description = "Quarterly update",
                priority = 3,
                projectId = 9L,
                recurrenceRule = "{\"type\":\"MONTHLY\"}",
                estimatedDuration = 60,
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        taskDao.insert(
            TaskEntity(
                title = "Outline",
                parentTaskId = parentId,
                sortOrder = 0,
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        taskDao.insert(
            TaskEntity(
                title = "Write draft",
                parentTaskId = parentId,
                sortOrder = 1,
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        tagDao.addTagToTask(TaskTagCrossRef(taskId = parentId, tagId = 111L))
        tagDao.addTagToTask(TaskTagCrossRef(taskId = parentId, tagId = 222L))

        val templateId = repo.createTemplateFromTask(
            taskId = parentId,
            name = "Blog Post Template",
            icon = "\uD83D\uDCDD",
            category = "writing"
        )

        val stored = templateDao.getTemplateById(templateId)!!
        assertEquals("Blog Post Template", stored.name)
        assertEquals("\uD83D\uDCDD", stored.icon)
        assertEquals("writing", stored.category)
        assertEquals("Publish Blog Post", stored.templateTitle)
        assertEquals("Quarterly update", stored.templateDescription)
        assertEquals(3, stored.templatePriority)
        assertEquals(9L, stored.templateProjectId)
        assertEquals("{\"type\":\"MONTHLY\"}", stored.templateRecurrenceJson)
        assertEquals(60, stored.templateDuration)

        // Tags and subtasks should be serialized to JSON arrays.
        assertEquals(listOf(111L, 222L), TaskTemplateRepository.parseTagIds(stored.templateTagsJson))
        assertEquals(
            listOf("Outline", "Write draft"),
            TaskTemplateRepository.parseSubtaskTitles(stored.templateSubtasksJson)
        )
    }

    // ---------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------

    private fun sampleTemplate(
        id: Long = 0L,
        name: String = "Sample Template",
        templateTitle: String? = "Sample Template Title",
        templateDescription: String? = null,
        templatePriority: Int? = null,
        templateProjectId: Long? = null,
        templateTagsJson: String? = null,
        templateRecurrenceJson: String? = null,
        templateDuration: Int? = null,
        templateSubtasksJson: String? = null,
        usageCount: Int = 0
    ) = TaskTemplateEntity(
        id = id,
        name = name,
        templateTitle = templateTitle,
        templateDescription = templateDescription,
        templatePriority = templatePriority,
        templateProjectId = templateProjectId,
        templateTagsJson = templateTagsJson,
        templateRecurrenceJson = templateRecurrenceJson,
        templateDuration = templateDuration,
        templateSubtasksJson = templateSubtasksJson,
        usageCount = usageCount,
        createdAt = 0L,
        updatedAt = 0L
    )

    /** In-memory fake of [TaskTemplateDao]. Only implements the methods the repository calls. */
    private class FakeTemplateDao : TaskTemplateDao {
        private val store = mutableMapOf<Long, TaskTemplateEntity>()
        private var nextId = 1L

        override fun getAllTemplates(): Flow<List<TaskTemplateEntity>> =
            flowOf(store.values.sortedByDescending { it.usageCount })

        override suspend fun getAllTemplatesOnce(): List<TaskTemplateEntity> =
            store.values.sortedByDescending { it.usageCount }

        override fun getTemplatesByCategory(category: String): Flow<List<TaskTemplateEntity>> =
            flowOf(store.values.filter { it.category == category })

        override suspend fun getTemplateById(id: Long): TaskTemplateEntity? = store[id]

        override fun getAllCategories(): Flow<List<String>> =
            flowOf(
                store.values
                    .mapNotNull { it.category }
                    .distinct()
                    .sorted()
            )

        override suspend fun insertTemplate(template: TaskTemplateEntity): Long {
            val id = if (template.id == 0L) nextId++ else template.id.also { nextId = maxOf(nextId, it + 1) }
            store[id] = template.copy(id = id)
            return id
        }

        override suspend fun updateTemplate(template: TaskTemplateEntity) {
            store[template.id] = template
        }

        override suspend fun deleteTemplate(id: Long) {
            store.remove(id)
        }

        override suspend fun incrementUsage(id: Long, usedAt: Long) {
            store[id]?.let {
                store[id] = it.copy(usageCount = it.usageCount + 1, lastUsedAt = usedAt)
            }
        }

        override suspend fun countTemplates(): Int = store.size

        override suspend fun getTemplateByName(name: String): TaskTemplateEntity? =
            store.values.firstOrNull { it.name == name }

        override suspend fun clearCategory(category: String, now: Long) {
            store.entries.forEach { (id, template) ->
                if (template.category == category) {
                    store[id] = template.copy(category = null, updatedAt = now)
                }
            }
        }

        override fun searchTemplates(query: String): Flow<List<TaskTemplateEntity>> =
            flowOf(
                store.values.filter {
                    (it.name.contains(query) || it.templateTitle?.contains(query) == true)
                }
            )

        override suspend fun deleteAll() {
            store.clear()
        }

        override suspend fun deleteAllBuiltIn() {
            val builtInIds = store.entries.filter { it.value.isBuiltIn }.map { it.key }
            builtInIds.forEach { store.remove(it) }
        }

        override suspend fun getBuiltInTemplatesOnce(): List<TaskTemplateEntity> =
            store.values.filter { it.isBuiltIn }

        override suspend fun deleteById(id: Long) {
            store.remove(id)
        }
    }

    /** In-memory fake of [TaskDao]. Implements only the handful of calls the template repo uses. */
    private class FakeTaskDao : TaskDao {
        val tasks = mutableListOf<TaskEntity>()
        private var nextId = 1L

        override suspend fun insert(task: TaskEntity): Long {
            val id = if (task.id == 0L) nextId++ else task.id.also { nextId = maxOf(nextId, it + 1) }
            tasks.add(task.copy(id = id))
            return id
        }

        override suspend fun getTaskByIdOnce(id: Long): TaskEntity? = tasks.firstOrNull { it.id == id }

        override suspend fun getIdByCloudId(cloudId: String): Long? =
            tasks.firstOrNull { it.cloudId == cloudId }?.id

        override suspend fun getSubtasksOnce(parentTaskId: Long): List<TaskEntity> =
            tasks.filter { it.parentTaskId == parentTaskId }.sortedBy { it.sortOrder }

        override suspend fun getTasksForPhaseOnce(phaseId: Long): List<TaskEntity> = unsupported()

        override suspend fun getUnphasedTasksForProjectOnce(projectId: Long): List<TaskEntity> = unsupported()

        override suspend fun getTasksInHorizonOnce(
            startMillis: Long,
            endMillis: Long
        ): List<TaskEntity> = unsupported()

        override suspend fun getScheduledTasksInHorizonOnce(
            startMillis: Long,
            endMillis: Long
        ): List<TaskEntity> = unsupported()

        // --- Unused methods: throw so an accidental call in future tests is loud. ---
        override fun getAllTasks(): Flow<List<TaskEntity>> = unsupported()

        override fun getAllTasksByCustomOrder(): Flow<List<TaskEntity>> = unsupported()

        override fun getTasksByProject(projectId: Long): Flow<List<TaskEntity>> = unsupported()

        override suspend fun getTasksByProjectOnce(projectId: Long): List<TaskEntity> = unsupported()

        override suspend fun deleteTasksByProjectId(projectId: Long) = unsupported()

        override fun getSubtasks(parentTaskId: Long): Flow<List<TaskEntity>> = unsupported()

        override suspend fun getMaxSubtaskSortOrder(parentTaskId: Long): Int = unsupported()

        override suspend fun updateSortOrder(id: Long, sortOrder: Int, now: Long) = unsupported()

        override suspend fun getMaxRootSortOrder(): Int = unsupported()

        override fun getIncompleteTasks(): Flow<List<TaskEntity>> = unsupported()

        override fun getIncompleteRootTasks(): Flow<List<TaskEntity>> = unsupported()

        override fun getTasksDueOnDate(startOfDay: Long, endOfDay: Long): Flow<List<TaskEntity>> = unsupported()

        override fun getTasksForAnalyticsRange(startMillis: Long, endMillis: Long): Flow<List<TaskEntity>> = unsupported()

        override fun getOverdueTasks(now: Long): Flow<List<TaskEntity>> = unsupported()

        override fun getTaskById(id: Long): Flow<TaskEntity?> = unsupported()

        override suspend fun update(task: TaskEntity) = unsupported()

        override suspend fun delete(task: TaskEntity) = unsupported()

        override suspend fun deleteById(id: Long) = unsupported()

        override suspend fun getAllTasksOnce(): List<TaskEntity> = tasks.toList()

        override suspend fun getIncompleteTasksWithReminders(): List<TaskEntity> = unsupported()

        override suspend fun markCompleted(id: Long, completedAt: Long) = unsupported()

        override suspend fun markIncomplete(id: Long, now: Long) = unsupported()

        override fun getTasksWithTags(): Flow<List<TaskWithTags>> = unsupported()

        override fun searchTasks(query: String): Flow<List<TaskEntity>> = unsupported()

        override fun getArchivedTasks(): Flow<List<TaskEntity>> = unsupported()

        override suspend fun archiveTask(id: Long, archivedAt: Long) = unsupported()

        override suspend fun unarchiveTask(id: Long, updatedAt: Long) = unsupported()

        override suspend fun permanentlyDelete(id: Long) = unsupported()

        override suspend fun archiveCompletedBefore(cutoffDate: Long, now: Long) = unsupported()

        override fun getArchivedCount(): Flow<Int> = unsupported()

        override fun searchArchivedTasks(query: String): Flow<List<TaskEntity>> = unsupported()

        override fun getOverdueRootTasks(startOfToday: Long): Flow<List<TaskEntity>> = unsupported()

        override fun getTodayTasks(startOfToday: Long, endOfToday: Long): Flow<List<TaskEntity>> = unsupported()

        override fun getPlannedForToday(startOfToday: Long, endOfToday: Long): Flow<List<TaskEntity>> = unsupported()

        override fun getCompletedToday(startOfToday: Long): Flow<List<TaskEntity>> = unsupported()

        override suspend fun getOverdueRootTasksOnce(startOfToday: Long): List<TaskEntity> = unsupported()

        override suspend fun getTodayTasksOnce(startOfToday: Long, endOfToday: Long): List<TaskEntity> = unsupported()

        override suspend fun getCompletedTodayOnce(startOfToday: Long): List<TaskEntity> = unsupported()

        override suspend fun getIncompleteRootTasksOnce(): List<TaskEntity> = unsupported()

        override suspend fun getInboxCandidatesOnce(limit: Int): List<TaskEntity> = unsupported()

        override suspend fun setPlanDate(id: Long, plannedDate: Long?, now: Long) = unsupported()

        override fun getTasksNotInToday(startOfToday: Long, endOfToday: Long): Flow<List<TaskEntity>> = unsupported()

        override suspend fun clearExpiredPlans(startOfToday: Long, now: Long) = unsupported()

        override suspend fun updateDueDate(id: Long, newDate: Long?, now: Long) = unsupported()

        override suspend fun getTasksForHabitInRangeOnce(habitId: Long, startDate: Long, endDate: Long): List<TaskEntity> = unsupported()

        override suspend fun batchUpdatePriorityQuery(taskIds: List<Long>, priority: Int, now: Long) = unsupported()

        override suspend fun batchUpdateDueDateQuery(taskIds: List<Long>, newDueDate: Long?, now: Long) = unsupported()

        override suspend fun batchUpdateProjectQuery(taskIds: List<Long>, newProjectId: Long?, now: Long) = unsupported()

        override suspend fun batchTouchTasksQuery(taskIds: List<Long>, now: Long) = unsupported()

        override suspend fun batchInsertTaskTagsQuery(taskIds: List<Long>, tagId: Long) = unsupported()

        override suspend fun batchDeleteTaskTagsQuery(taskIds: List<Long>, tagId: Long) = unsupported()

        override suspend fun updateEisenhowerQuadrant(id: Long, quadrant: String?, reason: String?, updatedAt: Long) = unsupported()

        override suspend fun updateEisenhowerQuadrantIfNotOverridden(
            id: Long,
            quadrant: String?,
            reason: String?,
            updatedAt: Long
        ): Int = unsupported()

        override suspend fun setManualQuadrant(
            id: Long,
            quadrant: String?,
            reason: String?,
            updatedAt: Long
        ) = unsupported()

        override suspend fun clearManualQuadrantOverride(id: Long, updatedAt: Long) = unsupported()

        override fun getCategorizedTasks(): Flow<List<TaskEntity>> = unsupported()

        override suspend fun updatePlannedDateAndSortOrder(id: Long, plannedDate: Long, sortOrder: Int, now: Long) = unsupported()

        override suspend fun getCompletedTasksInRange(startOfDay: Long, endOfDay: Long): List<TaskEntity> = unsupported()

        override suspend fun getIncompleteTodayCount(endOfDay: Long): Int = unsupported()

        override suspend fun getLastCompletedTask(): TaskEntity? = unsupported()

        override suspend fun getIncompleteTaskCount(): Int = unsupported()

        override suspend fun deleteAll(): Nothing = unsupported()

        override suspend fun deleteAllTaskTagCrossRefs(): Nothing = unsupported()

        override suspend fun getLatestHabitTaskForDayOnce(
            habitId: Long,
            startDate: Long,
            endDate: Long
        ): TaskEntity? = unsupported()
    }

    /** In-memory fake of [TagDao]. Records all cross-ref inserts so tests can assert on them. */
    private class FakeTagDao : TagDao {
        val crossRefs = mutableListOf<TaskTagCrossRef>()
        val tagIdsByTask = mutableMapOf<Long, MutableList<Long>>()

        override suspend fun addTagToTask(crossRef: TaskTagCrossRef) {
            crossRefs.add(crossRef)
            tagIdsByTask.getOrPut(crossRef.taskId) { mutableListOf() }.add(crossRef.tagId)
        }

        override suspend fun getTagIdsForTaskOnce(taskId: Long): List<Long> =
            tagIdsByTask[taskId]?.toList() ?: emptyList()

        override suspend fun getTagNamesForTaskOnce(taskId: Long): List<String> = unsupported()

        override fun getAllTags(): Flow<List<TagEntity>> = unsupported()

        override fun getTagById(id: Long): Flow<TagEntity?> = unsupported()

        override fun getTagsForTask(taskId: Long): Flow<List<TagEntity>> = unsupported()

        override suspend fun getAllTagsOnce(): List<TagEntity> = unsupported()

        override suspend fun getTagByIdOnce(id: Long): TagEntity? = unsupported()

        override suspend fun getTagByNameOnce(name: String): TagEntity? = unsupported()

        override suspend fun insert(tag: TagEntity): Long = unsupported()

        override suspend fun update(tag: TagEntity) = unsupported()

        override suspend fun delete(tag: TagEntity) = unsupported()

        override fun searchTags(query: String): Flow<List<TagEntity>> = unsupported()

        override suspend fun removeTagFromTask(taskId: Long, tagId: Long) = unsupported()

        override suspend fun removeAllTagsFromTask(taskId: Long) = unsupported()

        override suspend fun deleteAll(): Nothing = unsupported()

        override suspend fun deleteAllCrossRefs(): Nothing = unsupported()
    }

    companion object {
        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("Not needed by TaskTemplateRepository tests")
    }
}
