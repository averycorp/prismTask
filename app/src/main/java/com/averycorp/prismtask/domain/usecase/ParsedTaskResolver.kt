package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.RecurrenceType
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedTask(
    val title: String,
    val dueDate: Long?,
    val dueTime: Long?,
    val tagIds: List<Long>,
    val projectId: Long?,
    val priority: Int,
    val recurrenceRule: RecurrenceRule?,
    val unmatchedTags: List<String>,
    val unmatchedProject: String?,
    /** Work-Life Balance category carried over from NLP parsing. */
    val lifeCategory: String? = null,
    /** Reward / output mode carried over from NLP parsing (`#work-mode` etc). */
    val taskMode: String? = null,
    /** Start-friction load carried over from NLP parsing (`#easy-load` etc). */
    val cognitiveLoad: String? = null
)

@Singleton
class ParsedTaskResolver
@Inject
constructor(
    private val tagRepository: TagRepository,
    private val projectRepository: ProjectRepository
) {
    suspend fun resolve(parsed: ParsedTask): ResolvedTask {
        val tagIds = mutableListOf<Long>()
        val unmatchedTags = mutableListOf<String>()

        val allTags = tagRepository.getAllTags().firstOrNull() ?: emptyList()
        for (tagName in parsed.tags) {
            val match = allTags.find { it.name.equals(tagName, ignoreCase = true) }
            if (match != null) {
                tagIds.add(match.id)
            } else {
                unmatchedTags.add(tagName)
            }
        }

        var projectId: Long? = null
        var unmatchedProject: String? = null
        if (parsed.projectName != null) {
            val allProjects = projectRepository.getAllProjects().firstOrNull() ?: emptyList()
            // Skip archived projects so a recycled name re-resolves to a
            // fresh project rather than silently auto-assigning the retired one.
            val match = allProjects.find {
                it.status != "ARCHIVED" &&
                    it.name.equals(parsed.projectName, ignoreCase = true)
            }
            if (match != null) {
                projectId = match.id
            } else {
                unmatchedProject = parsed.projectName
            }
        }

        val recurrenceRule = when (parsed.recurrenceHint) {
            "daily" -> RecurrenceRule(type = RecurrenceType.DAILY, interval = 1)
            "weekly" -> RecurrenceRule(type = RecurrenceType.WEEKLY, interval = 1)
            "monthly" -> RecurrenceRule(type = RecurrenceType.MONTHLY, interval = 1)
            "yearly" -> RecurrenceRule(type = RecurrenceType.YEARLY, interval = 1)
            else -> null
        }

        return ResolvedTask(
            title = parsed.title,
            dueDate = parsed.dueDate,
            dueTime = parsed.dueTime,
            tagIds = tagIds,
            projectId = projectId,
            priority = parsed.priority,
            recurrenceRule = recurrenceRule,
            unmatchedTags = unmatchedTags,
            unmatchedProject = unmatchedProject,
            lifeCategory = parsed.lifeCategory,
            taskMode = parsed.taskMode,
            cognitiveLoad = parsed.cognitiveLoad
        )
    }
}
