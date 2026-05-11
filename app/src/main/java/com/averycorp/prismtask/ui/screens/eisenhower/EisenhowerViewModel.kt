package com.averycorp.prismtask.ui.screens.eisenhower

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.remote.api.EisenhowerCategorization
import com.averycorp.prismtask.data.remote.api.EisenhowerRequest
import com.averycorp.prismtask.data.remote.api.EisenhowerSummary
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.EisenhowerQuadrant
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sealed UI state for the Eisenhower categorize action.
 *
 * Replaces the old parallel `isLoading` / `error` flags so the screen
 * can't render an inconsistent combination (e.g. spinner + error toast +
 * stale grid). The per-task [quadrants] StateFlow is Room-sourced and
 * lives alongside this state — [quadrants] drives what renders in the
 * cells, while [EisenhowerUiState] drives the categorize-action UX.
 */
sealed interface EisenhowerUiState {
    /** No categorize action in flight and none completed this session. */
    data object Idle : EisenhowerUiState

    /** Categorize action in flight. */
    data object Loading : EisenhowerUiState

    /** Categorize returned at least one quadrant assignment. */
    data class Success(val categorizations: List<EisenhowerCategorization>, val summary: EisenhowerSummary) : EisenhowerUiState

    /**
     * Categorize returned an empty response — usually because the user
     * has no incomplete tasks. Surfaces a friendly screen-level empty
     * state instead of leaving the grid silently unchanged.
     */
    data class Empty(val reason: String) : EisenhowerUiState

    /** Categorize failed (network, 5xx, parse). */
    data class Error(val message: String) : EisenhowerUiState
}

@HiltViewModel
class EisenhowerViewModel
@Inject
constructor(
    private val taskDao: TaskDao,
    private val api: PrismTaskApi,
    private val taskRepository: TaskRepository,
    private val proFeatureGate: ProFeatureGate
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    private val _allIncompleteTasks = taskDao.getIncompleteRootTasks()

    val quadrants: StateFlow<Map<String, List<TaskEntity>>> = _allIncompleteTasks
        .map { tasks ->
            val categorized = tasks.filter { it.eisenhowerQuadrant != null && it.archivedAt == null }
            mapOf(
                "Q1" to categorized.filter { it.eisenhowerQuadrant == "Q1" },
                "Q2" to categorized.filter { it.eisenhowerQuadrant == "Q2" },
                "Q3" to categorized.filter { it.eisenhowerQuadrant == "Q3" },
                "Q4" to categorized.filter { it.eisenhowerQuadrant == "Q4" }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _uiState = MutableStateFlow<EisenhowerUiState>(EisenhowerUiState.Idle)
    val uiState: StateFlow<EisenhowerUiState> = _uiState

    private val _lastCategorizedAt = MutableStateFlow<Long?>(null)
    val lastCategorizedAt: StateFlow<Long?> = _lastCategorizedAt

    private val _expandedQuadrant = MutableStateFlow<String?>(null)
    val expandedQuadrant: StateFlow<String?> = _expandedQuadrant

    init {
        // Check if tasks are already categorized
        viewModelScope.launch {
            _allIncompleteTasks.collect { tasks ->
                val latest = tasks.mapNotNull { it.eisenhowerUpdatedAt }.maxOrNull()
                _lastCategorizedAt.value = latest
            }
        }
    }

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    fun categorize() {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER)) {
            _showUpgradePrompt.value = true
            return
        }
        viewModelScope.launch {
            _uiState.value = EisenhowerUiState.Loading
            try {
                val response = api.categorizeEisenhower(EisenhowerRequest())
                if (response.categorizations.isEmpty()) {
                    _uiState.value = EisenhowerUiState.Empty(
                        "No incomplete tasks to categorize. Add a task and try again."
                    )
                    return@launch
                }
                val now = System.currentTimeMillis()
                for (cat in response.categorizations) {
                    // TODO(weekly-followup): TaskEntity PK is Long but the
                    // backend now returns Firestore document IDs (strings).
                    // Until the Room schema gains a firestoreId column and a
                    // lookup by that column, try to parse as Long so the
                    // app continues to work for the Postgres-numeric-ID
                    // migration window. Alphanumeric IDs are skipped with a
                    // warning; the user will see the backend's response
                    // echoed but Room won't update until the follow-up.
                    val localId = cat.taskId.toLongOrNull()
                    if (localId == null) {
                        Log.w(
                            "EisenhowerVM",
                            "Skipping quadrant write for non-numeric task id: ${cat.taskId}"
                        )
                        continue
                    }
                    taskDao.updateEisenhowerQuadrant(
                        id = localId,
                        quadrant = cat.quadrant,
                        reason = cat.reason,
                        updatedAt = now
                    )
                }
                _lastCategorizedAt.value = now
                _uiState.value = EisenhowerUiState.Success(
                    categorizations = response.categorizations,
                    summary = response.summary
                )
            } catch (e: Exception) {
                Log.w("EisenhowerVM", "Categorize failed", e)
                _uiState.value = EisenhowerUiState.Error("Couldn't categorize tasks")
            }
        }
    }

    fun moveTaskToQuadrant(taskId: Long, quadrant: String) {
        viewModelScope.launch {
            taskRepository.setQuadrantManual(
                taskId = taskId,
                quadrant = EisenhowerQuadrant.fromCode(quadrant)
            )
        }
    }

    /**
     * Clear any manual override and run a fresh AI classification inline.
     * Pro-gated: Free users get the upgrade prompt instead of a silent
     * no-op. Failures surface through [EisenhowerUiState.Error] so the
     * existing screen banner + Snackbar render the message.
     */
    fun reclassify(taskId: Long) {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_EISENHOWER)) {
            _showUpgradePrompt.value = true
            return
        }
        viewModelScope.launch {
            _uiState.value = EisenhowerUiState.Loading
            val result = taskRepository.reclassify(taskId)
            if (result.isSuccess) {
                _uiState.value = EisenhowerUiState.Idle
            } else {
                Log.w("EisenhowerVM", "Reclassify failed", result.exceptionOrNull())
                _uiState.value = EisenhowerUiState.Error(
                    "Couldn't reach the AI classifier. Check your connection and try again."
                )
            }
        }
    }

    // Routes through TaskRepository so recurring tasks spawn their next
    // occurrence, the active reminder is cancelled, and the completion is
    // mirrored to the sync tracker / calendar push / widgets — same path
    // every other complete entry point uses. Audit:
    // docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md (Item 3).
    fun completeTask(taskId: Long) {
        viewModelScope.launch {
            taskRepository.completeTask(taskId)
        }
    }

    fun expandQuadrant(quadrant: String?) {
        _expandedQuadrant.value = quadrant
    }

    /**
     * Clear a transient Error/Empty state and return to Idle so the screen
     * stops rendering the banner. Idempotent for Idle/Loading/Success.
     */
    fun dismissUiMessage() {
        when (_uiState.value) {
            is EisenhowerUiState.Error, is EisenhowerUiState.Empty -> {
                _uiState.value = EisenhowerUiState.Idle
            }
            else -> Unit
        }
    }
}
