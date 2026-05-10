package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.ExternalAnchorRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskDependencyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.ExternalAnchor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared schedule-import orchestrator for the F.8 "Import Project from
 * Schedule File" feature. The use case is split into two stages so the
 * preview screen can render the parsed shape before any rows hit Room:
 *
 *  - [parse] runs Haiku / regex and returns an [ImportPlan] describing
 *    what *would* be created. No Room writes.
 *  - [materialise] takes a plan + optional [ImportExclusions] and
 *    inserts the rows. Excluded sections are skipped at the materialise
 *    boundary (no row inserted) — task / risk indices match plan-relative
 *    position, see [ImportExclusions].
 *  - [importContent] composes the two for callers that don't need a
 *    preview (kept for backward compat — same signature, same outcome).
 *
 * `createProject` controls the orchestration:
 *
 * - `createProject = true`:
 *     - Rich extras present (phases / risks / anchors / dependencies) →
 *       [ImportPlan.Rich] → [ImportOutcome.Rich].
 *     - Otherwise → [ImportPlan.FlatProject] → [ImportOutcome.FlatProject].
 * - `createProject = false`:
 *     - Skip the rich parser entirely; flat parser → [ImportPlan.FlatOrphans]
 *       → [ImportOutcome.FlatOrphans].
 * - Either path: no parser produces output → null plan → [ImportOutcome.Unparseable].
 */
