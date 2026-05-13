package com.averycorp.prismtask.ui.screens.projects

import android.util.Log
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.dao.ProjectWithCount
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectListViewModel
@Inject
constructor(
    private val projectRepository: ProjectRepository,
    private val taskRepository: TaskRepository,
    private val pendingImportContent: PendingImportContent,
    private val sortPreferences: SortPreferences
) : ViewModel() {
    val snackbarHostState = SnackbarHostState()

    val projects: StateFlow<List<ProjectWithCount>> = projectRepository
        .getProjectWithTaskCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Reactive per-project sort mode, keyed by the dynamic
     * `sort_project_{projectId}` preference. UI that surfaces a per-project
     * sort dropdown can collect this for a single project.
     */
    fun observeProjectSort(projectId: Long): Flow<String> =
        sortPreferences.observeSortMode(SortPreferences.ScreenKeys.project(projectId))

    fun onChangeProjectSort(projectId: Long, sortMode: String) {
        viewModelScope.launch {
            sortPreferences.setSortMode(SortPreferences.ScreenKeys.project(projectId), sortMode)
        }
    }

    fun onDeleteProject(project: ProjectEntity, deleteTasks: Boolean = false) {
        viewModelScope.launch {
            try {
                if (deleteTasks) {
                    taskRepository.deleteTasksByProjectId(project.id)
                }
                projectRepository.deleteProject(project)
            } catch (e: Exception) {
                Log.e("ProjectListVM", "Failed to delete project", e)
                snackbarHostState.showSnackbar("Couldn't delete project")
            }
        }
    }

    fun onArchiveProject(projectId: Long) {
        viewModelScope.launch {
            try {
                projectRepository.archiveProject(projectId)
            } catch (e: Exception) {
                Log.e("ProjectListVM", "Failed to archive project", e)
                snackbarHostState.showSnackbar("Couldn't archive project")
            }
        }
    }

    fun onReopenProject(projectId: Long) {
        viewModelScope.launch {
            try {
                projectRepository.reopenProject(projectId)
            } catch (e: Exception) {
                Log.e("ProjectListVM", "Failed to reopen project", e)
                snackbarHostState.showSnackbar("Couldn't reopen project")
            }
        }
    }

    /**
     * Stage pasted content for the import preview screen to consume.
     * The preview ViewModel reads it via [PendingImportContent.consume]
     * because Compose Navigation's nav-arg has a length cap that would
     * truncate real schedules.
     */
    fun stagePastedContent(content: String) {
        pendingImportContent.stage(content)
    }
}
