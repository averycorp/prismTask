package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.UsageLogDao
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class TaskSuggestions(val suggestedTags: List<SuggestedTag> = emptyList(), val suggestedProject: SuggestedProject? = null)

data class SuggestedTag(val tag: TagEntity, val confidence: Float, val reason: String)

data class SuggestedProject(val project: ProjectEntity, val confidence: Float, val reason: String)

private val STOP_WORDS = setOf(
    "the",
    "a",
    "an",
    "is",
    "at",
    "to",
    "for",
    "of",
    "in",
    "on",
    "and",
    "or",
    "but",
    "with",
    "my",
    "do",
    "get",
    "go",
    "be"
)

fun extractKeywords(title: String): List<String> =
    title
        .lowercase()
        .split(Regex("""\s+"""))
        .filter { it.length >= 3 && it !in STOP_WORDS }

@Singleton
class SuggestionEngine
@Inject
constructor(
    private val usageLogDao: UsageLogDao,
    private val tagRepository: TagRepository,
    private val projectRepository: ProjectRepository
) {
    fun getSuggestions(
        title: String,
        currentTagIds: List<Long>,
        currentProjectId: Long?,
        config: com.averycorp.prismtask.data.preferences.SuggestionConfig =
            com.averycorp.prismtask.data.preferences.SuggestionConfig()
    ): Flow<TaskSuggestions> {
        val keywords = extractKeywords(title)
        if (keywords.isEmpty()) return flowOf(TaskSuggestions())

        val tagFlows = keywords.map { kw -> usageLogDao.getTagsForKeyword(kw) }
        val projectFlows = keywords.map { kw -> usageLogDao.getProjectsForKeyword(kw) }

        val allTags = tagRepository.getAllTags()
        val allProjects = projectRepository.getAllProjects()

        return combine(
            combine(tagFlows) { arrays -> arrays.flatMap { it } },
            combine(projectFlows) { arrays -> arrays.flatMap { it } },
            allTags,
            allProjects
        ) { tagFreqs, projectFreqs, tags, projects ->
            // Merge tag frequencies
            val tagCounts = tagFreqs
                .groupBy { it.entityId }
                .mapValues { (_, freqs) -> freqs.sumOf { it.count } }
                .filterKeys { it !in currentTagIds }

            val totalTagLogs = tagFreqs.sumOf { it.count }.coerceAtLeast(1)
            val suggestedTags = tagCounts
                .map { (tagId, count) ->
                    val tag = tags.find { it.id == tagId }
                    val confidence = count.toFloat() / totalTagLogs
                    if (tag != null && confidence > config.tagThreshold) {
                        SuggestedTag(tag, confidence, "Used in $count similar tasks")
                    } else {
                        null
                    }
                }.filterNotNull()
                .sortedByDescending { it.confidence }
                .take(config.maxResults)

            // Merge project frequencies
            val projectCounts = projectFreqs
                .groupBy { it.entityId }
                .mapValues { (_, freqs) -> freqs.sumOf { it.count } }
                .filterKeys { it != currentProjectId }

            val suggestedProject = projectCounts
                .maxByOrNull { it.value }
                ?.let { (projId, count) ->
                    val project = projects.find { it.id == projId }
                    val totalProjLogs = projectFreqs.sumOf { it.count }.coerceAtLeast(1)
                    val confidence = count.toFloat() / totalProjLogs
                    if (project != null && confidence > config.projectThreshold) {
                        SuggestedProject(project, confidence, "Used in $count similar tasks")
                    } else {
                        null
                    }
                }

            TaskSuggestions(suggestedTags = suggestedTags, suggestedProject = suggestedProject)
        }
    }
}
