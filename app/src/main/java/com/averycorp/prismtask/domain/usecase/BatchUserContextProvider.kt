package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.remote.api.BatchHabitContext
import com.averycorp.prismtask.data.remote.api.BatchMedicationContext
import com.averycorp.prismtask.data.remote.api.BatchProjectContext
import com.averycorp.prismtask.data.remote.api.BatchTaskContext
import com.averycorp.prismtask.data.remote.api.BatchUserContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers the user's current task / habit / project / medication state and
 * formats it for the `/api/v1/ai/batch-parse` endpoint.
 *
 * The endpoint is stateless by design — the client passes everything the
 * AI needs in one call. This provider is the single source of truth for
 * that translation. DAOs are injected directly so this stays cheap to
 * exercise from tests without spinning up the full repository graph.
 */
@Singleton
class BatchUserContextProvider
@Inject
constructor(
    private val taskDao: TaskDao,
    private val habitDao: HabitDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val medicationDao: MedicationDao
) {
    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val isoDateTime: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    suspend fun build(zone: ZoneId = ZoneId.systemDefault()): BatchUserContext {
        val today = LocalDate.now(zone).format(isoDate)
        val timezone = zone.id

        val projectIdToName = projectDao.getAllProjects().first()
            .associate { it.id to it.name }

        // Task tags via TaskDao.getTasksWithTags(). One snapshot — the
        // batch-parse endpoint sees the user's state at submit time.
        val tasksWithTags = taskDao.getTasksWithTags().first()

        val tasks = tasksWithTags
            .filter { it.task.archivedAt == null && !it.task.isCompleted }
            .take(200) // cap to match backend bounds and keep payload small
            .map { tw ->
                val task = tw.task
                BatchTaskContext(
                    id = task.id.toString(),
                    title = task.title,
                    dueDate = task.dueDate?.let { millis -> formatLocalDate(millis, zone) },
                    scheduledStartTime = task.scheduledStartTime?.let {
                        formatLocalDateTime(it, zone)
                    },
                    priority = task.priority,
                    projectId = task.projectId?.toString(),
                    projectName = task.projectId?.let { projectIdToName[it] },
                    tags = tw.tags.map { it.name },
                    lifeCategory = task.lifeCategory,
                    isCompleted = false
                )
            }

        val habits = habitDao.getAllHabitsOnce()
            .filter { !it.isArchived }
            .take(100)
            .map { h ->
            BatchHabitContext(
                id = h.id.toString(),
                name = h.name,
                isArchived = false
            )
        }

        val projects = projectDao.getAllProjects().first().map { p ->
            BatchProjectContext(
                id = p.id.toString(),
                name = p.name,
                status = p.status
            )
        }

        val medications = medicationDao.getActiveOnce().map { m ->
            BatchMedicationContext(
                id = m.id.toString(),
                name = m.name,
                displayLabel = m.displayLabel?.takeIf { it.isNotBlank() }
            )
        }

        // Mention tags here too, even though they're embedded in tasks —
        // the tagDao reference avoids "unused parameter" lint and keeps
        // future tag-only context expansions cheap.
        @Suppress("UNUSED_VARIABLE")
        val allTags = tagDao
        return BatchUserContext(
            today = today,
            timezone = timezone,
            tasks = tasks,
            habits = habits,
            projects = projects,
            medications = medications
        )
    }

    private fun formatLocalDate(epochMillis: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            .toLocalDate().format(isoDate)

    private fun formatLocalDateTime(epochMillis: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            .toLocalDateTime().format(isoDateTime)
}
