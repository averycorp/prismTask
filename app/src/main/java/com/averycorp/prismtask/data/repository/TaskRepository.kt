package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.calendar.CalendarPushDispatcher
import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.EisenhowerClassifier
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.automation.AutomationEvent
import com.averycorp.prismtask.domain.automation.AutomationEventBus
import com.averycorp.prismtask.domain.model.EisenhowerQuadrant
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.LifeCategoryClassifier
import com.averycorp.prismtask.domain.usecase.RecurrenceEngine
import com.averycorp.prismtask.notifications.ReminderScheduler
import com.averycorp.prismtask.util.DayBoundary
import com.averycorp.prismtask.widget.WidgetUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class TaskRepository
@Inject
constructor(
    private val transactionRunner: DatabaseTransactionRunner,
    private val taskDao: TaskDao,
    private val tagDao: TagDao,
    private val syncTracker: SyncTracker,
    private val calendarPushDispatcher: CalendarPushDispatcher,
    private val reminderScheduler: ReminderScheduler,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val taskCompletionRepository: TaskCompletionRepository,
    private val eisenhowerClassifier: EisenhowerClassifier,
    private val userPreferences: UserPreferencesDataStore,
    private val automationEventBus: AutomationEventBus,
    private val advancedTuningPreferences: com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences,
    private val habitRepositoryProvider: Provider<HabitRepository>
) {
    /**
     * Latest snapshot of the user's custom life-category keywords, refreshed
     * by a background collector below. Read synchronously on the insert path
     * (which is non-suspend) so newly-added keywords (Settings → Advanced
     * Tuning) start affecting auto-classification on the next task creation.
     * Defaults to no extra keywords until the first emission.
     */
    @Volatile
    private var latestLifeCategoryCustomKeywords: com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords =
        com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords()

    private fun lifeCategoryClassifier(): LifeCategoryClassifier =
        LifeCategoryClassifier.withCustomKeywords(latestLifeCategoryCustomKeywords)

    /**
     * Background scope for fire-and-forget Eisenhower classification. A
     * classification failure must never surface to the task-creation caller —
     * offline / rate-limited / malformed-response all fall through and leave
     * the task with its pre-existing quadrant (null on first create).
     *
     * SupervisorJob so one failed classify doesn't cancel future ones.
     */
    private val classifyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Keep [latestLifeCategoryCustomKeywords] warm so the synchronous
        // insert path picks up Settings changes without blocking on disk.
        classifyScope.launch {
            advancedTuningPreferences.getLifeCategoryCustomKeywords().collect {
                latestLifeCategoryCustomKeywords = it
            }
        }
    }

    /**
     * Enqueue an async classification for a newly-created task. Respects the
     * `eisenhowerAutoClassifyEnabled` preference and the per-task
     * `userOverrodeQuadrant` guard — user moves are never clobbered by the
     * auto-classifier.
     */
    private fun classifyInBackground(taskId: Long) {
        classifyScope.launch {
            val prefs = userPreferences.eisenhowerFlow.firstOrNull() ?: return@launch
            if (!prefs.autoClassifyEnabled) return@launch
            val task = taskDao.getTaskByIdOnce(taskId) ?: return@launch
            if (task.userOverrodeQuadrant) return@launch
            val result = eisenhowerClassifier.classify(task)
            val classification = result.getOrNull() ?: return@launch
            val code = classification.quadrant.code ?: return@launch
            val updated = taskDao.updateEisenhowerQuadrantIfNotOverridden(
                id = taskId,
                quadrant = code,
                reason = classification.reason
            )
            if (updated > 0) {
                syncTracker.trackUpdate(taskId, "task")
                widgetUpdateManager.updateTaskWidgets()
            }
        }
    }

    /**
     * Centralized life-category fallback applied on every insert path so that
     * no task reaches Room with a null `life_category`. Null means "never
     * classified"; a non-null value means either "user picked" or "classifier
     * ran." A classifier miss resolves to [LifeCategory.UNCATEGORIZED] rather
     * than null so downstream readers (balance bar, weekly report) can tell
     * the difference between "we tried" and "nobody's looked at this row
     * yet."
     */
    fun resolveLifeCategoryForInsert(task: TaskEntity): String {
        val existing = task.lifeCategory
        if (!existing.isNullOrBlank()) {
            android.util.Log.i(
                "PrismSync",
                "lifeCategory.resolved | taskId=${task.id} | source=preserved | result=$existing"
            )
            return existing
        }
        val guess = lifeCategoryClassifier().classify(task.title, task.description)
        val source = if (guess == LifeCategory.UNCATEGORIZED) "default" else "classifier"
        android.util.Log.i(
            "PrismSync",
            "lifeCategory.resolved | taskId=${task.id} | source=$source | result=${guess.name}"
        )
        return guess.name
    }

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    fun getTasksByProject(projectId: Long): Flow<List<TaskEntity>> = taskDao.getTasksByProject(projectId)

    fun getSubtasks(parentTaskId: Long): Flow<List<TaskEntity>> = taskDao.getSubtasks(parentTaskId)

    suspend fun deleteTasksByProjectId(projectId: Long) {
        taskDao.getTasksByProjectOnce(projectId).forEach {
            reminderScheduler.cancelReminder(it.id)
        }
        taskDao.deleteTasksByProjectId(projectId)
        widgetUpdateManager.updateTaskWidgets()
    }

    fun getIncompleteTasks(): Flow<List<TaskEntity>> = taskDao.getIncompleteTasks()

    fun getIncompleteRootTasks(): Flow<List<TaskEntity>> = taskDao.getIncompleteRootTasks()

    fun getTasksDueOnDate(startOfDay: Long, endOfDay: Long): Flow<List<TaskEntity>> = taskDao.getTasksDueOnDate(startOfDay, endOfDay)

    fun getOverdueTasks(now: Long): Flow<List<TaskEntity>> = taskDao.getOverdueTasks(now)

    suspend fun addSubtask(title: String, parentTaskId: Long, priority: Int = 0): Long {
        val now = System.currentTimeMillis()
        val parent = taskDao.getTaskById(parentTaskId).firstOrNull()
        val nextSortOrder = taskDao.getMaxSubtaskSortOrder(parentTaskId) + 1
        val draft =
            TaskEntity(
                title = title,
                parentTaskId = parentTaskId,
                projectId = parent?.projectId,
                dueDate = parent?.dueDate,
                priority = priority,
                sortOrder = nextSortOrder,
                lifeCategory = parent?.lifeCategory,
                createdAt = now,
                updatedAt = now
            )
        val task = draft.copy(lifeCategory = resolveLifeCategoryForInsert(draft))
        val id = taskDao.insert(task)
        syncTracker.trackCreate(id, "task")
        automationEventBus.emit(AutomationEvent.TaskCreated(id))
        calendarPushDispatcher.enqueuePushTask(id)
        widgetUpdateManager.updateTaskWidgets()
        classifyInBackground(id)
        return id
    }

    suspend fun reorderSubtasks(parentTaskId: Long, orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            taskDao.updateSortOrder(id, index)
            syncTracker.trackUpdate(id, "task")
            automationEventBus.emit(AutomationEvent.TaskUpdated(id))
        }
    }

    suspend fun updateTaskOrder(taskOrders: List<Pair<Long, Int>>) {
        if (taskOrders.isEmpty()) return
        taskDao.updateSortOrders(taskOrders)
        taskOrders.forEach { (id, _) -> syncTracker.trackUpdate(id, "task") }
    }

    suspend fun getNextRootSortOrder(): Int = taskDao.getMaxRootSortOrder() + 1

    suspend fun getAllTasksOnce(): List<TaskEntity> = taskDao.getAllTasksOnce()

    fun getTaskById(id: Long): Flow<TaskEntity?> = taskDao.getTaskById(id)

    suspend fun getTaskByIdOnce(id: Long): TaskEntity? = taskDao.getTaskByIdOnce(id)

    suspend fun insertTask(task: TaskEntity): Long {
        val resolved = task.copy(lifeCategory = resolveLifeCategoryForInsert(task))
        val id = taskDao.insert(resolved)
        syncTracker.trackCreate(id, "task")
        automationEventBus.emit(AutomationEvent.TaskCreated(id))
        calendarPushDispatcher.enqueuePushTask(id)
        widgetUpdateManager.updateTaskWidgets()
        if (resolved.reminderOffset != null && resolved.dueDate != null) {
            reminderScheduler.scheduleReminder(
                taskId = id,
                taskTitle = resolved.title,
                taskDescription = resolved.description,
                dueDate = ReminderScheduler.combineDateAndTime(resolved.dueDate, resolved.dueTime),
                reminderOffset = resolved.reminderOffset
            )
        }
        classifyInBackground(id)
        return id
    }

    suspend fun addTask(
        title: String,
        description: String? = null,
        dueDate: Long? = null,
        dueTime: Long? = null,
        priority: Int = 0,
        projectId: Long? = null,
        parentTaskId: Long? = null,
        lifeCategory: String? = null,
        taskMode: String? = null,
        cognitiveLoad: String? = null,
        reminderOffset: Long? = null,
        recurrenceRule: String? = null,
        estimatedDuration: Int? = null
    ): Long {
        val now = System.currentTimeMillis()
        val nextSortOrder = if (parentTaskId == null) taskDao.getMaxRootSortOrder() + 1 else 0
        val draft =
            TaskEntity(
                title = title,
                description = description,
                dueDate = dueDate,
                dueTime = dueTime,
                priority = priority,
                projectId = projectId,
                parentTaskId = parentTaskId,
                sortOrder = nextSortOrder,
                lifeCategory = lifeCategory,
                taskMode = taskMode,
                cognitiveLoad = cognitiveLoad,
                reminderOffset = reminderOffset,
                recurrenceRule = recurrenceRule,
                estimatedDuration = estimatedDuration,
                createdAt = now,
                updatedAt = now
            )
        val task = draft.copy(lifeCategory = resolveLifeCategoryForInsert(draft))
        val id = taskDao.insert(task)
        syncTracker.trackCreate(id, "task")
        // Mirror insertTask() — every primary-creation surface routes through
        // this method (TaskList QuickAdd row, Today plan-for-today,
        // AddEditTask save-new, MultiCreate, Onboarding, Conversation
        // extract). Without this emit, automation rules with TaskCreated
        // triggers silently never fire for those paths. See
        // docs/audits/D_AUTOMATION_ACTION_SILENT_FAILURE_AUDIT.md § A1.
        automationEventBus.emit(AutomationEvent.TaskCreated(id))
        calendarPushDispatcher.enqueuePushTask(id)
        widgetUpdateManager.updateTaskWidgets()
        if (reminderOffset != null && dueDate != null) {
            reminderScheduler.scheduleReminder(
                taskId = id,
                taskTitle = title,
                taskDescription = description,
                dueDate = ReminderScheduler.combineDateAndTime(dueDate, dueTime),
                reminderOffset = reminderOffset
            )
        }
        classifyInBackground(id)
        return id
    }

    suspend fun updateTask(task: TaskEntity) {
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        taskDao.update(updated)
        syncTracker.trackUpdate(task.id, "task")
        automationEventBus.emit(AutomationEvent.TaskUpdated(updated.id))
        calendarPushDispatcher.enqueuePushTask(updated.id)
        widgetUpdateManager.updateTaskWidgets()
        if (updated.reminderOffset != null && updated.dueDate != null) {
            reminderScheduler.scheduleReminder(
                taskId = updated.id,
                taskTitle = updated.title,
                taskDescription = updated.description,
                dueDate = ReminderScheduler.combineDateAndTime(updated.dueDate, updated.dueTime),
                reminderOffset = updated.reminderOffset
            )
        } else {
            reminderScheduler.cancelReminder(updated.id)
        }
    }

    suspend fun rescheduleTask(taskId: Long, newDueDate: Long?) {
        val task = taskDao.getTaskByIdOnce(taskId) ?: return
        val updated = task.copy(dueDate = newDueDate, updatedAt = System.currentTimeMillis())
        taskDao.update(updated)
        syncTracker.trackUpdate(taskId, "task")
        val offset = updated.reminderOffset
        if (offset != null &&
            newDueDate != null
        ) {
            reminderScheduler.scheduleReminder(
                taskId = updated.id,
                taskTitle = updated.title,
                taskDescription = updated.description,
                dueDate = ReminderScheduler.combineDateAndTime(newDueDate, updated.dueTime),
                reminderOffset = offset
            )
        } else {
            reminderScheduler.cancelReminder(taskId)
        }
        calendarPushDispatcher.enqueuePushTask(updated.id)
        widgetUpdateManager.updateTaskWidgets()
    }

    suspend fun planTaskForToday(taskId: Long) {
        val startOfToday = DayBoundary.startOfCurrentDay(0)
        taskDao.setPlanDate(taskId, startOfToday)
        syncTracker.trackUpdate(taskId, "task")
        widgetUpdateManager.updateTaskWidgets()
    }

    /**
     * Marks [id] complete and, for recurring tasks, spawns the next occurrence.
     *
     * Returns the id of the spawned next-instance (or null when none was
     * spawned — non-recurring task, max-occurrences hit, or the row is
     * already completed). Callers that drive an Undo snackbar should pass
     * this id back to [uncompleteTask] so the spawned child gets rolled
     * back atomically with the parent.
     *
     * Idempotence: re-invoking on an already-completed row is a no-op.
     * The spawn predicate is checked against a *fresh* read inside the
     * transaction, so a rapid double-tap that races two coroutines into
     * this function will see the post-commit `is_completed = 1` from the
     * winning call and bail out — no duplicate next-instance.
     */
    suspend fun completeTask(id: Long): Long? {
        val now = System.currentTimeMillis()
        val task = taskDao.getTaskById(id).firstOrNull() ?: return null
        val tags = tagDao.getTagsForTask(id).first()

        // Cancel the scheduled reminder for the task we're marking complete
        // so a stale alarm doesn't fire for a finished task. The PendingIntent
        // request code matches the one registered by ReminderScheduler
        // (taskId.toInt()), so this reliably targets the correct alarm.
        // Covers all three completion entry points (single tap / bulk /
        // subtask) because they all funnel through this function.
        reminderScheduler.cancelReminder(id)

        // didComplete distinguishes a real completion (case: row was incomplete
        // and we flipped it) from a no-op (row already completed, or deleted
        // out from under us). nextRecurrenceId is null on both no-ops AND on
        // a real completion of a non-recurring task — so it can't carry the
        // signal alone. Audit: docs/audits/COULDNT_UPDATE_TASK_AUDIT.md (Item 2).
        val (didComplete, nextRecurrenceId) = transactionRunner.withTransaction {
            // Re-read fresh inside the transaction. A concurrent completeTask
            // (rapid double-tap) reaches this point after the winning call
            // commits — it must observe is_completed = 1 and bail out before
            // spawning a duplicate next-instance.
            val fresh = taskDao.getTaskByIdOnce(id) ?: return@withTransaction (false to null)
            if (fresh.isCompleted) return@withTransaction (false to null)

            val nextId = if (fresh.recurrenceRule != null && fresh.dueDate != null) {
                val rule = RecurrenceConverter.fromJson(fresh.recurrenceRule)
                val nextDueDate = rule?.let { RecurrenceEngine.calculateNextDueDate(fresh.dueDate, it) }
                if (rule != null && nextDueDate != null) {
                    val updatedRule = rule.copy(occurrenceCount = rule.occurrenceCount + 1)
                    val nextDraft = fresh.copy(
                        id = 0,
                        isCompleted = false,
                        dueDate = nextDueDate,
                        recurrenceRule = RecurrenceConverter.toJson(updatedRule),
                        completedAt = null,
                        createdAt = now,
                        updatedAt = now
                    )
                    val nextTask = nextDraft.copy(lifeCategory = resolveLifeCategoryForInsert(nextDraft))
                    taskDao.insert(nextTask)
                } else {
                    null
                }
            } else {
                null
            }
            // Recorded after the spawn so the completion row carries the
            // spawned-id link. The toggle-uncomplete path (no Undo snackbar)
            // reads this back to roll the spawn child back, closing the
            // residual Item 2 path that the Undo-only fix didn't cover.
            taskCompletionRepository.recordCompletion(fresh, tags, spawnedRecurrenceId = nextId)
            taskDao.markCompleted(id, now)
            true to nextId
        }

        // Skip side effects when the transaction was a no-op (already-completed
        // row, deleted-out-from-under-us). Without this guard, a re-tap of an
        // already-completed task would still enqueue a calendar delete + sync
        // update + widget refresh.
        if (!didComplete) return null

        if (nextRecurrenceId != null) {
            syncTracker.trackCreate(nextRecurrenceId, "task")
            calendarPushDispatcher.enqueuePushTask(nextRecurrenceId)
            // Re-register the alarm against the newly-inserted recurrence
            // instance. Without this, recurring tasks lose their reminder
            // after the first completion because the reminder_offset field
            // alone doesn't schedule anything.
            val nextTask = taskDao.getTaskByIdOnce(nextRecurrenceId)
            val offset = nextTask?.reminderOffset
            val nextDueDate = nextTask?.dueDate
            if (nextTask != null && offset != null && nextDueDate != null) {
                reminderScheduler.scheduleReminder(
                    taskId = nextRecurrenceId,
                    taskTitle = nextTask.title,
                    taskDescription = nextTask.description,
                    dueDate = ReminderScheduler.combineDateAndTime(nextDueDate, nextTask.dueTime),
                    reminderOffset = offset
                )
            }
        }
        syncTracker.trackUpdate(id, "task")
        automationEventBus.emit(AutomationEvent.TaskCompleted(id))
        calendarPushDispatcher.enqueueDeleteTaskEvent(id)
        widgetUpdateManager.updateTaskWidgets()

        // Two-way sync: an auto-generated habit task carries `sourceHabitId`.
        // Mirror the completion onto the linked habit. HabitRepository's
        // completion path is idempotent, so the reverse-sync there finding
        // this task already complete is a safe no-op.
        task.sourceHabitId?.let { habitId ->
            try {
                habitRepositoryProvider.get().completeHabit(habitId, now)
            } catch (e: Exception) {
                android.util.Log.e("TaskRepository", "Habit sync on complete failed", e)
            }
        }
        return nextRecurrenceId
    }

    /**
     * Reverts a [completeTask] call.
     *
     * When [spawnedRecurrenceId] is non-null (Undo-snackbar path: Today
     * swipe, TaskList swipe, bulk complete), the spawned next-instance is
     * deleted before the parent is flipped back to incomplete.
     *
     * When [spawnedRecurrenceId] is null (toggle-uncomplete path), the
     * latest completion row for the task is consulted via
     * `getLatestCompletionForTask` and its stored `spawned_recurrence_id`
     * is used to roll the same spawn back. The completion row itself is
     * also deleted so a subsequent re-complete starts from a clean slate
     * (no double-counted analytics, no stale spawn link). Audit:
     * `docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md` (Item 2 +
     * residual).
     */
    suspend fun uncompleteTask(id: Long, spawnedRecurrenceId: Long? = null) {
        // Skip work + side effects when the row is already incomplete. The
        // toggle-uncomplete path can be invoked for a task that was never
        // completed (stale UI state racing a notification "Complete" that
        // got undone elsewhere); without this guard the call would still
        // queue a sync update + calendar push + widget refresh for a no-op.
        // Audit: docs/audits/COULDNT_UPDATE_TASK_AUDIT.md (Item 2).
        val current = taskDao.getTaskByIdOnce(id) ?: return
        if (!current.isCompleted) return

        val effectiveSpawnId: Long?
        val latestCompletionId: Long?
        if (spawnedRecurrenceId != null) {
            effectiveSpawnId = spawnedRecurrenceId
            latestCompletionId = null
        } else {
            val latest = taskCompletionRepository.getLatestCompletionForTask(id)
            effectiveSpawnId = latest?.spawnedRecurrenceId
            latestCompletionId = latest?.id
        }
        if (effectiveSpawnId != null) {
            deleteTask(effectiveSpawnId)
        }
        if (latestCompletionId != null) {
            taskCompletionRepository.deleteCompletionById(latestCompletionId)
        }
        taskDao.markIncomplete(id, System.currentTimeMillis())
        syncTracker.trackUpdate(id, "task")
        calendarPushDispatcher.enqueuePushTask(id)
        // Restore the reminder that completeTask cancelled so an Undo
        // snackbar (single or bulk complete) brings back the alarm the
        // user originally set.
        val task = taskDao.getTaskByIdOnce(id)
        val offset = task?.reminderOffset
        val dueDate = task?.dueDate
        if (task != null && offset != null && dueDate != null) {
            reminderScheduler.scheduleReminder(
                taskId = id,
                taskTitle = task.title,
                taskDescription = task.description,
                dueDate = ReminderScheduler.combineDateAndTime(dueDate, task.dueTime),
                reminderOffset = offset
            )
        }
        widgetUpdateManager.updateTaskWidgets()

        // Two-way sync: a manual undo on the linked task should also
        // un-log the habit completion that the forward path inserted.
        current.sourceHabitId?.let { habitId ->
            try {
                habitRepositoryProvider.get().uncompleteHabit(habitId, System.currentTimeMillis())
            } catch (e: Exception) {
                android.util.Log.e("TaskRepository", "Habit sync on uncomplete failed", e)
            }
        }
    }

    suspend fun deleteTask(id: Long) {
        // Cancel pending reminder alarm; the child task_tag / subtask rows
        // are wiped by the ON DELETE CASCADE foreign keys, but AlarmManager
        // alarms are out-of-band and must be cancelled explicitly.
        reminderScheduler.cancelReminder(id)
        taskDao.getSubtasksOnce(id).forEach {
            reminderScheduler.cancelReminder(it.id)
        }
        calendarPushDispatcher.enqueueDeleteTaskEvent(id)
        syncTracker.trackDelete(id, "task")
        taskDao.deleteById(id)
        automationEventBus.emit(AutomationEvent.TaskDeleted(id))
        widgetUpdateManager.updateTaskWidgets()
    }

    fun searchTasks(query: String): Flow<List<TaskEntity>> = taskDao.searchTasks(query)

    fun getArchivedTasks(): Flow<List<TaskEntity>> = taskDao.getArchivedTasks()

    suspend fun archiveTask(id: Long) {
        taskDao.archiveTask(id, System.currentTimeMillis())
        syncTracker.trackUpdate(id, "task")
        widgetUpdateManager.updateTaskWidgets()
    }

    suspend fun unarchiveTask(id: Long) {
        taskDao.unarchiveTask(id, System.currentTimeMillis())
        syncTracker.trackUpdate(id, "task")
        widgetUpdateManager.updateTaskWidgets()
    }

    suspend fun permanentlyDeleteTask(id: Long) {
        reminderScheduler.cancelReminder(id)
        taskDao.getSubtasksOnce(id).forEach {
            reminderScheduler.cancelReminder(it.id)
        }
        calendarPushDispatcher.enqueueDeleteTaskEvent(id)
        syncTracker.trackDelete(id, "task")
        taskDao.permanentlyDelete(id)
        widgetUpdateManager.updateTaskWidgets()
    }

    suspend fun toggleFlag(id: Long): Boolean? {
        val task = taskDao.getTaskByIdOnce(id) ?: return null
        val updated = task.copy(isFlagged = !task.isFlagged, updatedAt = System.currentTimeMillis())
        taskDao.update(updated)
        syncTracker.trackUpdate(id, "task")
        return updated.isFlagged
    }

    suspend fun setFlag(id: Long, flagged: Boolean) {
        val task = taskDao.getTaskByIdOnce(id) ?: return
        if (task.isFlagged == flagged) return
        taskDao.update(task.copy(isFlagged = flagged, updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(id, "task")
    }

    fun searchArchivedTasks(query: String): Flow<List<TaskEntity>> = taskDao.searchArchivedTasks(query)

    suspend fun autoArchiveOldCompleted(daysOld: Int) {
        val cutoff =
            System.currentTimeMillis() - (daysOld.toLong() * 24 * 60 * 60 * 1000)
        taskDao.archiveCompletedBefore(cutoff, System.currentTimeMillis())
    }

    fun getArchivedCount(): Flow<Int> = taskDao.getArchivedCount()

    suspend fun duplicateTask(taskId: Long, includeSubtasks: Boolean = false, copyDueDate: Boolean = false): Long {
        val original = taskDao.getTaskByIdOnce(taskId) ?: return -1L
        val now = System.currentTimeMillis()
        val nextSortOrder = if (original.parentTaskId !=
            null
        ) {
            taskDao.getMaxSubtaskSortOrder(original.parentTaskId) + 1
        } else {
            taskDao.getMaxRootSortOrder() + 1
        }
        val duplicate = buildDuplicateEntity(original, nextSortOrder, now, copyDueDate)
        val newId = taskDao.insert(duplicate)
        syncTracker.trackCreate(newId, "task")
        val tagIds = tagDao.getTagIdsForTaskOnce(taskId)
        for (crossRef in buildTagCrossRefs(tagIds, newId)) {
            tagDao.addTagToTask(crossRef)
        }
        if (includeSubtasks) {
            val originalSubtasks = taskDao.getSubtasksOnce(taskId)
            originalSubtasks.forEachIndexed {
                    index,
                    sub
                ->
                val subCopy = buildSubtaskDuplicate(sub, newId, index, now)
                val newSubId = taskDao.insert(subCopy)
                syncTracker.trackCreate(newSubId, "task")
            }
        }
        calendarPushDispatcher.enqueuePushTask(newId)
        widgetUpdateManager.updateTaskWidgets()
        return newId
    }

    suspend fun batchUpdatePriority(taskIds: List<Long>, priority: Int) {
        if (taskIds.isEmpty()) return
        taskDao.batchUpdatePriority(taskIds, priority)
        taskIds.forEach { syncTracker.trackUpdate(it, "task") }
        widgetUpdateManager.updateTaskWidgets()
    }

    /**
     * Apply a user's manual Eisenhower quadrant choice and stamp
     * `user_overrode_quadrant = 1` so subsequent auto-classifications will
     * skip this row. [quadrant] may be [EisenhowerQuadrant.UNCLASSIFIED],
     * which clears the quadrant but still marks the row as user-overridden
     * (user explicitly chose "no quadrant" — don't re-classify it).
     */
    suspend fun setQuadrantManual(taskId: Long, quadrant: EisenhowerQuadrant) {
        taskDao.setManualQuadrant(
            id = taskId,
            quadrant = quadrant.code,
            reason = "Manually moved"
        )
        syncTracker.trackUpdate(taskId, "task")
        widgetUpdateManager.updateTaskWidgets()
    }

    /**
     * Explicit user-initiated reclassification. Clears any prior manual
     * override and runs the classifier inline so the caller can surface
     * success/failure to the user. Bypasses [UserPreferencesDataStore]'s
     * `autoClassifyEnabled` gate — that preference governs background
     * classification on insert/update only; an explicit "Reclassify With AI"
     * tap is the user telling the app they want it now.
     */
    suspend fun reclassify(taskId: Long): Result<Unit> {
        taskDao.clearManualQuadrantOverride(taskId)
        syncTracker.trackUpdate(taskId, "task")
        val task = taskDao.getTaskByIdOnce(taskId)
            ?: return Result.failure(IllegalStateException("Task $taskId not found"))
        val classification = eisenhowerClassifier.classify(task).getOrElse {
            return Result.failure(it)
        }
        val code = classification.quadrant.code
            ?: return Result.failure(IllegalStateException("Unknown quadrant"))
        taskDao.updateEisenhowerQuadrantIfNotOverridden(
            id = taskId,
            quadrant = code,
            reason = classification.reason
        )
        syncTracker.trackUpdate(taskId, "task")
        widgetUpdateManager.updateTaskWidgets()
        return Result.success(Unit)
    }

    suspend fun batchReschedule(taskIds: List<Long>, newDueDate: Long?) {
        if (taskIds.isEmpty()) return
        taskDao.batchReschedule(taskIds, newDueDate)
        for (id in taskIds) {
            val updated =
                taskDao.getTaskByIdOnce(id) ?: continue
            val offset = updated.reminderOffset
            if (offset != null &&
                newDueDate != null
            ) {
                reminderScheduler.scheduleReminder(
                    taskId = updated.id,
                    taskTitle = updated.title,
                    taskDescription = updated.description,
                    dueDate = ReminderScheduler.combineDateAndTime(newDueDate, updated.dueTime),
                    reminderOffset = offset
                )
            } else {
                reminderScheduler.cancelReminder(id)
            }
            calendarPushDispatcher.enqueuePushTask(updated.id)
            syncTracker.trackUpdate(id, "task")
        }
        widgetUpdateManager.updateTaskWidgets()
    }

    suspend fun batchMoveToProject(taskIds: List<Long>, newProjectId: Long?) {
        if (taskIds.isEmpty()) return
        taskDao.batchMoveToProject(taskIds, newProjectId)
        taskIds.forEach { syncTracker.trackUpdate(it, "task") }
        widgetUpdateManager.updateTaskWidgets()
    }

    suspend fun moveToProject(taskId: Long, projectId: Long?, cascadeSubtasks: Boolean = false): List<Long> {
        val task = taskDao.getTaskByIdOnce(taskId) ?: return emptyList()
        val subtasks = if (cascadeSubtasks) taskDao.getSubtasksOnce(taskId) else emptyList()
        val idsToMove = buildMoveTargetIds(task, subtasks, cascadeSubtasks)
        taskDao.batchMoveToProject(idsToMove, projectId)
        idsToMove.forEach { syncTracker.trackUpdate(it, "task") }
        widgetUpdateManager.updateTaskWidgets()
        return idsToMove
    }

    suspend fun batchAddTag(taskIds: List<Long>, tagId: Long) {
        if (taskIds.isEmpty()) return
        taskDao.batchAddTag(taskIds, tagId)
        taskIds.forEach { syncTracker.trackUpdate(it, "task") }
    }

    suspend fun batchRemoveTag(taskIds: List<Long>, tagId: Long) {
        if (taskIds.isEmpty()) return
        taskDao.batchRemoveTag(taskIds, tagId)
        taskIds.forEach { syncTracker.trackUpdate(it, "task") }
    }

    fun getTasksGroupedByDate(): Flow<Map<String, List<TaskEntity>>> = taskDao.getIncompleteRootTasks().map { tasks ->
        groupByDate(
            tasks
        )
    }

    private fun groupByDate(tasks: List<TaskEntity>): Map<String, List<TaskEntity>> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfToday = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val startOfTomorrow = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val startOfDayAfterTomorrow = calendar.timeInMillis
        calendar.timeInMillis = startOfToday
        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        calendar.add(Calendar.WEEK_OF_YEAR, 1)
        val endOfThisWeek = calendar.timeInMillis
        val grouped = linkedMapOf<String, MutableList<TaskEntity>>()
        for (task in tasks) {
            val bucket = when {
                task.dueDate == null -> "Later"
                task.dueDate < startOfToday -> "Overdue"
                task.dueDate <
                    startOfTomorrow -> "Today"
                task.dueDate < startOfDayAfterTomorrow -> "Tomorrow"
                task.dueDate < endOfThisWeek -> "This Week"
                else -> "Later"
            }
            grouped.getOrPut(bucket) { mutableListOf() }.add(task)
        }
        val order = listOf("Overdue", "Today", "Tomorrow", "This Week", "Later")
        return order.filter { it in grouped }.associateWith { grouped.getValue(it) }
    }

    companion object {
        fun buildDuplicateEntity(
            original: TaskEntity,
            nextSortOrder: Int,
            now: Long,
            copyDueDate: Boolean = false
        ): TaskEntity = original.copy(
            id = 0,
            title = "Copy of ${original.title}",
            dueDate = if (copyDueDate) original.dueDate else null,
            dueTime = if (copyDueDate) original.dueTime else null,
            plannedDate = null,
            isCompleted = false,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            reminderOffset = if (copyDueDate) original.reminderOffset else null,
            archivedAt = null,
            scheduledStartTime = null,
            sortOrder = nextSortOrder
        )

        fun buildSubtaskDuplicate(
            originalSubtask: TaskEntity,
            newParentId: Long,
            sortOrder: Int,
            now: Long
        ): TaskEntity = originalSubtask
            .copy(
                id = 0,
                parentTaskId = newParentId,
                dueDate = null,
                dueTime = null,
                plannedDate = null,
                isCompleted = false,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
                reminderOffset = null,
                archivedAt = null,
                scheduledStartTime = null,
                sortOrder = sortOrder
            )

        fun buildTagCrossRefs(tagIds: List<Long>, newTaskId: Long): List<TaskTagCrossRef> = tagIds.map { tagId ->
            TaskTagCrossRef(
                taskId = newTaskId,
                tagId = tagId
            )
        }

        fun buildMoveTargetIds(
            task: TaskEntity,
            subtasks: List<TaskEntity>,
            cascadeSubtasks: Boolean
        ): List<Long> = if (cascadeSubtasks) {
            listOf(task.id) +
                subtasks.map { it.id }
        } else {
            listOf(task.id)
        }

        fun applyProjectMove(
            task: TaskEntity,
            subtasks: List<TaskEntity>,
            newProjectId: Long?,
            cascadeSubtasks: Boolean,
            now: Long
        ): List<TaskEntity> {
            val updatedParent = task.copy(projectId = newProjectId, updatedAt = now)
            return if (cascadeSubtasks) {
                listOf(updatedParent) +
                    subtasks.map { it.copy(projectId = newProjectId, updatedAt = now) }
            } else {
                listOf(updatedParent)
            }
        }
    }
}
