package com.averycorp.prismtask.ui.screens.briefing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.remote.api.DailyBriefingRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class BriefingPriority(
    val taskId: Long,
    val title: String,
    val reason: String
)

data class SuggestedTask(
    val taskId: Long,
    val title: String,
    val suggestedTime: String,
    val reason: String
)

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
    private val taskRepository: TaskRepository,
    private val proFeatureGate: ProFeatureGate,
    private val billingManager: BillingManager
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages

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
                    val localId = taskRepository.getIdByCloudId(p.taskId)
                    if (localId == null) {
                        pendingSync += p.title
                        null
                    } else {
                        BriefingPriority(localId, p.title, p.reason)
                    }
                }
                val resolvedSuggestions = response.suggestedOrder.mapNotNull { s ->
                    val localId = taskRepository.getIdByCloudId(s.taskId)
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
            } catch (e: HttpException) {
                _error.value = mapBriefingHttpError(e.code())
            } catch (e: IOException) {
                _error.value = "Network error — check your connection and try again."
            } catch (e: Exception) {
                _error.value = "Couldn't generate briefing"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Map known backend failure modes to actionable user-facing copy. Anything
    // we haven't enumerated falls through to the generic message — preserves
    // current behavior for unexpected codes while making the common cases
    // (rate limit hit, AI features off, Anthropic outage) self-diagnosing.
    private fun mapBriefingHttpError(code: Int): String = when (code) {
        401 -> "Sign in required to use Daily Briefing."
        403 -> "Daily Briefing requires a Pro subscription."
        429 -> "Daily Briefing limit reached — try again in a few minutes."
        451 -> "AI features are disabled. Turn them on in Settings → AI Features."
        500 -> "Briefing generation failed — please try again."
        503 -> "AI service temporarily unavailable — try again in a moment."
        else -> "Couldn't generate briefing (HTTP $code)"
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
                    taskRepository.updatePlannedDateAndSortOrder(taskId, today, sortOrder)
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

    fun restorePurchases() {
        viewModelScope.launch {
            try {
                billingManager.restorePurchases()
                _messages.emit("Purchases restored")
            } catch (e: Exception) {
                _messages.emit("Couldn't restore purchases")
            }
        }
    }
}
