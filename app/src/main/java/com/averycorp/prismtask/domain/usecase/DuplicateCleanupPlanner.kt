package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity

/**
 * Plans which tasks, habits, and projects should be removed to clean up
 * accidental duplicates.
 *
 * Detection rule:
 *  - Tasks: grouped by (normalized title, dueDate). Only active, non-archived,
 *    non-completed root tasks (no parent) are considered — subtasks ride
 *    along via foreign-key CASCADE when their parent is deleted, and
 *    completed/archived work is preserved.
 *  - Habits: grouped by (normalized name, frequencyPeriod, targetFrequency).
 *    Built-in habits (isBuiltIn && templateKey != null) are additionally
 *    grouped by templateKey so that name-drifted duplicates are caught too.
 *    Archived habits are skipped.
 *  - Projects: grouped by normalized name. Archived projects are skipped.
 *
 * Keep rule: for each group of size > 1, the "most complete" entry is kept
 * (highest completeness score). Ties are broken by oldest createdAt, then by
 * lowest id. All other entries in the group are returned as the IDs to delete.
 *
 * Normalization: title/name are trimmed and lowercased. Null dueDates on
 * tasks are grouped together (two "buy milk" tasks with no due date collapse
 * to a single duplicate group).
 */
object DuplicateCleanupPlanner {
    /** Per-task data the planner needs for its completeness score. */
    data class TaskExtras(val subtaskCount: Int, val tagCount: Int)

    /** Per-habit data the planner needs for its completeness score. */
    data class HabitExtras(val completionCount: Int, val logCount: Int)

    /**
     * Describes a merge operation: the habit with [keeperId] is kept and
     * all habits in [loserIds] should have their completions reassigned to
     * [keeperId] before being deleted.
     */
    data class HabitMerge(val keeperId: Long, val loserIds: List<Long>)

    fun findTaskDuplicatesToDelete(
        tasks: List<TaskEntity>,
        extras: Map<Long, TaskExtras>
    ): List<Long> {
        val candidates = tasks.filter {
            it.archivedAt == null && !it.isCompleted && it.parentTaskId == null
        }
        return candidates
            .groupBy { normalize(it.title) to it.dueDate }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                val keeper = group.maxWithOrNull(
                    compareBy<TaskEntity> { scoreTask(it, extras[it.id]) }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.id }
                ) ?: return@flatMap emptyList()
                group.filter { it.id != keeper.id }.map { it.id }
            }
    }

    /**
     * Returns the full merge plan for duplicate habits: each [HabitMerge]
     * names the keeper and the losers (whose completions should be reassigned
     * to the keeper before deletion).
     *
     * Duplicate detection runs two passes:
     *  1. Name-based: habits with the same (normalized name, frequencyPeriod,
     *     targetFrequency) are duplicates.
     *  2. Template-key-based: built-in habits (isBuiltIn = true) that share
     *     the same non-null templateKey are duplicates even when their names
     *     have drifted.
     *
     * A habit already covered by the template-key pass is excluded from the
     * name-based pass to prevent it appearing in two separate merge groups.
     */
    fun planHabitDuplicates(
        habits: List<HabitEntity>,
        extras: Map<Long, HabitExtras>
    ): List<HabitMerge> {
        val candidates = habits.filter { !it.isArchived }

        val groups = mutableListOf<List<HabitEntity>>()
        val coveredByTemplateKey = mutableSetOf<Long>()

        // Pass 1: group built-in habits by templateKey.
        candidates
            .filter { it.isBuiltIn && it.templateKey != null }
            .groupBy { it.templateKey!! }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                groups.add(group)
                group.forEach { coveredByTemplateKey.add(it.id) }
            }

        // Pass 2: group remaining habits by (name, frequencyPeriod, targetFrequency).
        candidates
            .filter { it.id !in coveredByTemplateKey }
            .groupBy { Triple(normalize(it.name), it.frequencyPeriod, it.targetFrequency) }
            .values
            .filter { it.size > 1 }
            .forEach { groups.add(it) }

        return groups.map { group ->
            val keeper = group.maxWith(
                compareBy<HabitEntity> { scoreHabit(it, extras[it.id]) }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.id }
            )
            HabitMerge(
                keeperId = keeper.id,
                loserIds = group.filter { it.id != keeper.id }.map { it.id }
            )
        }
    }

    fun findHabitDuplicatesToDelete(
        habits: List<HabitEntity>,
        extras: Map<Long, HabitExtras>
    ): List<Long> = planHabitDuplicates(habits, extras).flatMap { it.loserIds }

    fun findProjectDuplicatesToDelete(projects: List<ProjectEntity>): List<Long> {
        val candidates = projects.filter { it.archivedAt == null }
        return candidates
            .groupBy { normalize(it.name) }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                val keeper = group.maxWithOrNull(
                    compareBy<ProjectEntity> { scoreProject(it) }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.id }
                ) ?: return@flatMap emptyList()
                group.filter { it.id != keeper.id }.map { it.id }
            }
    }

    private fun normalize(text: String): String = text.trim().lowercase()

    private fun scoreTask(task: TaskEntity, extras: TaskExtras?): Int {
        var score = 0
        if (!task.description.isNullOrBlank()) score++
        if (!task.notes.isNullOrBlank()) score++
        if (task.reminderOffset != null) score++
        if (task.recurrenceRule != null) score++
        if (task.projectId != null) score++
        if (task.estimatedDuration != null) score++
        if (task.dueTime != null) score++
        score += extras?.subtaskCount ?: 0
        score += extras?.tagCount ?: 0
        return score
    }

    private fun scoreHabit(habit: HabitEntity, extras: HabitExtras?): Int {
        var score = 0
        if (!habit.description.isNullOrBlank()) score++
        if (habit.reminderTime != null) score++
        if (!habit.category.isNullOrBlank()) score++
        if (habit.reminderIntervalMillis != null) score++
        score += extras?.completionCount ?: 0
        score += extras?.logCount ?: 0
        return score
    }

    private fun scoreProject(project: ProjectEntity): Int {
        var score = 0
        if (!project.description.isNullOrBlank()) score++
        if (project.themeColorKey != null) score++
        if (project.startDate != null) score++
        if (project.endDate != null) score++
        return score
    }
}
