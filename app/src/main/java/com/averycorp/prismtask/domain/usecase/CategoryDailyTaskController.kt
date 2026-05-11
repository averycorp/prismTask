package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.RecurrenceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spawns and reaps the recurring DAILY [TaskEntity] tied to a Daily
 * Essentials category (leisure slots, custom leisure sections, courses).
 *
 * Each category owns at most one persistent task. The category-side
 * preference / column stores the resulting task id so the toggle can
 * find and delete it later. Daily spawning of subsequent occurrences is
 * handled by the existing RecurrenceEngine on task completion.
 */
@Singleton
class CategoryDailyTaskController
@Inject
constructor(private val taskRepository: TaskRepository) {
    /**
     * Creates a recurring DAILY task with `"$emoji $label"` as the title
     * if [existingId] is null or points at a missing row. Returns the
     * id of the live task — pass it back into [removeDailyTask] when
     * the toggle flips off.
     */
    suspend fun ensureDailyTask(label: String, emoji: String, existingId: Long?): Long {
        existingId?.let { id ->
            if (taskRepository.getTaskByIdOnce(id) != null) return id
        }
        val title = buildTitle(label, emoji)
        val recurrence = RecurrenceConverter.toJson(RecurrenceRule(type = RecurrenceType.DAILY))
        return taskRepository.addTask(
            title = title,
            recurrenceRule = recurrence
        )
    }

    /** Deletes the spawned task (no-op when [taskId] is null or already gone). */
    suspend fun removeDailyTask(taskId: Long?) {
        val id = taskId ?: return
        if (taskRepository.getTaskByIdOnce(id) == null) return
        taskRepository.deleteTask(id)
    }

    private fun buildTitle(label: String, emoji: String): String {
        val cleanLabel = label.trim().ifEmpty { "Daily Task" }
        val cleanEmoji = emoji.trim()
        return if (cleanEmoji.isEmpty()) cleanLabel else "$cleanEmoji $cleanLabel"
    }
}
