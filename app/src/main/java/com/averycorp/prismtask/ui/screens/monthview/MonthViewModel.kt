package com.averycorp.prismtask.ui.screens.monthview

import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.ui.components.QuickRescheduleFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class DayInfo(
    val taskCount: Int,
    val completedCount: Int,
    val hasOverdue: Boolean,
    val hasUrgent: Boolean,
    val topPriority: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonthViewModel
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val sortPreferences: SortPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : ViewModel() {
    val snackbarHostState = SnackbarHostState()

    val projects: StateFlow<List<ProjectEntity>> = projectRepository
        .getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taskCountByProject: StateFlow<Map<Long, Int>> = taskRepository
        .getIncompleteRootTasks()
        .map { tasks ->
            tasks
                .groupingBy { it.projectId }
                .eachCount()
                .mapNotNull { (id, count) -> id?.let { it to count } }
                .toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val zone = ZoneId.systemDefault()

    val firstDayOfWeek: StateFlow<DayOfWeek> = taskBehaviorPreferences
        .getFirstDayOfWeek()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayOfWeek.MONDAY)

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = _currentMonth

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate

    val currentSort: StateFlow<String> =
        sortPreferences
            .observeSortMode(SortPreferences.ScreenKeys.MONTH_VIEW)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortPreferences.SortModes.DEFAULT)

    fun onChangeSort(sortMode: String) {
        viewModelScope.launch {
            sortPreferences.setSortMode(SortPreferences.ScreenKeys.MONTH_VIEW, sortMode)
        }
    }

    val monthDayInfos: StateFlow<Map<LocalDate, DayInfo>> = _currentMonth
        .flatMapLatest { month ->
            val firstDay = month.atDay(1)
            val lastDay = month.atEndOfMonth()
            val startMillis = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = lastDay
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            val today = LocalDate.now()

            taskRepository.getTasksDueOnDate(startMillis, endMillis).map { tasks ->
                val rootTasks = tasks.filter { it.parentTaskId == null && it.archivedAt == null }
                val grouped = mutableMapOf<LocalDate, MutableList<TaskEntity>>()
                for (task in rootTasks) {
                    val date = task.dueDate?.let {
                        java.time.Instant
                            .ofEpochMilli(it)
                            .atZone(zone)
                            .toLocalDate()
                    } ?: continue
                    grouped.getOrPut(date) { mutableListOf() }.add(task)
                }
                grouped.mapValues { (date, dayTasks) ->
                    DayInfo(
                        taskCount = dayTasks.size,
                        completedCount = dayTasks.count { it.isCompleted },
                        hasOverdue = date.isBefore(today) && dayTasks.any { !it.isCompleted },
                        hasUrgent = dayTasks.any { it.priority >= 4 },
                        topPriority = dayTasks.maxOfOrNull { it.priority } ?: 0
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val selectedDateTasks: StateFlow<List<TaskEntity>> = combine(
        _selectedDate.flatMapLatest { date ->
            if (date == null) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
                taskRepository.getTasksDueOnDate(start, end).map { tasks ->
                    tasks.filter { it.parentTaskId == null && it.archivedAt == null }
                }
            }
        },
        currentSort
    ) { tasks, sort ->
        // Always keep completed items at the bottom; apply the persisted sort
        // to the remainder so user preference is respected.
        val (done, open) = tasks.partition { it.isCompleted }
        sortTasks(open, sort) + sortTasks(done, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun sortTasks(tasks: List<TaskEntity>, sort: String): List<TaskEntity> = when (sort) {
        SortPreferences.SortModes.DUE_DATE -> tasks.sortedWith(
            compareBy<TaskEntity> { it.dueDate == null }.thenBy { it.dueDate }.thenByDescending { it.priority }
        )
        SortPreferences.SortModes.PRIORITY -> tasks.sortedByDescending { it.priority }
        SortPreferences.SortModes.ALPHABETICAL -> tasks.sortedBy { it.title.lowercase() }
        SortPreferences.SortModes.DATE_CREATED -> tasks.sortedByDescending { it.createdAt }
        else -> tasks.sortedByDescending { it.priority }
    }

    fun onPreviousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
    }

    fun onNextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
    }

    fun onGoToToday() {
        _currentMonth.value = YearMonth.now()
        _selectedDate.value = LocalDate.now()
    }

    fun onSelectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun onRescheduleTask(taskId: Long, newDueDate: Long?) {
        viewModelScope.launch {
            try {
                val previous = taskRepository.getTaskByIdOnce(taskId)?.dueDate
                taskRepository.rescheduleTask(taskId, newDueDate)
                val sod = taskBehaviorPreferences.getStartOfDay().first()
                val label = QuickRescheduleFormatter.describe(
                    newDueDate,
                    sodHour = sod.hour,
                    sodMinute = sod.minute
                )
                val result = snackbarHostState.showSnackbar(
                    message = "Rescheduled to $label",
                    actionLabel = "UNDO",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    taskRepository.rescheduleTask(taskId, previous)
                }
            } catch (e: Exception) {
                Log.e("MonthViewVM", "Failed to reschedule", e)
            }
        }
    }

    fun onPlanTaskForToday(taskId: Long) {
        viewModelScope.launch {
            try {
                taskRepository.planTaskForToday(taskId)
                snackbarHostState.showSnackbar("Planned for today", duration = SnackbarDuration.Short)
            } catch (e: Exception) {
                Log.e("MonthViewVM", "Failed to plan for today", e)
            }
        }
    }

    fun onMoveToProject(
        taskId: Long,
        newProjectId: Long?,
        cascadeSubtasks: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                val task = taskRepository.getTaskByIdOnce(taskId) ?: return@launch
                val previousParentProjectId = task.projectId
                val previousSubtaskProjects: Map<Long, Long?> = if (cascadeSubtasks) {
                    taskRepository.getSubtasks(taskId).first().associate { it.id to it.projectId }
                } else {
                    emptyMap()
                }

                taskRepository.moveToProject(taskId, newProjectId, cascadeSubtasks)
                val projectName = newProjectId?.let { id ->
                    projects.value.find { it.id == id }?.name
                } ?: "No Project"
                val result = snackbarHostState.showSnackbar(
                    message = "Moved '${task.title}' to $projectName",
                    actionLabel = "UNDO",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    taskRepository.moveToProject(taskId, previousParentProjectId, false)
                    previousSubtaskProjects.entries
                        .groupBy { it.value }
                        .forEach { (origProjectId, entries) ->
                            taskRepository.batchMoveToProject(entries.map { it.key }, origProjectId)
                        }
                }
            } catch (e: Exception) {
                Log.e("MonthViewVM", "Failed to move task to project", e)
            }
        }
    }

    fun onCreateProjectAndMoveTask(
        taskId: Long,
        name: String,
        cascadeSubtasks: Boolean = false
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val newId = projectRepository.addProject(name.trim())
                onMoveToProject(taskId, newId, cascadeSubtasks)
            } catch (e: Exception) {
                Log.e("MonthViewVM", "Failed to create project", e)
            }
        }
    }

    fun onDeleteTaskWithUndo(taskId: Long) {
        viewModelScope.launch {
            try {
                val savedTask = taskRepository.getTaskByIdOnce(taskId) ?: return@launch
                taskRepository.deleteTask(taskId)
                val result = snackbarHostState.showSnackbar(
                    message = "Task Deleted",
                    actionLabel = "UNDO",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    taskRepository.insertTask(savedTask)
                }
            } catch (e: Exception) {
                Log.e("MonthViewVM", "Failed to delete task", e)
            }
        }
    }

    fun onDuplicateTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val newId = taskRepository.duplicateTask(taskId, includeSubtasks = false)
                if (newId <= 0L) {
                    snackbarHostState.showSnackbar("Couldn't duplicate task")
                    return@launch
                }
                snackbarHostState.showSnackbar(
                    message = "Task Duplicated",
                    duration = SnackbarDuration.Short
                )
            } catch (e: Exception) {
                Log.e("MonthViewVM", "Failed to duplicate task", e)
            }
        }
    }

    suspend fun getSubtaskCount(taskId: Long): Int =
        taskRepository.getSubtasks(taskId).first().size
}
