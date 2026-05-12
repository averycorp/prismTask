package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.util.DayBoundary
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spawns and reaps the [com.averycorp.prismtask.data.local.entity.TaskEntity]
 * tied to a Daily Essentials category (leisure slots, custom leisure sections,
 * courses).
 *
 * Each category owns at most one task. The category-side preference / column
 * stores the resulting task id so the toggle can find and delete it later.
 *
 * The spawned task is due *today* and is non-recurring — so completing it
 * does not carry the item forward into tomorrow. This mirrors the habit
 * daily-task behaviour in
 * [com.averycorp.prismtask.domain.usecase.HabitDailyTaskGenerator].
 */
@Singleton
class CategoryDailyTaskController
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) {
    /**
     * Creates a non-recurring task due today titled `"$emoji $label"` if
     * [existingId] is null or points at a missing row. Returns the id of
     * the live task — pass it back into [removeDailyTask] when the toggle
     * flips off.
     */
    suspend fun ensureDailyTask(label: String, emoji: String, existingId: Long?): Long {
        existingId?.let { id ->
            if (taskRepository.getTaskByIdOnce(id) != null) return id
        }
        val title = buildTitle(label, emoji)
        val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
        val dueDate = DayBoundary.startOfCurrentDay(dayStartHour)
        return taskRepository.addTask(
            title = title,
            dueDate = dueDate
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
