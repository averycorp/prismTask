package com.averycorp.prismtask.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.repository.UserAiPreference
import com.averycorp.prismtask.data.repository.UserAiPreferenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the AI Memory Settings screen.
 *
 * The list of preferences is observed from Room (always reflects the
 * latest chat-mirrored snapshot); CRUD actions go through the repo,
 * which round-trips to the backend before updating Room.
 */
@HiltViewModel
class AiMemoryViewModel
@Inject
constructor(
    private val repository: UserAiPreferenceRepository
) : ViewModel() {

    val cap: Int = repository.maxPreferences

    val preferences: StateFlow<List<UserAiPreference>> =
        repository.preferencesAsDomain.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching { repository.refresh() }
                .onFailure {
                    _messages.tryEmit("Couldn't load preferences. Pull to retry.")
                }
            _isLoading.value = false
        }
    }

    fun addPreference(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (preferences.value.size >= cap) {
            _messages.tryEmit("Memory is full ($cap of $cap). Delete one to add another.")
            return
        }
        viewModelScope.launch {
            runCatching { repository.create(trimmed) }
                .onFailure { _messages.tryEmit("Couldn't save preference. Try again.") }
        }
    }

    fun updatePreference(id: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.update(id, trimmed) }
                .onFailure { _messages.tryEmit("Couldn't update preference. Try again.") }
        }
    }

    fun deletePreference(id: String) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onFailure { _messages.tryEmit("Couldn't delete preference. Try again.") }
        }
    }
}
