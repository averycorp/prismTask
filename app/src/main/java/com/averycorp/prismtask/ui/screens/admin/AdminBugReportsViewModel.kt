package com.averycorp.prismtask.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.remote.api.AdminBugReportResponse
import com.averycorp.prismtask.data.repository.AdminBugReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminBugReportsViewModel
@Inject
constructor(private val repository: AdminBugReportRepository) : ViewModel() {
    private val _reports = MutableStateFlow<List<AdminBugReportResponse>>(emptyList())
    val reports: StateFlow<List<AdminBugReportResponse>> = _reports.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>(null)
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _severityFilter = MutableStateFlow<String?>(null)
    val severityFilter: StateFlow<String?> = _severityFilter.asStateFlow()

    private val _selected = MutableStateFlow<AdminBugReportResponse?>(null)
    val selected: StateFlow<AdminBugReportResponse?> = _selected.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = repository.listReports(
                statusFilter = _statusFilter.value,
                severity = _severityFilter.value,
                page = 1,
                limit = 100
            )
            result
                .onSuccess { _reports.value = it }
                .onFailure { _error.value = it.message ?: "Failed to load reports" }
            _isLoading.value = false
        }
    }

    fun setStatusFilter(value: String?) {
        _statusFilter.value = value
        refresh()
    }

    fun setSeverityFilter(value: String?) {
        _severityFilter.value = value
        refresh()
    }

    fun select(report: AdminBugReportResponse?) {
        _selected.value = report
    }

    fun updateStatus(reportId: String, newStatus: String, adminNotes: String? = null) {
        viewModelScope.launch {
            repository.updateStatus(reportId, newStatus, adminNotes)
                .onSuccess { updated ->
                    _reports.update { list ->
                        list.map { if (it.reportId == updated.reportId) updated else it }
                    }
                    if (_selected.value?.reportId == updated.reportId) {
                        _selected.value = updated
                    }
                    _messages.tryEmit("Report marked $newStatus")
                }
                .onFailure { _messages.tryEmit(it.message ?: "Failed to update report") }
        }
    }
}
