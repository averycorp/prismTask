package com.averycorp.prismtask.ui.screens.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.domain.model.ProjectStatus
import com.averycorp.prismtask.domain.model.ProjectWithProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Projects side of the Tasks tab segmented toggle.
 *
 * The pane has two top-level modes: the normal Active/Completed/All chip
 * view, and a separate Archived-only view reached via a footer link. The
 * archived mode hides the chip row entirely so archived projects feel
 * cordoned off rather than mixed in with the others. Both the chip filter
 * and the archived-mode toggle are persisted through [SavedStateHandle] so
 * they survive process death.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsPaneViewModel
@Inject
constructor(
    private val projectRepository: ProjectRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    companion object {
        const val KEY_STATUS_FILTER = "projects_pane_status_filter"
        const val KEY_SHOW_ARCHIVED = "projects_pane_show_archived"
    }

    /**
     * Chip-row selection for the normal (non-archived) view. `null` means
     * "All", which surfaces both ACTIVE and COMPLETED projects but never
     * ARCHIVED — archived projects only appear inside [showArchived].
     *
     * ARCHIVED is intentionally rejected here: pre-existing SavedStateHandle
     * values from before the archive split could otherwise restore the VM
     * into a state the UI can no longer reach via the chip row.
     */
    private val _statusFilter = MutableStateFlow<ProjectStatus?>(
        savedStateHandle.get<String?>(KEY_STATUS_FILTER)
            ?.let { raw -> runCatching { ProjectStatus.valueOf(raw) }.getOrNull() }
            ?.takeUnless { it == ProjectStatus.ARCHIVED }
            ?: ProjectStatus.ACTIVE
    )
    val statusFilter: StateFlow<ProjectStatus?> = _statusFilter.asStateFlow()

    private val _showArchived = MutableStateFlow(
        savedStateHandle.get<Boolean?>(KEY_SHOW_ARCHIVED) ?: false
    )
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    /**
     * Project list driving the pane. When [showArchived] is on, emits only
     * ARCHIVED rows; otherwise emits whatever the chip filter selects, with
     * ARCHIVED stripped out of the "All" case so archived projects can't
     * leak into the default view.
     */
    val projects: StateFlow<List<ProjectWithProgress>> = combine(
        _statusFilter,
        _showArchived
    ) { status, archivedMode -> status to archivedMode }
        .flatMapLatest { (status, archivedMode) ->
            val effective = if (archivedMode) ProjectStatus.ARCHIVED else status
            projectRepository.observeProjectsWithProgress(effective).map { list ->
                if (!archivedMode && effective == null) {
                    list.filter { it.status != ProjectStatus.ARCHIVED }
                } else {
                    list
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many archived projects exist — drives the footer link's visibility. */
    val archivedCount: StateFlow<Int> = projectRepository
        .observeProjectsWithProgress(ProjectStatus.ARCHIVED)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setStatusFilter(status: ProjectStatus?) {
        val safe = status?.takeUnless { it == ProjectStatus.ARCHIVED }
        _statusFilter.value = safe
        savedStateHandle[KEY_STATUS_FILTER] = safe?.name
    }

    fun setShowArchived(show: Boolean) {
        _showArchived.value = show
        savedStateHandle[KEY_SHOW_ARCHIVED] = show
    }

    fun archiveProject(projectId: Long) {
        viewModelScope.launch { projectRepository.archiveProject(projectId) }
    }

    fun completeProject(projectId: Long) {
        viewModelScope.launch { projectRepository.completeProject(projectId) }
    }

    fun reopenProject(projectId: Long) {
        viewModelScope.launch { projectRepository.reopenProject(projectId) }
    }
}
