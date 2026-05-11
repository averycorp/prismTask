package com.averycorp.prismtask.ui.screens.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArchiveViewModel
@Inject
constructor(private val taskRepository: TaskRepository, private val sortPreferences: SortPreferences) :
    ViewModel() {
    val searchQuery = MutableStateFlow("")

    val currentSort: StateFlow<String> =
        sortPreferences
            .observeSortMode(SortPreferences.ScreenKeys.ARCHIVE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortPreferences.SortModes.DEFAULT)

    fun onChangeSort(sortMode: String) {
        viewModelScope.launch {
            sortPreferences.setSortMode(SortPreferences.ScreenKeys.ARCHIVE, sortMode)
        }
    }

    val archivedTasks: StateFlow<List<TaskEntity>> = combine(
        taskRepository.getArchivedTasks(),
        currentSort
    ) { tasks, sort -> sortTasks(tasks, sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedCount: StateFlow<Int> = taskRepository
        .getArchivedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val filteredArchive: StateFlow<List<TaskEntity>> = combine(
        searchQuery.flatMapLatest { query ->
            if (query.isBlank()) {
                taskRepository.getArchivedTasks()
            } else {
                taskRepository.searchArchivedTasks(query)
            }
        },
        currentSort
    ) { tasks, sort -> sortTasks(tasks, sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun sortTasks(tasks: List<TaskEntity>, sort: String): List<TaskEntity> = when (sort) {
        SortPreferences.SortModes.DUE_DATE -> tasks.sortedWith(
            compareBy<TaskEntity> { it.dueDate == null }.thenBy { it.dueDate }
        )
        SortPreferences.SortModes.PRIORITY -> tasks.sortedByDescending { it.priority }
        SortPreferences.SortModes.ALPHABETICAL -> tasks.sortedBy { it.title.lowercase() }
        SortPreferences.SortModes.DATE_CREATED -> tasks.sortedByDescending { it.createdAt }
        else -> tasks
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onUnarchive(taskId: Long) {
        viewModelScope.launch {
            taskRepository.unarchiveTask(taskId)
        }
    }

    fun onPermanentlyDelete(taskId: Long) {
        viewModelScope.launch {
            taskRepository.permanentlyDeleteTask(taskId)
        }
    }

    fun onClearAllArchived() {
        viewModelScope.launch {
            archivedTasks.value.forEach { task ->
                taskRepository.permanentlyDeleteTask(task.id)
            }
        }
    }
}
