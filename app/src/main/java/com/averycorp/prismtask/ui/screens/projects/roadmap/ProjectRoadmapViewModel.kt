package com.averycorp.prismtask.ui.screens.projects.roadmap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.ExternalAnchorEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.ProjectPhaseEntity
import com.averycorp.prismtask.data.local.entity.ProjectRiskEntity
import com.averycorp.prismtask.data.local.entity.TaskDependencyEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.ExternalAnchorRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskDependencyRepository
import com.averycorp.prismtask.domain.model.ExternalAnchor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * View-model state + actions for the project-roadmap screen
 * (PrismTask-timeline-class scope, audit § P10 option (b)).
 *
 * Combines the per-project phase list, risk register, external
 * anchors, and the project's task-dependency edges with the unphased
 * tasks pulled at emission time. Editor affordances delegate to the
 * underlying repos which already handle sync tracking.
 */
data class ProjectRoadmapState(
    val project: ProjectEntity? = null,
    val phases: List<PhaseWithTasks> = emptyList(),
    val unphasedTasks: List<TaskEntity> = emptyList(),
    val risks: List<ProjectRiskEntity> = emptyList(),
    val anchors: List<ExternalAnchorRepository.Decoded> = emptyList(),
    val dependencies: List<TaskDependencyEntity> = emptyList(),
    val projectTasks: List<TaskEntity> = emptyList()
)

data class PhaseWithTasks(val phase: ProjectPhaseEntity, val tasks: List<TaskEntity>)

/**
 * Sealed editor surface — exactly one editor is open at a time, or
 * `null` when nothing is being edited. New-vs-edit is encoded by
 * whether [PhaseEditor.existing] (etc.) is non-null.
 */
sealed class RoadmapEditor {
    data class PhaseEditor(val existing: ProjectPhaseEntity? = null) : RoadmapEditor()
    data class RiskEditor(val existing: ProjectRiskEntity? = null) : RoadmapEditor()
    data class AnchorEditor(val existing: ExternalAnchorEntity? = null, val decoded: ExternalAnchor? = null) : RoadmapEditor()
    data object DependencyEditor : RoadmapEditor()
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRoadmapViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val taskDao: TaskDao,
    private val projectRepository: ProjectRepository,
    private val externalAnchorRepository: ExternalAnchorRepository,
    private val taskDependencyRepository: TaskDependencyRepository
) : ViewModel() {
    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L

    private val _editor = MutableStateFlow<RoadmapEditor?>(null)
    val editor: StateFlow<RoadmapEditor?> = _editor.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val state: StateFlow<ProjectRoadmapState> = if (projectId <= 0) {
        flowOf(ProjectRoadmapState())
    } else {
        projectRepository.observeProject(projectId)
            .flatMapLatest { detail ->
                if (detail == null) {
                    flowOf(ProjectRoadmapState())
                } else {
                    combine(
                        projectRepository.observePhases(projectId),
                        projectRepository.observeRisks(projectId),
                        externalAnchorRepository.observeAnchors(projectId),
                        taskDao.getTasksByProject(projectId)
                    ) { phases, risks, anchors, projectTasks ->
                        val phaseWithTasks = phases.map { phase ->
                            PhaseWithTasks(phase, taskDao.getTasksForPhaseOnce(phase.id))
                        }
                        val unphased = taskDao.getUnphasedTasksForProjectOnce(projectId)
                        val taskIds = projectTasks.map { it.id }.toSet()
                        // Filter the global edge set down to ones whose
                        // endpoints are both inside this project. The
                        // dependencies table is global; the roadmap surface
                        // only renders edges relevant to the current view.
                        val edges = taskDependencyRepository.getAllOnce()
                            .filter { it.blockerTaskId in taskIds && it.blockedTaskId in taskIds }
                        ProjectRoadmapState(
                            project = detail.project,
                            phases = phaseWithTasks,
                            unphasedTasks = unphased,
                            risks = risks,
                            anchors = anchors,
                            dependencies = edges,
                            projectTasks = projectTasks
                        )
                    }
                }
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ProjectRoadmapState()
    )

    fun openEditor(editor: RoadmapEditor) {
        _editor.value = editor
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun dismissError() {
        _error.value = null
    }

    // ────────────────── Phase actions ──────────────────

    fun savePhase(
        existing: ProjectPhaseEntity?,
        title: String,
        description: String?,
        startDate: Long?,
        endDate: Long?,
        versionAnchor: String?
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            _error.value = "Phase title is required."
            return
        }
        viewModelScope.launch {
            if (existing == null) {
                projectRepository.addPhase(
                    projectId = projectId,
                    title = trimmed,
                    description = description?.takeIf { it.isNotBlank() },
                    startDate = startDate,
                    endDate = endDate,
                    versionAnchor = versionAnchor?.takeIf { it.isNotBlank() }
                )
            } else {
                projectRepository.updatePhase(
                    existing.copy(
                        title = trimmed,
                        description = description?.takeIf { it.isNotBlank() },
                        startDate = startDate,
                        endDate = endDate,
                        versionAnchor = versionAnchor?.takeIf { it.isNotBlank() }
                    )
                )
            }
            closeEditor()
        }
    }

    fun deletePhase(phase: ProjectPhaseEntity) {
        viewModelScope.launch { projectRepository.deletePhase(phase) }
    }

    // ────────────────── Risk actions ──────────────────

    fun saveRisk(
        existing: ProjectRiskEntity?,
        title: String,
        level: String,
        mitigation: String?
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            _error.value = "Risk title is required."
            return
        }
        viewModelScope.launch {
            if (existing == null) {
                projectRepository.addRisk(
                    projectId = projectId,
                    title = trimmed,
                    level = level,
                    mitigation = mitigation?.takeIf { it.isNotBlank() }
                )
            } else {
                projectRepository.updateRisk(
                    existing.copy(
                        title = trimmed,
                        level = level,
                        mitigation = mitigation?.takeIf { it.isNotBlank() }
                    )
                )
            }
            closeEditor()
        }
    }

    fun deleteRisk(risk: ProjectRiskEntity) {
        viewModelScope.launch { projectRepository.deleteRisk(risk) }
    }

    // ────────────────── External-anchor actions ──────────────────

    fun saveAnchor(
        existing: ExternalAnchorEntity?,
        label: String,
        anchor: ExternalAnchor
    ) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            _error.value = "Anchor label is required."
            return
        }
        viewModelScope.launch {
            if (existing == null) {
                externalAnchorRepository.addAnchor(projectId, trimmed, anchor)
            } else {
                externalAnchorRepository.updateAnchor(existing, trimmed, anchor)
            }
            closeEditor()
        }
    }

    fun deleteAnchor(anchor: ExternalAnchorEntity) {
        viewModelScope.launch { externalAnchorRepository.deleteAnchor(anchor) }
    }

    // ────────────────── Dependency actions ──────────────────

    fun addDependency(blockerTaskId: Long, blockedTaskId: Long) {
        if (blockerTaskId == blockedTaskId) {
            _error.value = "A task can't block itself."
            return
        }
        viewModelScope.launch {
            val result = taskDependencyRepository.addDependency(blockerTaskId, blockedTaskId)
            result.exceptionOrNull()?.let {
                _error.value = when (it) {
                    is TaskDependencyRepository.DependencyError.CycleRejected ->
                        "That edge would close a cycle."
                    else -> "Couldn't add dependency."
                }
            }
            closeEditor()
        }
    }

    fun deleteDependency(edge: TaskDependencyEntity) {
        viewModelScope.launch { taskDependencyRepository.removeById(edge.id) }
    }
}