@Singleton
class ProjectImporter @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val taskRepository: TaskRepository,
    private val externalAnchorRepository: ExternalAnchorRepository,
    private val taskDependencyRepository: TaskDependencyRepository,
    private val checklistParser: ChecklistParser,
    private val todoListParser: TodoListParser
) {
    suspend fun parse(content: String, createProject: Boolean): ImportPlan? {
        if (!createProject) {
            val flat = todoListParser.parse(content) ?: return null
            return ImportPlan.FlatOrphans(listName = flat.name, items = flat.items)
        }

        val checklist = checklistParser.parse(content)
        val hasRichExtras = checklist != null && (
            checklist.phases.isNotEmpty() ||
                checklist.risks.isNotEmpty() ||
                checklist.externalAnchors.isNotEmpty() ||
                checklist.taskDependencies.isNotEmpty()
            )
        if (checklist != null && hasRichExtras) {
            return ImportPlan.Rich(checklist)
        }

        val flat = todoListParser.parse(content) ?: return null
        return ImportPlan.FlatProject(
            projectName = flat.name ?: "Imported List",
            items = flat.items
        )
    }

    suspend fun materialise(
        plan: ImportPlan,
        exclusions: ImportExclusions = ImportExclusions.EMPTY
    ): ImportOutcome = when (plan) {
        is ImportPlan.Rich -> materialiseRich(plan.result, exclusions)
        is ImportPlan.FlatProject -> materialiseFlatAsProject(plan, exclusions)
        is ImportPlan.FlatOrphans -> materialiseFlatAsOrphans(plan, exclusions)
    }

    suspend fun importContent(content: String, createProject: Boolean): ImportOutcome {
        val plan = parse(content, createProject) ?: return ImportOutcome.Unparseable
        return materialise(plan)
    }

    private suspend fun materialiseRich(
        result: ComprehensiveImportResult,
        exclusions: ImportExclusions
    ): ImportOutcome.Rich {
        val projectId = projectRepository.addProject(
            name = result.project.name,
            color = result.project.color,
            icon = result.project.icon
        )

        val phaseIdsByName = mutableMapOf<String, Long>()
        for (phase in result.phases) {
            val id = projectRepository.addPhase(
                projectId = projectId,
                title = phase.name,
                description = phase.description,
                startDate = phase.startDate,
                endDate = phase.endDate
            )
            phaseIdsByName[phase.name] = id
        }

        val taskIdsByTitle = mutableMapOf<String, Long>()
        var taskCount = 0
        result.tasks.forEachIndexed { index, task ->
            if (index in exclusions.excludedTaskIndices) return@forEachIndexed
            val taskId = insertChecklistTask(task, projectId, parentTaskId = null)
            if (taskId > 0) {
                taskCount++
                taskIdsByTitle[task.title] = taskId
                taskCount += insertChecklistSubtasks(task.subtasks, projectId, parentTaskId = taskId)
            }
        }

        var riskCount = 0
        result.risks.forEachIndexed { index, risk ->
            if (index in exclusions.excludedRiskIndices) return@forEachIndexed
            projectRepository.addRisk(
                projectId = projectId,
                title = risk.title,
                level = risk.level.uppercase().takeIf { it in setOf("LOW", "MEDIUM", "HIGH") } ?: "MEDIUM",
                mitigation = risk.description
            )
            riskCount++
        }

        for (anchor in result.externalAnchors) {
            // v1 only materialises calendar_deadline anchors. NumericThreshold
            // and BooleanGate require info the import prompt doesn't extract;
            // skip silently rather than fail the import.
            if (anchor.type != "calendar_deadline" || anchor.targetDate == null) continue
            externalAnchorRepository.addAnchor(
                projectId = projectId,
                label = anchor.title,
                anchor = ExternalAnchor.CalendarDeadline(epochMs = anchor.targetDate),
                phaseId = anchor.phaseName?.let(phaseIdsByName::get)
            )
        }

        for (dep in result.taskDependencies) {
            val blockerId = taskIdsByTitle[dep.blockerTitle] ?: continue
            val blockedId = taskIdsByTitle[dep.blockedTitle] ?: continue
            taskDependencyRepository.addDependency(
                blockerTaskId = blockerId,
                blockedTaskId = blockedId
            )
        }

        return ImportOutcome.Rich(
            projectName = result.project.name,
            taskCount = taskCount,
            phaseCount = result.phases.size,
            riskCount = riskCount
        )
    }

    private suspend fun materialiseFlatAsProject(
        plan: ImportPlan.FlatProject,
        exclusions: ImportExclusions
    ): ImportOutcome.FlatProject {
        val projectId = projectRepository.addProject(name = plan.projectName)
        var count = 0
        plan.items.forEachIndexed { index, item ->
            if (index in exclusions.excludedTaskIndices) return@forEachIndexed
            val taskId = insertParsedItem(item, projectId, parentTaskId = null)
            if (taskId > 0) {
                count++
                count += insertParsedSubtasks(item.subtasks, projectId, parentTaskId = taskId)
            }
        }
        return ImportOutcome.FlatProject(projectName = plan.projectName, taskCount = count)
    }

    private suspend fun materialiseFlatAsOrphans(
        plan: ImportPlan.FlatOrphans,
        exclusions: ImportExclusions
    ): ImportOutcome.FlatOrphans {
        var count = 0
        plan.items.forEachIndexed { index, item ->
            if (index in exclusions.excludedTaskIndices) return@forEachIndexed
            val taskId = insertParsedItem(item, projectId = null, parentTaskId = null)
            if (taskId > 0) {
                count++
                count += insertParsedSubtasks(item.subtasks, projectId = null, parentTaskId = taskId)
            }
        }
        return ImportOutcome.FlatOrphans(listName = plan.listName, taskCount = count)
    }

    private suspend fun insertChecklistSubtasks(
        subtasks: List<ChecklistParsedTask>,
        projectId: Long,
        parentTaskId: Long
    ): Int {
        var count = 0
        for (sub in subtasks) {
            val subId = insertChecklistTask(sub, projectId, parentTaskId = parentTaskId)
            if (subId > 0) {
                count++
                count += insertChecklistSubtasks(sub.subtasks, projectId, parentTaskId = subId)
            }
        }
        return count
    }

    private suspend fun insertParsedSubtasks(
        subtasks: List<ParsedTodoItem>,
        projectId: Long?,
        parentTaskId: Long
    ): Int {
        var count = 0
        for (sub in subtasks) {
            val subId = insertParsedItem(sub, projectId, parentTaskId = parentTaskId)
            if (subId > 0) {
                count++
                count += insertParsedSubtasks(sub.subtasks, projectId, parentTaskId = subId)
            }
        }
        return count
    }

    private suspend fun insertChecklistTask(
        task: ChecklistParsedTask,
        projectId: Long,
        parentTaskId: Long?
    ): Long {
        val now = System.currentTimeMillis()
        return taskRepository.insertTask(
            TaskEntity(
                title = task.title,
                description = task.description,
                dueDate = task.dueDate,
                priority = task.priority,
                isCompleted = task.completed,
                completedAt = if (task.completed) now else null,
                projectId = projectId,
                parentTaskId = parentTaskId,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private suspend fun insertParsedItem(
        item: ParsedTodoItem,
        projectId: Long?,
        parentTaskId: Long?
    ): Long {
        val now = System.currentTimeMillis()
        return taskRepository.insertTask(
            TaskEntity(
                title = item.title,
                description = item.description,
                dueDate = item.dueDate,
                priority = item.priority,
                isCompleted = item.completed,
                completedAt = if (item.completed) now else null,
                projectId = projectId,
                parentTaskId = parentTaskId,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}

/**
 * Parsed import shape the preview screen renders. Materialise-time the
 * caller passes the plan back through [ProjectImporter.materialise]
 * along with optional per-section [ImportExclusions].
 */
sealed interface ImportPlan {
    val projectName: String
    val taskTitles: List<String>
    val riskTitles: List<String>

    data class Rich(val result: ComprehensiveImportResult) : ImportPlan {
        override val projectName: String get() = result.project.name
        override val taskTitles: List<String> get() = result.tasks.map { it.title }
        override val riskTitles: List<String> get() = result.risks.map { it.title }
        val phases: List<ParsedProjectPhaseDomain> get() = result.phases
        val externalAnchors: List<ParsedExternalAnchorDomain> get() = result.externalAnchors
        val taskDependencies: List<ParsedTaskDependencyDomain> get() = result.taskDependencies
    }

    data class FlatProject(
        override val projectName: String,
        val items: List<ParsedTodoItem>
    ) : ImportPlan {
        override val taskTitles: List<String> get() = items.map { it.title }
        override val riskTitles: List<String> get() = emptyList()
    }

    data class FlatOrphans(
        val listName: String?,
        val items: List<ParsedTodoItem>
    ) : ImportPlan {
        override val projectName: String get() = listName ?: "Imported List"
        override val taskTitles: List<String> get() = items.map { it.title }
        override val riskTitles: List<String> get() = emptyList()
    }
}

/**
 * Per-section opt-out indices, plan-relative. Materialise drops any
 * task / risk whose index appears in the matching set; non-listed
 * sections (phases, anchors, dependencies) are read-only in v1 and
 * always materialised.
 */
data class ImportExclusions(
    val excludedTaskIndices: Set<Int> = emptySet(),
    val excludedRiskIndices: Set<Int> = emptySet()
) {
    companion object {
        val EMPTY = ImportExclusions()
    }
}

sealed interface ImportOutcome {
    data class Rich(
        val projectName: String,
        val taskCount: Int,
        val phaseCount: Int,
        val riskCount: Int
    ) : ImportOutcome

    data class FlatProject(val projectName: String, val taskCount: Int) : ImportOutcome

    data class FlatOrphans(val listName: String?, val taskCount: Int) : ImportOutcome

    object Unparseable : ImportOutcome
}
