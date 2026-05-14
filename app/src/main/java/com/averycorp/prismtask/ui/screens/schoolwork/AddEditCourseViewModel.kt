package com.averycorp.prismtask.ui.screens.schoolwork

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.repository.SchoolworkRepository
import com.averycorp.prismtask.domain.usecase.CategoryDailyTaskController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddEditCourseViewModel
@Inject
constructor(
    private val repository: SchoolworkRepository,
    private val dailyTaskController: CategoryDailyTaskController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val courseId: Long = savedStateHandle.get<Long>("courseId") ?: -1L
    val isEditing = courseId != -1L

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code

    private val _icon = MutableStateFlow("\uD83D\uDCDA")
    val icon: StateFlow<String> = _icon

    private val _color = MutableStateFlow(0)
    val color: StateFlow<Int> = _color

    private val _createDailyTask = MutableStateFlow(false)
    val createDailyTask: StateFlow<Boolean> = _createDailyTask

    init {
        if (isEditing) {
            viewModelScope.launch {
                repository.getCourseById(courseId)?.let { course ->
                    _name.value = course.name
                    _code.value = course.code
                    _icon.value = course.icon
                    _color.value = course.color
                    _createDailyTask.value = course.createDailyTask
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _name.value = value
    }

    fun onCodeChange(value: String) {
        _code.value = value
    }

    fun onIconChange(value: String) {
        _icon.value = value
    }

    fun onColorChange(value: Int) {
        _color.value = value
    }

    fun onCreateDailyTaskChange(value: Boolean) {
        _createDailyTask.value = value
    }

    fun save(onDone: () -> Unit) {
        if (_name.value.isBlank()) return
        viewModelScope.launch {
            val name = _name.value.trim()
            val code = _code.value.trim()
            val wantTask = _createDailyTask.value
            if (isEditing) {
                val existing = repository.getCourseById(courseId) ?: return@launch
                val nextTaskId = resolveTaskId(
                    label = name,
                    emoji = _icon.value,
                    existingId = existing.dailyTaskId,
                    wantTask = wantTask
                )
                repository.updateCourse(
                    existing.copy(
                        name = name,
                        code = code,
                        icon = _icon.value,
                        color = _color.value,
                        createDailyTask = wantTask,
                        dailyTaskId = nextTaskId
                    )
                )
            } else {
                val newId = repository.insertCourse(
                    CourseEntity(
                        name = name,
                        code = code,
                        icon = _icon.value,
                        color = _color.value,
                        createDailyTask = wantTask
                    )
                )
                if (wantTask) {
                    val taskId = dailyTaskController.ensureDailyTask(
                        label = name,
                        emoji = _icon.value,
                        existingId = null
                    )
                    repository.getCourseById(newId)?.let { saved ->
                        repository.updateCourse(saved.copy(dailyTaskId = taskId))
                    }
                }
            }
            onDone()
        }
    }

    private suspend fun resolveTaskId(
        label: String,
        emoji: String,
        existingId: Long?,
        wantTask: Boolean
    ): Long? = if (wantTask) {
        dailyTaskController.ensureDailyTask(label, emoji, existingId)
    } else {
        dailyTaskController.removeDailyTask(existingId)
        null
    }
}
