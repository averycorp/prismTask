package com.averycorp.prismtask.ui.screens.projects

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.domain.usecase.ImportExclusions
import com.averycorp.prismtask.domain.usecase.ImportOutcome
import com.averycorp.prismtask.domain.usecase.ImportPlan
import com.averycorp.prismtask.domain.usecase.ProjectImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State + actions for the ProjectImportPreviewScreen.
 *
 * Mirrors the BatchPreview state-machine shape (Idle / Loading /
 * Loaded / Committing / Applied / Error) with the same re-entry
 * guards on [loadPlan] — Compose Navigation can re-fire the
 * triggering LaunchedEffect during the pop transition, and re-running
 * [ProjectImporter.materialise] on an Applied state would double-insert
 * the project tree.
 */
@HiltViewModel
class ProjectImportPreviewViewModel
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val projectImporter: ProjectImporter,
    private val pendingImportContent: PendingImportContent
) : ViewModel() {
    private val _state = MutableStateFlow<ImportPreviewState>(ImportPreviewState.Idle)
    val state: StateFlow<ImportPreviewState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ImportPreviewEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ImportPreviewEvent> = _events.asSharedFlow()

    private val _excludedTasks = MutableStateFlow<Set<Int>>(emptySet())
    val excludedTasks: StateFlow<Set<Int>> = _excludedTasks.asStateFlow()

    private val _excludedRisks = MutableStateFlow<Set<Int>>(emptySet())
    val excludedRisks: StateFlow<Set<Int>> = _excludedRisks.asStateFlow()

    /**
     * Load the plan from a file URI (passed via nav arg) or from the
     * paste handoff (signaled by `uriString = null`). The asProject
     * toggle controls whether we attempt rich parse + project creation
     * or wrap items as orphan tasks.
     */
    fun loadPlan(uriString: String?, asProject: Boolean) {
        when (val current = _state.value) {
            is ImportPreviewState.Loading,
            is ImportPreviewState.Committing,
            is ImportPreviewState.Applied -> return
            is ImportPreviewState.Loaded ->
                if (current.sourceKey == sourceKeyFor(uriString, asProject)) return
            ImportPreviewState.Idle, is ImportPreviewState.Error -> Unit
        }
        _state.value = ImportPreviewState.Loading(sourceKeyFor(uriString, asProject))
        viewModelScope.launch {
            try {
                val content = readContent(uriString)
                if (content.isNullOrBlank()) {
                    _state.value = ImportPreviewState.Error(
                        sourceKey = sourceKeyFor(uriString, asProject),
                        kind = ImportPreviewErrorKind.ReadFailure,
                        message = "Couldn't read import contents."
                    )
                    return@launch
                }
                val plan = projectImporter.parse(content, asProject)
                if (plan == null) {
                    _state.value = ImportPreviewState.Error(
                        sourceKey = sourceKeyFor(uriString, asProject),
                        kind = ImportPreviewErrorKind.Unparseable,
                        message = "Could not parse to-do list format."
                    )
                    return@launch
                }
                _state.value = ImportPreviewState.Loaded(
                    sourceKey = sourceKeyFor(uriString, asProject),
                    plan = plan,
                    asProject = asProject
                )
                _excludedTasks.value = emptySet()
                _excludedRisks.value = emptySet()
            } catch (e: Exception) {
                _state.value = ImportPreviewState.Error(
                    sourceKey = sourceKeyFor(uriString, asProject),
                    kind = ImportPreviewErrorKind.ReadFailure,
                    message = e.message ?: "Import failed."
                )
            }
        }
    }

    fun toggleTask(index: Int) {
        val current = _excludedTasks.value
        _excludedTasks.value = if (index in current) current - index else current + index
    }

    fun toggleRisk(index: Int) {
        val current = _excludedRisks.value
        _excludedRisks.value = if (index in current) current - index else current + index
    }

    fun approve() {
        val loaded = _state.value as? ImportPreviewState.Loaded ?: return
        val taskCount = loaded.plan.taskTitles.size
        val effectiveTasks = taskCount - _excludedTasks.value.count { it in 0 until taskCount }
        if (effectiveTasks == 0 && loaded.plan.riskTitles.isEmpty()) {
            viewModelScope.launch {
                _events.emit(ImportPreviewEvent.Cancelled(reason = "No items selected"))
            }
            return
        }
        _state.value = ImportPreviewState.Committing(loaded.sourceKey)
        viewModelScope.launch {
            try {
                val outcome = projectImporter.materialise(
                    plan = loaded.plan,
                    exclusions = ImportExclusions(
                        excludedTaskIndices = _excludedTasks.value,
                        excludedRiskIndices = _excludedRisks.value
                    )
                )
                _state.value = ImportPreviewState.Applied(loaded.sourceKey, outcome)
                _events.emit(ImportPreviewEvent.Approved(outcome))
            } catch (e: Exception) {
                _state.value = ImportPreviewState.Error(
                    sourceKey = loaded.sourceKey,
                    kind = ImportPreviewErrorKind.WriteFailure,
                    message = e.message ?: "Failed to commit import."
                )
            }
        }
    }

    fun cancel() {
        viewModelScope.launch {
            _events.emit(ImportPreviewEvent.Cancelled(reason = null))
        }
    }

    private fun sourceKeyFor(uriString: String?, asProject: Boolean): String =
        "${uriString ?: "<paste>"}|asProject=$asProject"

    private suspend fun readContent(uriString: String?): String? {
        if (uriString.isNullOrBlank()) {
            return pendingImportContent.consume()
        }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }
}

sealed class ImportPreviewState {
    data object Idle : ImportPreviewState()
    data class Loading(val sourceKey: String) : ImportPreviewState()
    data class Loaded(val sourceKey: String, val plan: ImportPlan, val asProject: Boolean) : ImportPreviewState()
    data class Committing(val sourceKey: String) : ImportPreviewState()
    data class Applied(val sourceKey: String, val outcome: ImportOutcome) : ImportPreviewState()
    data class Error(val sourceKey: String, val kind: ImportPreviewErrorKind, val message: String) : ImportPreviewState()
}

enum class ImportPreviewErrorKind { ReadFailure, Unparseable, WriteFailure }

sealed class ImportPreviewEvent {
    data class Approved(val outcome: ImportOutcome) : ImportPreviewEvent()
    data class Cancelled(val reason: String?) : ImportPreviewEvent()
}
