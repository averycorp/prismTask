package com.averycorp.prismtask.ui.screens.projects

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.domain.model.ProjectStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddEditProjectViewModel
@Inject
constructor(private val projectRepository: ProjectRepository, savedStateHandle: SavedStateHandle) :
    ViewModel() {
    private val _errorMessages = MutableSharedFlow<String>()
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    private val projectId: Long? = savedStateHandle.get<Long>("projectId")?.takeIf { it != -1L }
    val isEditMode: Boolean = projectId != null

    private var existingProject: ProjectEntity? = null

    var name by mutableStateOf("")
        private set
    var description by mutableStateOf("")
        private set
    var color by mutableStateOf("#4A90D9")
        private set
    var icon by mutableStateOf("\uD83D\uDCC1")
        private set
    var startDate by mutableStateOf<Long?>(null)
        private set
    var endDate by mutableStateOf<Long?>(null)
        private set
    var nameError by mutableStateOf(false)
        private set

    init {
        if (projectId != null) {
            viewModelScope.launch {
                projectRepository.getProjectById(projectId).firstOrNull()?.let { project ->
                    existingProject = project
                    name = project.name
                    description = project.description.orEmpty()
                    color = project.color
                    icon = project.icon
                    startDate = project.startDate
                    endDate = project.endDate
                }
            }
        }
    }

    fun onNameChange(value: String) {
        name = value
        if (value.isNotBlank()) nameError = false
    }

    fun onDescriptionChange(value: String) {
        description = value
    }

    fun onColorChange(value: String) {
        color = value
    }

    fun onIconChange(value: String) {
        icon = value
    }

    fun onStartDateChange(value: Long?) {
        startDate = value
    }

    fun onEndDateChange(value: Long?) {
        endDate = value
    }

    suspend fun saveProject(): Boolean {
        if (name.isBlank()) {
            nameError = true
            return false
        }

        return try {
            val existing = existingProject
            val trimmedDescription = description.trim().takeIf { it.isNotEmpty() }
            if (existing != null) {
                projectRepository.updateProject(
                    existing.copy(
                        name = name.trim(),
                        description = trimmedDescription,
                        color = color,
                        icon = icon,
                        // Reusing the habit-style hex picker means the token key
                        // is semantically the same value — keeping them dual-written
                        // unblocks future theme-token integration without a data
                        // migration (see docs/projects-feature.md).
                        themeColorKey = color,
                        startDate = startDate,
                        endDate = endDate
                    )
                )
            } else {
                projectRepository.addProject(
                    name = name.trim(),
                    description = trimmedDescription,
                    status = ProjectStatus.ACTIVE,
                    startDate = startDate,
                    endDate = endDate,
                    themeColorKey = color,
                    color = color,
                    icon = icon
                )
            }
            true
        } catch (e: Exception) {
            Log.e("AddEditProjectVM", "Failed to save project", e)
            _errorMessages.emit("Couldn't save project")
            false
        }
    }

    suspend fun deleteProject() {
        try {
            existingProject?.let { projectRepository.deleteProject(it) }
        } catch (e: Exception) {
            Log.e("AddEditProjectVM", "Failed to delete project", e)
            _errorMessages.emit("Couldn't delete project")
        }
    }
}
