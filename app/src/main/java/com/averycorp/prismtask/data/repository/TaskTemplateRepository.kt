package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.CognitiveLoadClassifier
import com.averycorp.prismtask.domain.usecase.DateShortcuts
import com.averycorp.prismtask.domain.usecase.LifeCategoryClassifier
import com.averycorp.prismtask.domain.usecase.TaskModeClassifier
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskTemplateRepository
@Inject
constructor(
    private val templateDao: TaskTemplateDao,
    private val taskDao: TaskDao,
    private val tagDao: TagDao,
    private val advancedTuningPreferences: com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
) {
    /** Latest snapshot of user-supplied life-category keywords; see TaskRepository for the pattern. */
    @Volatile
    private var latestLifeCategoryCustomKeywords: com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords =
        com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords()

    @Volatile
    private var latestTaskModeCustomKeywords: com.averycorp.prismtask.data.preferences.TaskModeCustomKeywords =
        com.averycorp.prismtask.data.preferences.TaskModeCustomKeywords()

    @Volatile
    private var latestCognitiveLoadCustomKeywords: com.averycorp.prismtask.data.preferences.CognitiveLoadCustomKeywords =
        com.averycorp.prismtask.data.preferences.CognitiveLoadCustomKeywords()

    private val keywordScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    init {
        keywordScope.launch {
            advancedTuningPreferences.getLifeCategoryCustomKeywords().collect {
                latestLifeCategoryCustomKeywords = it
            }
        }
        keywordScope.launch {
            advancedTuningPreferences.getTaskModeCustomKeywords().collect {
                latestTaskModeCustomKeywords = it
            }
        }
        keywordScope.launch {
            advancedTuningPreferences.getCognitiveLoadCustomKeywords().collect {
                latestCognitiveLoadCustomKeywords = it
            }
        }
    }

    private fun resolveTemplateLifeCategory(title: String, description: String?, taskId: Long): String {
        val classifier = LifeCategoryClassifier.withCustomKeywords(latestLifeCategoryCustomKeywords)
        val guess = classifier.classify(title, description)
        val source = if (guess == LifeCategory.UNCATEGORIZED) "default" else "classifier"
        android.util.Log.i(
            "PrismSync",
            "lifeCategory.resolved | taskId=$taskId | source=$source | result=${guess.name}"
        )
        return guess.name
    }

    private fun resolveTemplateTaskMode(title: String, description: String?): String =
        TaskModeClassifier.withCustomKeywords(latestTaskModeCustomKeywords)
            .classify(title, description)
            .name

    private fun resolveTemplateCognitiveLoad(title: String, description: String?): String =
        CognitiveLoadClassifier.withCustomKeywords(latestCognitiveLoadCustomKeywords)
            .classify(title, description)
            .name

    fun getAllTemplates(): Flow<List<TaskTemplateEntity>> = templateDao.getAllTemplates()

    fun getTemplatesByCategory(category: String): Flow<List<TaskTemplateEntity>> =
        templateDao.getTemplatesByCategory(category)

    fun getAllCategories(): Flow<List<String>> = templateDao.getAllCategories()

    suspend fun getTemplateById(id: Long): TaskTemplateEntity? = templateDao.getTemplateById(id)

    fun searchTemplates(query: String): Flow<List<TaskTemplateEntity>> =
        templateDao.searchTemplates(query)

    suspend fun createTemplate(template: TaskTemplateEntity): Long =
        templateDao.insertTemplate(template)

    /**
     * Persists an edit to a template. Because users can edit the seeded
     * built-ins in place, any update flips [TaskTemplateEntity.isBuiltIn] to
     * `false` — the intent is that a modified default is no longer a "built-in",
     * it's the user's template now. We intentionally do NOT preserve the flag
     * (e.g., "it was once a built-in") because the UI treats built-in purely as
     * "shipped as-is by us".
     */
    suspend fun updateTemplate(template: TaskTemplateEntity) =
        templateDao.updateTemplate(
            template.copy(
                isBuiltIn = false,
                updatedAt = System.currentTimeMillis()
            )
        )

    suspend fun deleteTemplate(id: Long) = templateDao.deleteTemplate(id)

    /**
     * Removes [category] from every template that currently has it set,
     * setting their category to `null`. This is the "Manage Categories →
     * Delete" action in [com.averycorp.prismtask.ui.screens.templates.TemplateListScreen]:
     * deleting a category only removes the label, it doesn't touch the templates
     * themselves.
     */
    suspend fun clearCategory(category: String) {
        templateDao.clearCategory(category)
    }

    suspend fun getTemplateByName(name: String): TaskTemplateEntity? =
        templateDao.getTemplateByName(name)

    suspend fun getAllTemplatesOnce(): List<TaskTemplateEntity> =
        templateDao.getAllTemplatesOnce()

    /**
     * Instantiates a new [TaskEntity] from the template referenced by
     * [templateId]. Callers can override the due date (e.g., "apply this
     * template for next Monday") and the project (e.g., "use this template
     * inside my current project"); all other fields come from the template.
     *
     * Template-level subtasks (stored as a JSON array of titles) and tag
     * assignments (stored as a JSON array of tag ids) are materialized as
     * real rows so the resulting task looks identical to one built by hand.
     * The template's usage counter is bumped as a side effect.
     *
     * @return the id of the newly created root task.
     * @throws IllegalArgumentException if no template with [templateId] exists.
     */
    suspend fun createTaskFromTemplate(
        templateId: Long,
        dueDateOverride: Long? = null,
        projectIdOverride: Long? = null,
        quickUse: Boolean = false
    ): Long {
        val template = templateDao.getTemplateById(templateId)
            ?: throw IllegalArgumentException("Template not found")

        val now = System.currentTimeMillis()
        val effectiveDueDate = dueDateOverride ?: if (quickUse) DateShortcuts.today(now) else null
        val rawTask = buildTaskFromTemplate(template, effectiveDueDate, projectIdOverride, now)
        val task = rawTask.copy(
            lifeCategory = resolveTemplateLifeCategory(rawTask.title, rawTask.description, taskId = 0),
            taskMode = rawTask.taskMode
                ?: resolveTemplateTaskMode(rawTask.title, rawTask.description),
            cognitiveLoad = rawTask.cognitiveLoad
                ?: resolveTemplateCognitiveLoad(rawTask.title, rawTask.description)
        )
        val taskId = taskDao.insert(task)

        // Create subtasks if template has them
        val subtasks = parseSubtaskTitles(template.templateSubtasksJson)
        subtasks.forEachIndexed { index, title ->
            val subtask = TaskEntity(
                title = title,
                parentTaskId = taskId,
                sortOrder = index,
                lifeCategory = resolveTemplateLifeCategory(title, description = null, taskId = 0),
                taskMode = resolveTemplateTaskMode(title, description = null),
                cognitiveLoad = resolveTemplateCognitiveLoad(title, description = null),
                createdAt = now,
                updatedAt = now
            )
            taskDao.insert(subtask)
        }

        // Assign tags if template has them
        val tagIds = parseTagIds(template.templateTagsJson)
        tagIds.forEach { tagId ->
            tagDao.addTagToTask(TaskTagCrossRef(taskId = taskId, tagId = tagId))
        }

        // Increment usage
        templateDao.incrementUsage(templateId, now)

        return taskId
    }

    /**
     * Captures the shape of an existing task as a reusable template. Copies
     * over the task's title/description/priority/project/recurrence/duration,
     * serializes its tag assignments and subtask titles to JSON, and stores
     * the result as a new row in `task_templates`. The source task is left
     * untouched.
     */
    suspend fun createTemplateFromTask(
        taskId: Long,
        name: String,
        icon: String? = null,
        category: String? = null
    ): Long {
        val task = taskDao.getTaskByIdOnce(taskId)
            ?: throw IllegalArgumentException("Task not found")
        val tagIds = tagDao.getTagIdsForTaskOnce(taskId)
        val subtasks = taskDao.getSubtasksOnce(taskId)

        val template = buildTemplateFromTask(
            task = task,
            tagIds = tagIds,
            subtaskTitles = subtasks.map { it.title },
            name = name,
            icon = icon,
            category = category
        )
        return templateDao.insertTemplate(template)
    }

    companion object {
        private val gson = Gson()

        /**
         * Pure transformation: produce the [TaskEntity] that should be
         * inserted when instantiating [template]. Extracted from the
         * repository method so the field-mapping contract can be tested
         * without a Room database.
         */
        fun buildTaskFromTemplate(
            template: TaskTemplateEntity,
            dueDateOverride: Long?,
            projectIdOverride: Long?,
            now: Long
        ): TaskEntity = TaskEntity(
            title = template.templateTitle ?: template.name,
            description = template.templateDescription,
            priority = template.templatePriority ?: 0,
            projectId = projectIdOverride ?: template.templateProjectId,
            recurrenceRule = template.templateRecurrenceJson,
            estimatedDuration = template.templateDuration,
            dueDate = dueDateOverride,
            createdAt = now,
            updatedAt = now
        )

        /**
         * Pure transformation: build a template that captures the content
         * fields of [task] plus its tag assignments and subtask titles. The
         * caller supplies the human-facing [name]/[icon]/[category] since
         * those aren't implied by the task itself.
         */
        fun buildTemplateFromTask(
            task: TaskEntity,
            tagIds: List<Long>,
            subtaskTitles: List<String>,
            name: String,
            icon: String? = null,
            category: String? = null
        ): TaskTemplateEntity = TaskTemplateEntity(
            name = name,
            icon = icon,
            category = category,
            templateTitle = task.title,
            templateDescription = task.description,
            templatePriority = task.priority,
            templateProjectId = task.projectId,
            templateTagsJson = if (tagIds.isNotEmpty()) gson.toJson(tagIds) else null,
            templateRecurrenceJson = task.recurrenceRule,
            templateDuration = task.estimatedDuration,
            templateSubtasksJson = if (subtaskTitles.isNotEmpty()) gson.toJson(subtaskTitles) else null
        )

        /**
         * Pure transformation: parse the JSON array of subtask titles stored
         * on a template back into a list. Returns an empty list if the JSON
         * is null, blank, or malformed so callers can iterate without extra
         * null checks.
         */
        fun parseSubtaskTitles(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        /**
         * Case-insensitive fuzzy name lookup used by the NLP quick-add path
         * ("apply my morning routine template"). The algorithm is deliberately
         * simple — substring match first, then token-prefix match — so that
         * common abbreviations ("morning", "review", "grocery") resolve to the
         * right template without pulling in a full Levenshtein implementation.
         *
         * Returns the best-matching template or null if nothing matches. Ties
         * are broken by the template with the higher [TaskTemplateEntity.usageCount]
         * so a frequently-used template wins over an unused one.
         */
        fun findBestMatchByName(
            templates: List<TaskTemplateEntity>,
            query: String
        ): TaskTemplateEntity? {
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty() || templates.isEmpty()) return null

            // 1) Exact match (case-insensitive) wins outright.
            templates
                .firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?.let { return it }

            // 2) Score everything else with a simple substring/prefix score.
            //    Higher score = better match.
            fun score(template: TaskTemplateEntity): Int {
                val name = template.name.lowercase()
                return when {
                    name == normalized -> 1000
                    name.startsWith(normalized) -> 500 + template.usageCount
                    name.contains(normalized) -> 300 + template.usageCount
                    // Token-prefix: any word in the template name starts with the query.
                    name
                        .split(' ', '-', '_')
                        .any { it.startsWith(normalized) } -> 200 + template.usageCount
                    // Reverse: query contains the template name (e.g., query is a
                    // longer phrase, template name is a short keyword).
                    normalized.contains(name) && name.length >= 3 -> 100 + template.usageCount
                    else -> 0
                }
            }

            return templates
                .map { it to score(it) }
                .filter { it.second > 0 }
                .maxWithOrNull(
                    compareBy<Pair<TaskTemplateEntity, Int>> { it.second }
                        .thenBy { it.first.usageCount }
                )?.first
        }

        /**
         * Merge two template lists by name, keeping whichever copy has the
         * higher [TaskTemplateEntity.usageCount]. Used on first-connect to the
         * backend when the same template exists both locally and remotely (e.g.,
         * the user installed on a second device). Templates that appear in only
         * one list are passed through unchanged.
         *
         * This is intentionally a pure function so it can be unit-tested without
         * any Room or network plumbing.
         */
        fun mergeTemplatesByName(
            local: List<TaskTemplateEntity>,
            remote: List<TaskTemplateEntity>
        ): List<TaskTemplateEntity> {
            val byName = mutableMapOf<String, TaskTemplateEntity>()
            for (template in local) {
                byName[template.name.lowercase()] = template
            }
            for (template in remote) {
                val key = template.name.lowercase()
                val existing = byName[key]
                byName[key] = if (existing == null) {
                    template
                } else if (template.usageCount > existing.usageCount) {
                    template
                } else {
                    existing
                }
            }
            return byName.values.toList()
        }

        /**
         * Pure transformation: parse the JSON array of tag ids stored on a
         * template. Returns an empty list if the JSON is null, blank, or
         * malformed.
         */
        fun parseTagIds(json: String?): List<Long> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                gson.fromJson(json, Array<Long>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
