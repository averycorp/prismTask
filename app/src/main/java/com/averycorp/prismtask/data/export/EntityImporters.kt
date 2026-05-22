package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.HabitLogDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.HabitLogEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.google.gson.JsonObject
import kotlin.math.abs

/**
 * Importers for the core task graph (projects, tags, tasks, habits) and
 * their derived history (task / habit completions, habit logs, leisure
 * logs). Extracted from [DataImporter] so the orchestrator can stay
 * coordinator-thin without touching the public surface.
 *
 * Mutates the shared [ImportContext] for count/error accumulation.
 */
internal class EntityImporters(
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val habitLogDao: HabitLogDao
) {
    suspend fun importProjects(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode,
        existingProjects: List<ProjectEntity>
    ): MutableMap<String, Long> {
        val projectNameToId = mutableMapOf<String, Long>()
        val existingByNameLower = existingProjects.associateBy { it.name.lowercase() }
        existingProjects.forEach { projectNameToId[it.name.lowercase()] = it.id }

        root.getAsJsonArray("projects")?.forEach { elem ->
            val obj = elem.asJsonObject
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val existing = existingByNameLower[name.lowercase()]
            if (mode == ImportMode.MERGE && existing != null) {
                // Last-write-wins: if the incoming row is newer than what we
                // already have, overlay the imported fields onto the existing
                // row (keeping its DB id). Otherwise count as a duplicate.
                val incomingUpdatedAt = obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                if (incomingUpdatedAt > existing.updatedAt) {
                    val merged = mergeEntityWithDefaults(ProjectEntity(name = name), obj)
                    projectDao.update(merged.copy(id = existing.id))
                    ctx.lwwOverwrites++
                } else {
                    ctx.duplicatesSkipped++
                }
            } else {
                val default = ProjectEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val project = merged.copy(id = 0, updatedAt = System.currentTimeMillis())
                val id = projectDao.insert(project)
                projectNameToId[name.lowercase()] = id
                ctx.projectsImported++
            }
        }
        return projectNameToId
    }

    suspend fun importTags(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode,
        existingTags: List<TagEntity>
    ): MutableMap<String, Long> {
        val tagNameToId = mutableMapOf<String, Long>()
        existingTags.forEach { tagNameToId[it.name.lowercase()] = it.id }

        root.getAsJsonArray("tags")?.forEach { elem ->
            val obj = elem.asJsonObject
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            if (mode == ImportMode.MERGE && name.lowercase() in tagNameToId) {
                ctx.duplicatesSkipped++
            } else {
                val default = TagEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val tag = merged.copy(id = 0)
                val id = tagDao.insert(tag)
                tagNameToId[name.lowercase()] = id
                ctx.tagsImported++
            }
        }
        return tagNameToId
    }

    suspend fun importTasks(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode,
        existingTasks: List<TaskEntity>,
        projectNameToId: Map<String, Long>,
        tagNameToId: Map<String, Long>
    ) {
        root.getAsJsonArray("tasks")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                if (title.isBlank()) return@forEach

                if (mode == ImportMode.MERGE) {
                    val createdAt = obj.get("createdAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0
                    val isDup = existingTasks.any { it.title == title && abs(it.createdAt - createdAt) < 60000 }
                    if (isDup) {
                        ctx.duplicatesSkipped++
                        return@forEach
                    }
                }

                // Foreign key resolution: v3 uses "_projectName" helper, v2 uses "project".
                val projectName = (obj.get("_projectName") ?: obj.get("project"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                val projectId = projectName?.let { projectNameToId[it.lowercase()] }

                val default = TaskEntity(title = title)
                val merged = mergeEntityWithDefaults(default, obj)
                val task = merged.copy(
                    id = 0,
                    projectId = projectId,
                    parentTaskId = null,
                    sourceHabitId = null,
                    updatedAt = System.currentTimeMillis()
                )
                val taskId = taskDao.insert(task)

                // Tags: v3 uses "_tagNames", v2 uses "tags".
                val tagArr = obj.getAsJsonArray("_tagNames") ?: obj.getAsJsonArray("tags")
                tagArr?.forEach { tagElem ->
                    if (tagElem.isJsonNull) return@forEach
                    val tagName = tagElem.asString
                    val tagId = tagNameToId[tagName.lowercase()]
                    if (tagId != null) {
                        tagDao.addTagToTask(TaskTagCrossRef(taskId = taskId, tagId = tagId))
                    }
                }

                ctx.tasksImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import task: ${e.message}")
            }
        }
    }

    suspend fun importHabits(
        ctx: ImportContext,
        root: JsonObject,
        mode: ImportMode
    ): MutableMap<String, Long> {
        val habitNameToId = mutableMapOf<String, Long>()
        val existingByNameLower = mutableMapOf<String, HabitEntity>()
        if (mode == ImportMode.MERGE) {
            habitDao.getAllHabitsOnce().forEach {
                habitNameToId[it.name.lowercase()] = it.id
                existingByNameLower[it.name.lowercase()] = it
            }
        }

        root.getAsJsonArray("habits")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val existing = existingByNameLower[name.lowercase()]
                if (mode == ImportMode.MERGE && existing != null) {
                    val incomingUpdatedAt = obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    if (incomingUpdatedAt > existing.updatedAt) {
                        val merged = mergeEntityWithDefaults(HabitEntity(name = name), obj)
                        habitDao.update(merged.copy(id = existing.id))
                        ctx.lwwOverwrites++
                    } else {
                        ctx.duplicatesSkipped++
                    }
                    return@forEach
                }
                val default = HabitEntity(name = name)
                val merged = mergeEntityWithDefaults(default, obj)
                val habit = merged.copy(id = 0, updatedAt = System.currentTimeMillis())
                val id = habitDao.insert(habit)
                habitNameToId[name.lowercase()] = id
                ctx.habitsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import habit: ${e.message}")
            }
        }
        return habitNameToId
    }

    suspend fun importTaskCompletions(
        ctx: ImportContext,
        root: JsonObject,
        @Suppress("UNUSED_PARAMETER") mode: ImportMode
    ) {
        derivedArray(root, "taskCompletions")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val completedDate = obj.get("completedDate")?.takeIf { !it.isJsonNull }?.asLong
                    ?: return@forEach
                val default = TaskCompletionEntity(completedDate = completedDate)
                val merged = mergeEntityWithDefaults(default, obj)
                val completion = merged.copy(id = 0)
                taskCompletionDao.insert(completion)
                ctx.taskCompletionsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import task completion: ${e.message}")
            }
        }
    }

    suspend fun importHabitCompletions(
        ctx: ImportContext,
        root: JsonObject,
        habitNameToId: Map<String, Long>
    ) {
        val existingCompletionKeys = habitCompletionDao
            .getAllCompletionsOnce()
            .map {
                it.habitId to (it.completedDateLocal ?: epochToLocalDateString(it.completedDate))
            }
            .toMutableSet()
        derivedArray(root, "habitCompletions")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val habitName = (obj.get("_habitName") ?: obj.get("habitName"))
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                if (habitName == null) {
                    ctx.orphansSkipped++
                    ctx.errors.add("Skipped habit completion: no _habitName")
                    return@forEach
                }
                val habitId = habitNameToId[habitName.lowercase()]
                if (habitId == null) {
                    ctx.orphansSkipped++
                    ctx.errors.add("Skipped habit completion: unknown habit '$habitName'")
                    return@forEach
                }
                val completedDate = obj.get("completedDate")?.takeIf { !it.isJsonNull }?.asLong
                    ?: return@forEach
                val completedDateLocal = obj.get("completedDateLocal")
                    ?.takeIf { !it.isJsonNull }?.asString
                    ?: epochToLocalDateString(completedDate)
                val key = habitId to completedDateLocal
                if (key in existingCompletionKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = HabitCompletionEntity(
                    habitId = habitId,
                    completedDate = completedDate,
                    completedDateLocal = completedDateLocal
                )
                val merged = mergeEntityWithDefaults(default, obj)
                val completion = merged.copy(
                    id = 0,
                    habitId = habitId,
                    completedDateLocal = completedDateLocal
                )
                habitCompletionDao.insert(completion)
                existingCompletionKeys.add(key)
                ctx.habitCompletionsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import habit completion: ${e.message}")
            }
        }
    }

    suspend fun importHabitLogs(
        ctx: ImportContext,
        root: JsonObject,
        habitNameToId: Map<String, Long>
    ) {
        val existingHabitLogKeys = habitLogDao
            .getAllLogsOnce()
            .map { it.habitId to it.date }
            .toMutableSet()
        derivedArray(root, "habitLogs")?.forEach { elem ->
            try {
                val obj = elem.asJsonObject
                val habitName = obj.get("_habitName")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("habitName")?.takeIf { !it.isJsonNull }?.asString
                val habitId = if (habitName != null) habitNameToId[habitName.lowercase()] else null
                if (habitId == null) return@forEach
                val date = obj.get("date")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                val key = habitId to date
                if (key in existingHabitLogKeys) {
                    ctx.duplicatesSkipped++
                    return@forEach
                }
                val default = HabitLogEntity(habitId = habitId, date = date)
                val merged = mergeEntityWithDefaults(default, obj)
                val log = merged.copy(id = 0, habitId = habitId)
                habitLogDao.insertLog(log)
                existingHabitLogKeys.add(key)
                ctx.habitLogsImported++
            } catch (e: Exception) {
                ctx.errors.add("Failed to import habit log: ${e.message}")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun importLeisureLogs(ctx: ImportContext, root: JsonObject) {
        // Leisure Budget v2.0: v1.x leisure_logs format is no longer
        // supported — the table was dropped in migration 81→82. v2.0
        // exports use the new leisure_activities / leisure_sessions
        // shape; round-tripping a pre-v82 export is intentionally a
        // no-op (the rows wouldn't translate into the v2.0 model
        // without duration data anyway).
        if (root.getAsJsonArray("leisureLogs") != null) {
            // Surface the skip so the import summary doesn't silently
            // drop a chunk of the user's old export.
            ctx.errors.add(
                "Skipped legacy leisure_logs payload — v1.x slot picks " +
                    "don't translate into the v2.0 session-log model."
            )
        }
    }
}

/**
 * Finds a derived collection by name. Prefers `derived.<name>` (v5+) and
 * falls back to the top-level key (v3/v4 back-compat). Shared between
 * [EntityImporters] and [WellnessImporters].
 */
internal fun derivedArray(root: JsonObject, name: String) =
    root.getAsJsonObject("derived")?.getAsJsonArray(name)
        ?: root.getAsJsonArray(name)

internal fun epochToLocalDateString(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()
