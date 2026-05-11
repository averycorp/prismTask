package com.averycorp.prismtask.ui.screens.briefing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.remote.api.DailyBriefingRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class BriefingPriority(val taskId: Long, val title: String, val reason: String)

data class SuggestedTask(val taskId: Long, val title: String, val suggestedTime: String, val reason: String)

data class DailyBriefing(
    val greeting: String,
    val dayType: String,
    val topPriorities: List<BriefingPriority>,
    val headsUp: List<String>,
    val suggestedOrder: List<SuggestedTask>,
    val habitReminders: List<String>,
    // Titles of priorities/suggestions whose Firestore doc IDs couldn't be
    // resolved to local Long task ids — typically tasks created on another
    // device that haven't synced down yet. Surfaced as a footer so partial
    // briefings still render.
    val pendingSyncTitles: List<String> = emptyList()
)

@HiltViewModel
class DailyBriefingViewModel
@Inject
constructor(
    private val api: PrismTaskApi,
    private val taskDao: TaskDao,
    private val proFeatureGate: ProFeatureGate
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    private val _briefing = MutableStateFlow<DailyBriefing?>(null)
    val briefing: StateFlow<DailyBriefing?> = _briefing

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt

    private val _orderApplied = MutableStateFlow(false)
    val orderApplied: StateFlow<Boolean> = _orderApplied

    private var cachedDate: String? = null

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    fun generateBriefing(date: String? = null) {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_BRIEFING)) {
            _showUpgradePrompt.value = true
            return
        }

        val targetDate = date ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Use cache if available for the same date
        if (cachedDate == targetDate && _briefing.value != null) {
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _orderApplied.value = false
            try {
                val response = api.getDailyBriefing(DailyBriefingRequest(date = targetDate))
                // Resolve Firestore doc IDs to local Long task ids. Tasks not
                // present locally (e.g. created on another device and not yet
                // synced down) are demoted into pendingSyncTitles so the rest
                // of the briefing still renders.
                val pendingSync = mutableListOf<String>()
                val resolvedPriorities = response.topPriorities.mapNotNull { p ->
                    val localId = taskDao.getIdByCloudId(p.taskId)
                    if (localId == null) {
                        pendingSync += p.title
                        null
                    } else {
                        BriefingPriority(localId, p.title, p.reason)
                    }
                }
                val resolvedSuggestions = response.suggestedOrder.mapNotNull { s ->
                    val localId = taskDao.getIdByCloudId(s.taskId)
                    if (localId == null) {
                        pendingSync += s.title
                        null
                    } else {
                        SuggestedTask(localId, s.title, s.suggestedTime, s.reason)
                    }
                }
                _briefing.value = DailyBriefing(
                    greeting = response.greeting,
                    dayType = response.dayType,
                    topPriorities = resolvedPriorities,
                    headsUp = response.headsUp,
                    suggestedOrder = resolvedSuggestions,
                    habitReminders = response.habitReminders,
                    pendingSyncTitles = pendingSync.distinct()
                )
                cachedDate = targetDate
            } catch (e: Exception) {
                _error.value = "Couldn't generate briefing"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshBriefing() {
        cachedDate = null
        _briefing.value = null
        generateBriefing()
    }

    fun applyOrder() {
        val briefing = _briefing.value ?: return
        viewModelScope.launch {
            try {
                val today = System.currentTimeMillis()
                val updates = briefing.suggestedOrder.mapIndexed { index, task ->
                    Pair(task.taskId, index)
                }
                for ((taskId, sortOrder) in updates) {
                    taskDao.updatePlannedDateAndSortOrder(taskId, today, sortOrder)
                }
                _orderApplied.value = true
            } catch (e: Exception) {
                _error.value = "Couldn't apply order"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
