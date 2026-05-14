package com.averycorp.prismtask.ui.screens.schoolwork

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.CourseCompletionEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.SchoolworkRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.ChecklistParsedTask
import com.averycorp.prismtask.domain.usecase.ChecklistParser
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SchoolworkViewModel
@Inject
constructor(
    private val repository: SchoolworkRepository,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val tagRepository: TagRepository,
    private val checklistParser: ChecklistParser,
    val proFeatureGate: ProFeatureGate
) : ViewModel() {
    val courses: StateFlow<List<CourseEntity>> = repository
        .getActiveCourses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAssignments: StateFlow<List<AssignmentEntity>> = repository
        .getAllAssignments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayCompletions: StateFlow<List<CourseCompletionEntity>> = repository
        .getTodayCompletions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _snackbar = MutableSharedFlow<String>()
    val snackbar: SharedFlow<String> = _snackbar

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    // --- File import ---

    fun importChecklist(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val content = context.contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: run {
                        _snackbar.emit("Could not read file")
                        return@launch
                    }

                importContent(content)
            } catch (e: Exception) {
                _snackbar.emit("Import failed")
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importFromText(content: String) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                importContent(content)
            } catch (e: Exception) {
                _snackbar.emit("Import failed")
            } finally {
                _isImporting.value = false
            }
        }
    }

    private suspend fun importContent(content: String) {
        val result = checklistParser.parse(content)
        if (result == null) {
            _snackbar.emit("Could not parse checklist format")
            return
        }

        // 1. Create the course + assignments in the schoolwork system
        val courseCount = courses.value.size
        val courseId = repository.insertCourse(
            CourseEntity(
                name = result.course.name,
                code = result.course.code,
                color = courseCount % 8,
                icon = result.project.icon,
                sortOrder = courseCount
            )
        )

        for (a in result.course.assignments) {
            repository.insertAssignment(
                AssignmentEntity(
                    courseId = courseId,
                    title = a.title,
                    dueDate = a.dueDate,
                    completed = a.completed,
                    completedAt = if (a.completed) System.currentTimeMillis() else null,
                    notes = a.time
                )
            )
        }

        // 2. Create the project in the task system
        val projectId = projectRepository.addProject(
            name = result.project.name,
            color = result.project.color,
            icon = result.project.icon
        )

        // 3. Create tags (reuse existing by name if possible)
        val tagNameToId = mutableMapOf<String, Long>()
        for (tag in result.tags) {
            val tagId = tagRepository.addTag(name = tag.name, color = tag.color)
            tagNameToId[tag.name] = tagId
        }

        // 4. Create tasks with subtasks, tags, and full metadata
        var taskCount = 0
        for (task in result.tasks) {
            createTaskTree(task, projectId, null, tagNameToId)
            taskCount++
        }

        val subtaskCount = result.tasks.sumOf { countSubtasks(it) }
        val tagCount = result.tags.size
        val importLabel = result.course.code.ifBlank { result.course.name }
        _snackbar.emit(
            "Imported $importLabel: $taskCount tasks" +
                (if (subtaskCount > 0) ", $subtaskCount subtasks" else "") +
                (if (tagCount > 0) ", $tagCount tags" else "") +
                " + project"
        )
    }

    private suspend fun createTaskTree(
        parsed: ChecklistParsedTask,
        projectId: Long,
        parentTaskId: Long?,
        tagNameToId: Map<String, Long>
    ) {
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            title = parsed.title,
            description = parsed.description,
            dueDate = parsed.dueDate,
            priority = parsed.priority,
            isCompleted = parsed.completed,
            completedAt = if (parsed.completed) now else null,
            projectId = projectId,
            parentTaskId = parentTaskId,
            estimatedDuration = parsed.estimatedMinutes,
            createdAt = now,
            updatedAt = now
        )
        val taskId = taskRepository.insertTask(task)

        // Assign tags
        val tagIds = parsed.tags.mapNotNull { tagNameToId[it] }
        if (tagIds.isNotEmpty()) {
            tagRepository.setTagsForTask(taskId, tagIds)
        }

        // Recursively create subtasks
        for (subtask in parsed.subtasks) {
            createTaskTree(subtask, projectId, taskId, tagNameToId)
        }
    }

    private fun countSubtasks(task: ChecklistParsedTask): Int = task.subtasks.size + task.subtasks.sumOf {
        countSubtasks(it)
    }

    // --- Daily course completions ---

    fun toggleCourseCompletion(courseId: Long) {
        viewModelScope.launch { repository.toggleCourseCompletion(courseId) }
    }

    fun resetToday() {
        viewModelScope.launch { repository.resetToday() }
    }

    // --- Assignments ---

    fun toggleAssignmentComplete(id: Long) {
        viewModelScope.launch { repository.toggleAssignmentComplete(id) }
    }

    fun addAssignment(courseId: Long, title: String, dueDate: Long?) {
        viewModelScope.launch {
            repository.insertAssignment(
                AssignmentEntity(courseId = courseId, title = title, dueDate = dueDate)
            )
        }
    }

    fun deleteAssignment(id: Long) {
        viewModelScope.launch { repository.deleteAssignment(id) }
    }

    // --- Courses ---

    fun deleteCourse(id: Long) {
        viewModelScope.launch { repository.deleteCourse(id) }
    }
}
