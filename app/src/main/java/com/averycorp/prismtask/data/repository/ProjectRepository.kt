package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.MilestoneDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.ProjectPhaseDao
import com.averycorp.prismtask.data.local.dao.ProjectRiskDao
import com.averycorp.prismtask.data.local.dao.ProjectWithCount
import com.averycorp.prismtask.data.local.entity.MilestoneEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.ProjectPhaseEntity
import com.averycorp.prismtask.data.local.entity.ProjectRiskEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.ProjectDetail
import com.averycorp.prismtask.domain.model.ProjectStatus
import com.averycorp.prismtask.domain.model.ProjectWithProgress
import com.averycorp.prismtask.domain.usecase.DailyForgivenessStreakCore
import com.averycorp.prismtask.domain.usecase.ForgivenessConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRepository
@Inject
constructor(
    private val projectDao: ProjectDao,
    private val syncTracker: SyncTracker,
    private val milestoneDao: MilestoneDao,
    private val projectPhaseDao: ProjectPhaseDao,
    private val projectRiskDao: ProjectRiskDao
) {

    // ---------------------------------------------------------------------
    // Legacy API — preserved verbatim so existing ViewModels and tests keep
    // compiling. New code should prefer the v1.4.0 methods below.
    // ---------------------------------------------------------------------

    fun getAllProjects(): Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    fun getProjectById(id: Long): Flow<ProjectEntity?> = projectDao.getProjectById(id)

    suspend fun getProjectByIdOnce(id: Long): ProjectEntity? = projectDao.getProjectByIdOnce(id)

    suspend fun getProjectByNameOnce(name: String): ProjectEntity? = projectDao.getProjectByNameOnce(name)

    fun getProjectWithTaskCount(): Flow<List<ProjectWithCount>> =
        projectDao.getProjectWithTaskCount()

    fun searchProjects(query: String): Flow<List<ProjectEntity>> = projectDao.searchProjects(query)

    suspend fun addProject(name: String, color: String = "#4A90D9", icon: String = "\uD83D\uDCC1"): Long {
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            name = name,
            color = color,
            icon = icon,
            createdAt = now,
            updatedAt = now
        )
        val id = projectDao.insert(project)
        syncTracker.trackCreate(id, "project")
        return id
    }

    suspend fun updateProject(project: ProjectEntity) {
        projectDao.update(project.copy(updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(project.id, "project")
    }

    suspend fun deleteProject(project: ProjectEntity) {
        syncTracker.trackDelete(project.id, "project")
        projectDao.delete(project)
    }

    // ---------------------------------------------------------------------
    // v1.4.0 Projects feature API.
    // ---------------------------------------------------------------------

    /**
     * Stream of lightweight project rows filtered by status.
     *
     * `status == null` → all projects. `ProjectStatus.ACTIVE` is the
     * default for list screens.
     */
    fun observeProjects(status: ProjectStatus? = ProjectStatus.ACTIVE): Flow<List<ProjectEntity>> =
        if (status == null) projectDao.observeAll() else projectDao.observeByStatus(status.name)

    /**
     * Project-list projection with precomputed progress, streak, and
     * activity aggregates. Emits whenever the underlying projects change;
     * per-row aggregates are refreshed on each emission.
     *
     * Note: only the project rows drive Flow emissions. Milestone/task
     * completions that land without modifying the project row (e.g.
     * ticking a milestone or completing a task) won't trip a re-emit on
     * their own. For v1 the list screen refreshes on navigation or via
     * a manual pull-to-refresh; wiring a combined Flow across milestones,
     * tasks, and task_completions is a Phase-2 polish item.
     */
    fun observeProjectsWithProgress(
        status: ProjectStatus? = ProjectStatus.ACTIVE,
        today: LocalDate = LocalDate.now(),
        forgiveness: ForgivenessConfig = ForgivenessConfig.DEFAULT,
        restDays: Set<LocalDate> = emptySet()
    ): Flow<List<ProjectWithProgress>> =
        observeProjects(status).map { projects ->
            // Can't use List.map here: computeProgress is a suspend fun and
            // List.map's lambda is non-suspending. A plain for-loop stays
            // inside the suspending Flow.map block.
            val out = ArrayList<ProjectWithProgress>(projects.size)
            for (project in projects) {
                out.add(computeProgress(project, today, forgiveness, restDays))
            }
            out
        }

    /**
     * Project detail stream combining the entity, its milestones, task
     * counts, and the streak. Switches the milestone sub-stream whenever
     * the project row emits a new revision.
     */
    fun observeProject(
        id: Long,
        today: LocalDate = LocalDate.now(),
        forgiveness: ForgivenessConfig = ForgivenessConfig.DEFAULT,
        restDays: Set<LocalDate> = emptySet()
    ): Flow<ProjectDetail?> =
        projectDao.getProjectById(id).flatMapLatest { project ->
            if (project == null) {
                flowOf(null)
            } else {
                milestoneDao.observeMilestones(id).map { milestones ->
                    buildDetail(project, milestones, today, forgiveness, restDays)
                }
            }
        }

    // ----- Project lifecycle actions -----

    /**
     * Create a project with the v1.4.0 shape (description, dates, theme
     * color token). Legacy color/icon fields are defaulted so existing
     * export/import JSON and SyncMapper callers keep round-tripping.
     */
    suspend fun addProject(
        name: String,
        description: String?,
        status: ProjectStatus,
        startDate: Long?,
        endDate: Long?,
        themeColorKey: String?,
        color: String = "#4A90D9",
        icon: String = "\uD83D\uDCC1"
    ): Long {
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            name = name,
            description = description,
            color = color,
            icon = icon,
            themeColorKey = themeColorKey,
            status = status.name,
            startDate = startDate,
            endDate = endDate,
            createdAt = now,
            updatedAt = now
        )
        val id = projectDao.insert(project)
        syncTracker.trackCreate(id, "project")
        return id
    }

    suspend fun archiveProject(id: Long) = transitionStatus(id, ProjectStatus.ARCHIVED)

    suspend fun completeProject(id: Long) = transitionStatus(id, ProjectStatus.COMPLETED)

    suspend fun reopenProject(id: Long) = transitionStatus(id, ProjectStatus.ACTIVE)

    private suspend fun transitionStatus(id: Long, next: ProjectStatus) {
        val existing = projectDao.getProjectByIdOnce(id) ?: return
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = next.name,
            completedAt = if (next == ProjectStatus.COMPLETED) now else null,
            archivedAt = if (next == ProjectStatus.ARCHIVED) now else null,
            updatedAt = now
        )
        projectDao.update(updated)
        syncTracker.trackUpdate(id, "project")
    }

    // ----- Milestone CRUD & reorder -----

    fun observeMilestones(projectId: Long): Flow<List<MilestoneEntity>> =
        milestoneDao.observeMilestones(projectId)

    suspend fun addMilestone(projectId: Long, title: String): Long {
        val now = System.currentTimeMillis()
        val nextOrder = milestoneDao.getMaxOrderIndex(projectId) + 1
        val id = milestoneDao.insert(
            MilestoneEntity(
                projectId = projectId,
                title = title,
                orderIndex = nextOrder,
                createdAt = now,
                updatedAt = now
            )
        )
        touchProject(projectId, now)
        return id
    }

    suspend fun updateMilestone(milestone: MilestoneEntity) {
        val now = System.currentTimeMillis()
        milestoneDao.update(milestone.copy(updatedAt = now))
        touchProject(milestone.projectId, now)
    }

    suspend fun deleteMilestone(milestone: MilestoneEntity) {
        milestoneDao.delete(milestone)
        touchProject(milestone.projectId, System.currentTimeMillis())
    }

    /**
     * Toggle a milestone's completion flag. Stamps `completed_at` on
     * completion and clears it on un-toggle. Also bumps the parent project's
     * `updated_at` so [observeProjectsWithProgress] emits a refreshed row.
     */
    suspend fun toggleMilestone(milestone: MilestoneEntity, completed: Boolean) {
        val now = System.currentTimeMillis()
        val updated = milestone.copy(
            isCompleted = completed,
            completedAt = if (completed) now else null,
            updatedAt = now
        )
        milestoneDao.update(updated)
        touchProject(milestone.projectId, now)
    }

    /**
     * Persist a user-driven milestone reorder. `orderedIds` is the new
     * top-to-bottom ordering; missing IDs are ignored, unknown IDs are
     * skipped.
     */
    suspend fun reorderMilestones(projectId: Long, orderedIds: List<Long>) {
        val current = milestoneDao.getMilestonesOnce(projectId).associateBy { it.id }
        val now = System.currentTimeMillis()
        // Drop unknown IDs BEFORE indexing so orderIndex stays contiguous
        // (0, 1, 2, …). Using orderedIds.mapIndexedNotNull would reserve a
        // slot for each unknown id and leave gaps in orderIndex.
        val reindexed = orderedIds
            .mapNotNull { id -> current[id] }
            .mapIndexed { index, milestone -> milestone.copy(orderIndex = index, updatedAt = now) }
        if (reindexed.isNotEmpty()) {
            milestoneDao.updateAll(reindexed)
            touchProject(projectId, now)
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Bump the project's `updated_at` so list/detail streams re-emit after
     * a milestone or status change. Also notifies the sync tracker so the
     * project row will be included in the next push.
     */
    private suspend fun touchProject(projectId: Long, nowMillis: Long) {
        val existing = projectDao.getProjectByIdOnce(projectId) ?: return
        projectDao.update(existing.copy(updatedAt = nowMillis))
        syncTracker.trackUpdate(projectId, "project")
    }

    private suspend fun computeProgress(
        project: ProjectEntity,
        today: LocalDate,
        forgiveness: ForgivenessConfig,
        restDays: Set<LocalDate> = emptySet()
    ): ProjectWithProgress {
        val aggregate = projectDao.getAggregateRow(project.id)
        val milestoneDates = milestoneDao.getCompletedTimestamps(project.id)
        val taskActivityMillis = projectDao.getTaskActivityDates(project.id)
        val activityDates = toLocalDateSet(taskActivityMillis + milestoneDates)
        val streak = DailyForgivenessStreakCore.calculate(activityDates, today, forgiveness, restDays)
        val lastActivity = (taskActivityMillis + milestoneDates).maxOrNull()
        return ProjectWithProgress(
            project = project,
            status = ProjectStatus.fromStorageOrActive(project.status),
            totalMilestones = aggregate?.totalMilestones ?: 0,
            completedMilestones = aggregate?.completedMilestones ?: 0,
            upcomingMilestoneTitle = aggregate?.upcomingMilestoneTitle,
            totalTasks = aggregate?.totalTasks ?: 0,
            openTasks = aggregate?.openTasks ?: 0,
            streak = streak,
            lastActivityAt = lastActivity
        )
    }

    private suspend fun buildDetail(
        project: ProjectEntity,
        milestones: List<MilestoneEntity>,
        today: LocalDate,
        forgiveness: ForgivenessConfig,
        restDays: Set<LocalDate> = emptySet()
    ): ProjectDetail {
        val aggregate = projectDao.getAggregateRow(project.id)
        val taskActivityMillis = projectDao.getTaskActivityDates(project.id)
        val milestoneTimestamps = milestones.mapNotNull { it.completedAt }
        val activityDates = toLocalDateSet(taskActivityMillis + milestoneTimestamps)
        val streak = DailyForgivenessStreakCore.calculate(activityDates, today, forgiveness, restDays)
        val lastActivity = (taskActivityMillis + milestoneTimestamps).maxOrNull()
        return ProjectDetail(
            project = project,
            status = ProjectStatus.fromStorageOrActive(project.status),
            milestones = milestones,
            totalTasks = aggregate?.totalTasks ?: 0,
            openTasks = aggregate?.openTasks ?: 0,
            streak = streak,
            lastActivityAt = lastActivity
        )
    }

    private fun toLocalDateSet(
        epochMillis: List<Long>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Set<LocalDate> {
        if (epochMillis.isEmpty()) return emptySet()
        val out = HashSet<LocalDate>(epochMillis.size)
        for (ms in epochMillis) {
            out.add(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Phases (PrismTask-timeline-class scope, PR-1).
    // ---------------------------------------------------------------------

    fun observePhases(projectId: Long): Flow<List<ProjectPhaseEntity>> =
        projectPhaseDao.observePhases(projectId)

    suspend fun getPhasesOnce(projectId: Long): List<ProjectPhaseEntity> =
        projectPhaseDao.getPhasesOnce(projectId)

    suspend fun addPhase(
        projectId: Long,
        title: String,
        description: String? = null,
        colorKey: String? = null,
        startDate: Long? = null,
        endDate: Long? = null,
        versionAnchor: String? = null,
        versionNote: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val nextOrder = projectPhaseDao.getMaxOrderIndex(projectId) + 1
        val phase = ProjectPhaseEntity(
            projectId = projectId,
            title = title,
            description = description,
            colorKey = colorKey,
            startDate = startDate,
            endDate = endDate,
            versionAnchor = versionAnchor,
            versionNote = versionNote,
            orderIndex = nextOrder,
            createdAt = now,
            updatedAt = now
        )
        val id = projectPhaseDao.insert(phase)
        syncTracker.trackCreate(id, "project_phase")
        return id
    }

    suspend fun updatePhase(phase: ProjectPhaseEntity) {
        projectPhaseDao.update(phase.copy(updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(phase.id, "project_phase")
    }

    suspend fun deletePhase(phase: ProjectPhaseEntity) {
        syncTracker.trackDelete(phase.id, "project_phase")
        projectPhaseDao.delete(phase)
    }

    /**
     * Persists a user-driven re-order in a single batch. Caller is
     * expected to have already swapped the [orderIndex] values; this
     * method simply bumps `updated_at` and writes the rows.
     */
    suspend fun reorderPhases(phases: List<ProjectPhaseEntity>) {
        if (phases.isEmpty()) return
        val now = System.currentTimeMillis()
        projectPhaseDao.updateAll(phases.map { it.copy(updatedAt = now) })
        for (phase in phases) syncTracker.trackUpdate(phase.id, "project_phase")
    }

    // ---------------------------------------------------------------------
    // Risks (PrismTask-timeline-class scope, PR-1).
    // ---------------------------------------------------------------------

    fun observeRisks(projectId: Long): Flow<List<ProjectRiskEntity>> =
        projectRiskDao.observeRisks(projectId)

    suspend fun getRisksOnce(projectId: Long): List<ProjectRiskEntity> =
        projectRiskDao.getRisksOnce(projectId)

    suspend fun addRisk(
        projectId: Long,
        title: String,
        level: String = "MEDIUM",
        mitigation: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val risk = ProjectRiskEntity(
            projectId = projectId,
            title = title,
            level = level,
            mitigation = mitigation,
            createdAt = now,
            updatedAt = now
        )
        val id = projectRiskDao.insert(risk)
        syncTracker.trackCreate(id, "project_risk")
        return id
    }

    suspend fun updateRisk(risk: ProjectRiskEntity) {
        projectRiskDao.update(risk.copy(updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(risk.id, "project_risk")
    }

    suspend fun deleteRisk(risk: ProjectRiskEntity) {
        syncTracker.trackDelete(risk.id, "project_risk")
        projectRiskDao.delete(risk)
    }
}
