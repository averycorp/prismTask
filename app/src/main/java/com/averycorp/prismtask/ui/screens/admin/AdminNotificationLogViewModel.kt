package com.averycorp.prismtask.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.domain.usecase.NotificationProjector
import com.averycorp.prismtask.domain.usecase.ProjectedNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminNotificationLogViewModel
@Inject
constructor(private val projector: NotificationProjector) : ViewModel() {
    private val _notifications = MutableStateFlow<List<ProjectedNotification>>(emptyList())
    val notifications: StateFlow<List<ProjectedNotification>> = _notifications.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _notifications.value = projector.projectAll()
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _isLoading.value = false
            }
        }
    }
}
