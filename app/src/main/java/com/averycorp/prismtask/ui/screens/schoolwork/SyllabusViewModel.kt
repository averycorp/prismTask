package com.averycorp.prismtask.ui.screens.schoolwork

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.remote.api.SyllabusConfirmRequest
import com.averycorp.prismtask.data.remote.api.SyllabusEventResponse
import com.averycorp.prismtask.data.remote.api.SyllabusParseResponse
import com.averycorp.prismtask.data.remote.api.SyllabusRecurringItemResponse
import com.averycorp.prismtask.data.remote.api.SyllabusTaskResponse
import com.averycorp.prismtask.data.repository.FileTooLargeException
import com.averycorp.prismtask.data.repository.SyllabusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class SyllabusViewModel
@Inject
constructor(
    private val syllabusRepository: SyllabusRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {
    init {
        val uriString = savedStateHandle.get<String>("uri")
        if (!uriString.isNullOrBlank()) {
            val uri = Uri.parse(uriString)
            onSyllabusSelected(uri, appContext)
        }
    }

    sealed class UiState {
        data object Idle : UiState()

        data object Uploading : UiState()

        data class Review(val result: SyllabusParseResponse) : UiState()

        data object Confirming : UiState()

        data class Success(val tasksCreated: Int, val eventsCreated: Int, val recurringCreated: Int) : UiState()

        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    private val _snackbar = MutableSharedFlow<String>()
    val snackbar: SharedFlow<String> = _snackbar

    // Mutable review state
    private val _checkedTasks = MutableStateFlow<Set<Int>>(emptySet())
    val checkedTasks: StateFlow<Set<Int>> = _checkedTasks

    private val _checkedEvents = MutableStateFlow<Set<Int>>(emptySet())
    val checkedEvents: StateFlow<Set<Int>> = _checkedEvents

    private val _checkedRecurring = MutableStateFlow<Set<Int>>(emptySet())
    val checkedRecurring: StateFlow<Set<Int>> = _checkedRecurring

    private val _editedTasks = MutableStateFlow<Map<Int, SyllabusTaskResponse>>(emptyMap())
    val editedTasks: StateFlow<Map<Int, SyllabusTaskResponse>> = _editedTasks

    private val _editedEvents = MutableStateFlow<Map<Int, SyllabusEventResponse>>(emptyMap())
    val editedEvents: StateFlow<Map<Int, SyllabusEventResponse>> = _editedEvents

    private val _editedRecurring = MutableStateFlow<Map<Int, SyllabusRecurringItemResponse>>(emptyMap())
    val editedRecurring: StateFlow<Map<Int, SyllabusRecurringItemResponse>> = _editedRecurring

    fun onSyllabusSelected(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            _uiState.value = UiState.Uploading
            try {
                val result = syllabusRepository.parseSyllabus(uri, context)
                if (result.tasks.isEmpty() && result.events.isEmpty() && result.recurringSchedule.isEmpty()) {
                    _uiState.value = UiState.Error("No deadlines or events found in this syllabus")
                    return@launch
                }
                // Initialize all items as checked
                _checkedTasks.value = result.tasks.indices.toSet()
                _checkedEvents.value = result.events.indices.toSet()
                _checkedRecurring.value = result.recurringSchedule.indices.toSet()
                _editedTasks.value = emptyMap()
                _editedEvents.value = emptyMap()
                _editedRecurring.value = emptyMap()
                _uiState.value = UiState.Review(result)
            } catch (e: FileTooLargeException) {
                _uiState.value = UiState.Idle
                _snackbar.emit("PDF must be under 10MB")
            } catch (e: HttpException) {
                _uiState.value = UiState.Idle
                val detail = try {
                    e.response()?.errorBody()?.string()?.let { body ->
                        org.json.JSONObject(body).optString("detail", e.message())
                    } ?: e.message()
                } catch (_: Exception) {
                    e.message()
                }
                _snackbar.emit(detail ?: "Upload failed")
            } catch (e: Exception) {
                _uiState.value = UiState.Idle
                _snackbar.emit("Upload failed \u2014 check your connection")
            }
        }
    }

    fun onTaskToggled(index: Int, checked: Boolean) {
        _checkedTasks.value = if (checked) {
            _checkedTasks.value + index
        } else {
            _checkedTasks.value - index
        }
    }

    fun onEventToggled(index: Int, checked: Boolean) {
        _checkedEvents.value = if (checked) {
            _checkedEvents.value + index
        } else {
            _checkedEvents.value - index
        }
    }

    fun onRecurringToggled(index: Int, checked: Boolean) {
        _checkedRecurring.value = if (checked) {
            _checkedRecurring.value + index
        } else {
            _checkedRecurring.value - index
        }
    }

    fun onTaskEdited(index: Int, task: SyllabusTaskResponse) {
        _editedTasks.value = _editedTasks.value + (index to task)
    }

    fun onEventEdited(index: Int, event: SyllabusEventResponse) {
        _editedEvents.value = _editedEvents.value + (index to event)
    }

    fun onRecurringEdited(index: Int, item: SyllabusRecurringItemResponse) {
        _editedRecurring.value = _editedRecurring.value + (index to item)
    }

    fun totalCheckedCount(): Int = _checkedTasks.value.size + _checkedEvents.value.size + _checkedRecurring.value.size

    fun getEffectiveTask(index: Int, original: SyllabusTaskResponse): SyllabusTaskResponse = _editedTasks.value[index] ?: original

    fun getEffectiveEvent(index: Int, original: SyllabusEventResponse): SyllabusEventResponse = _editedEvents.value[index] ?: original

    fun getEffectiveRecurring(index: Int, original: SyllabusRecurringItemResponse): SyllabusRecurringItemResponse =
        _editedRecurring.value[index] ?: original

    fun onConfirm() {
        val state = _uiState.value
        if (state !is UiState.Review) return

        viewModelScope.launch {
            _uiState.value = UiState.Confirming
            try {
                val confirmedTasks = state.result.tasks
                    .filterIndexed { i, _ -> i in _checkedTasks.value }
                    .mapIndexed { i, t -> getEffectiveTask(i, t) }

                val confirmedEvents = state.result.events
                    .filterIndexed { i, _ -> i in _checkedEvents.value }
                    .mapIndexed { i, e -> getEffectiveEvent(i, e) }

                val confirmedRecurring = state.result.recurringSchedule
                    .filterIndexed { i, _ -> i in _checkedRecurring.value }
                    .mapIndexed { i, r -> getEffectiveRecurring(i, r) }

                val request = SyllabusConfirmRequest(
                    courseName = state.result.courseName,
                    tasks = confirmedTasks,
                    events = confirmedEvents,
                    recurringSchedule = confirmedRecurring
                )

                val response = syllabusRepository.confirmSyllabus(request)
                _uiState.value = UiState.Success(
                    tasksCreated = response.tasksCreated,
                    eventsCreated = response.eventsCreated,
                    recurringCreated = response.recurringCreated
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Review(state.result)
                _snackbar.emit("Failed to add items \u2014 please try again")
            }
        }
    }

    fun resetToIdle() {
        _uiState.value = UiState.Idle
    }
}
