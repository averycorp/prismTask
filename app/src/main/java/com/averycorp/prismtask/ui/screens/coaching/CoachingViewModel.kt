package com.averycorp.prismtask.ui.screens.coaching

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.CoachingRepository
import com.averycorp.prismtask.data.repository.CoachingResult
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for AI coaching features integrated across the app.
 * Manages state for:
 * - Energy check-in card (Today view, Trigger 3)
 * - Welcome-back dialog (Today view, Trigger 4)
 * - "Help me start" / stuck coaching (task detail, Trigger 1)
 * - Perfectionism coaching (task detail, Trigger 2)
 * - Celebration snackbar (task completion, Trigger 5)
 * - Task breakdown (task detail / quick-add, Trigger 6)
 */
@HiltViewModel
class CoachingViewModel
@Inject
constructor(
    private val coachingRepository: CoachingRepository,
    private val proFeatureGate: ProFeatureGate,
    private val billingManager: BillingManager
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    private val _statusMessages = MutableSharedFlow<String>()
    val statusMessages: SharedFlow<String> = _statusMessages

    // region Energy check-in (Trigger 3)

    private val _showEnergyCheckIn = MutableStateFlow(false)
    val showEnergyCheckIn: StateFlow<Boolean> = _showEnergyCheckIn

    private val _selectedEnergy = MutableStateFlow<String?>(null)
    val selectedEnergy: StateFlow<String?> = _selectedEnergy

    private val _energyPlanMessage = MutableStateFlow<String?>(null)
    val energyPlanMessage: StateFlow<String?> = _energyPlanMessage

    private val _energyPlanLoading = MutableStateFlow(false)
    val energyPlanLoading: StateFlow<Boolean> = _energyPlanLoading

    /**
     * Called when the Today screen loads. Checks if the energy check-in
     * card should be shown based on tier, task count, and prior check-in.
     */
    fun checkEnergyCheckIn(todayTaskCount: Int) {
        viewModelScope.launch {
            val isPro = proFeatureGate.isPro()
            if (!isPro) {
                _showEnergyCheckIn.value = false
                return@launch
            }
            if (todayTaskCount < 2) {
                _showEnergyCheckIn.value = false
                return@launch
            }
            val existing = coachingRepository.getTodayEnergyLevel()
            if (existing != null) {
                _selectedEnergy.value = existing
                _showEnergyCheckIn.value = false
                return@launch
            }
            _showEnergyCheckIn.value = true
        }
    }

    fun onSelectEnergy(
        level: String,
        todayTasks: List<TaskEntity>,
        overdueCount: Int,
        yesterdayCompleted: Int,
        yesterdayTotal: Int
    ) {
        _selectedEnergy.value = level
        _energyPlanLoading.value = true
        viewModelScope.launch {
            coachingRepository.setTodayEnergyLevel(level)
            val result = coachingRepository.getEnergyPlan(
                energyLevel = level,
                todayTasks = todayTasks,
                overdueCount = overdueCount,
                yesterdayCompleted = yesterdayCompleted,
                yesterdayTotal = yesterdayTotal
            )
            when (result) {
                is CoachingResult.Success -> {
                    _energyPlanMessage.value = result.response.message
                }
                is CoachingResult.UpgradeRequired -> {
                    _showUpgradePrompt.value = true
                }
                is CoachingResult.Error -> {
                    Log.e("CoachingVM", "Energy plan error: ${result.message}")
                    _energyPlanMessage.value = null
                }
                is CoachingResult.FreeLimitReached -> { /* shouldn't happen for energy */ }
            }
            _energyPlanLoading.value = false
        }
    }

    fun dismissEnergyCheckIn() {
        _showEnergyCheckIn.value = false
    }

    // endregion

    // region Welcome back (Trigger 4)

    private val _showWelcomeBack = MutableStateFlow(false)
    val showWelcomeBack: StateFlow<Boolean> = _showWelcomeBack

    private val _welcomeBackMessage = MutableStateFlow<String?>(null)
    val welcomeBackMessage: StateFlow<String?> = _welcomeBackMessage

    private val _welcomeBackLoading = MutableStateFlow(false)
    val welcomeBackLoading: StateFlow<Boolean> = _welcomeBackLoading

    /**
     * Called on app open. Checks welcome-back eligibility and fetches
     * the welcome message if applicable.
     */
    fun checkWelcomeBack(overdueCount: Int, recentCompletions: Int) {
        viewModelScope.launch {
            val daysAbsent = coachingRepository.checkWelcomeBackEligibility()
            if (daysAbsent == null) {
                coachingRepository.recordAppOpen()
                return@launch
            }

            _showWelcomeBack.value = true
            _welcomeBackLoading.value = true

            val result = coachingRepository.getWelcomeBack(
                daysAbsent = daysAbsent,
                overdueCount = overdueCount,
                recentCompletions = recentCompletions
            )
            when (result) {
                is CoachingResult.Success -> {
                    _welcomeBackMessage.value = result.response.message
                }
                is CoachingResult.Error -> {
                    Log.e("CoachingVM", "Welcome back error: ${result.message}")
                    _welcomeBackMessage.value = "Welcome back. Ready to pick up where you left off?"
                }
                else -> {
                    _showWelcomeBack.value = false
                }
            }
            _welcomeBackLoading.value = false
            coachingRepository.recordAppOpen()
        }
    }

    fun dismissWelcomeBack() {
        _showWelcomeBack.value = false
        viewModelScope.launch {
            coachingRepository.dismissWelcomeBack()
        }
    }

    // endregion

    // region Stuck / "Help me start" (Trigger 1)

    private val _stuckMessage = MutableStateFlow<String?>(null)
    val stuckMessage: StateFlow<String?> = _stuckMessage

    private val _stuckLoading = MutableStateFlow(false)
    val stuckLoading: StateFlow<Boolean> = _stuckLoading

    fun getStuckHelp(taskId: Long) {
        _stuckLoading.value = true
        _stuckMessage.value = null
        viewModelScope.launch {
            val result = coachingRepository.getStuckCoaching(taskId)
            when (result) {
                is CoachingResult.Success -> {
                    _stuckMessage.value = result.response.message
                }
                is CoachingResult.UpgradeRequired -> {
                    _showUpgradePrompt.value = true
                }
                is CoachingResult.Error -> {
                    Log.e("CoachingVM", "Stuck coaching error: ${result.message}")
                    _stuckMessage.value = null
                    _errorMessage.value = result.message
                }
                is CoachingResult.FreeLimitReached -> { /* shouldn't happen for stuck */ }
            }
            _stuckLoading.value = false
        }
    }

    fun dismissStuckMessage() {
        _stuckMessage.value = null
    }

    // endregion

    // region Perfectionism detection (Trigger 2)

    private val _perfectionismMessage = MutableStateFlow<String?>(null)
    val perfectionismMessage: StateFlow<String?> = _perfectionismMessage

    private val _perfectionismLoading = MutableStateFlow(false)
    val perfectionismLoading: StateFlow<Boolean> = _perfectionismLoading

    fun checkPerfectionism(
        taskId: Long,
        editCount: Int,
        rescheduleCount: Int,
        subtasksAdded: Int,
        subtasksCompleted: Int
    ) {
        if (!coachingRepository.shouldShowPerfectionismCard(
                editCount,
                rescheduleCount,
                subtasksAdded,
                subtasksCompleted
            )
        ) {
            return
        }

        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_COACHING)) return

        _perfectionismLoading.value = true
        viewModelScope.launch {
            val result = coachingRepository.getPerfectionismCoaching(
                taskId = taskId,
                editCount = editCount,
                rescheduleCount = rescheduleCount
            )
            when (result) {
                is CoachingResult.Success -> {
                    _perfectionismMessage.value = result.response.message
                }
                is CoachingResult.Error -> {
                    Log.e("CoachingVM", "Perfectionism coaching error: ${result.message}")
                }
                else -> {}
            }
            _perfectionismLoading.value = false
        }
    }

    fun dismissPerfectionism() {
        _perfectionismMessage.value = null
    }

    // endregion

    // region Celebration (Trigger 5)

    private val _celebrationMessage = MutableStateFlow<String?>(null)
    val celebrationMessage: StateFlow<String?> = _celebrationMessage

    fun onTaskCompleted(
        taskId: Long,
        completedSubtaskCount: Int = 0,
        totalSubtaskCount: Int = 0,
        daysOverdue: Int = 0,
        firstAfterGap: Boolean = false
    ) {
        if (!coachingRepository.shouldCelebrate(
                completedSubtaskCount,
                totalSubtaskCount,
                daysOverdue,
                firstAfterGap
            )
        ) {
            return
        }

        viewModelScope.launch {
            val result = coachingRepository.getCelebration(
                taskId = taskId,
                completedSubtaskCount = completedSubtaskCount,
                totalSubtaskCount = totalSubtaskCount,
                daysOverdue = daysOverdue,
                firstAfterGap = firstAfterGap
            )
            when (result) {
                is CoachingResult.Success -> {
                    _celebrationMessage.value = result.response.message
                }
                else -> {}
            }
        }
    }

    fun dismissCelebration() {
        _celebrationMessage.value = null
    }

    // endregion

    // region Task breakdown (Trigger 6)

    private val _breakdownSubtasks = MutableStateFlow<List<String>>(emptyList())
    val breakdownSubtasks: StateFlow<List<String>> = _breakdownSubtasks

    private val _breakdownLoading = MutableStateFlow(false)
    val breakdownLoading: StateFlow<Boolean> = _breakdownLoading

    private val _remainingBreakdowns = MutableStateFlow<Int?>(null)
    val remainingBreakdowns: StateFlow<Int?> = _remainingBreakdowns

    fun getTaskBreakdown(taskId: Long, projectName: String? = null) {
        _breakdownLoading.value = true
        _breakdownSubtasks.value = emptyList()
        viewModelScope.launch {
            val result = coachingRepository.getTaskBreakdown(taskId, projectName)
            when (result) {
                is CoachingResult.Success -> {
                    _breakdownSubtasks.value = result.response.subtasks ?: emptyList()
                    _remainingBreakdowns.value = coachingRepository.getRemainingBreakdowns()
                }
                is CoachingResult.FreeLimitReached -> {
                    _showUpgradePrompt.value = true
                }
                is CoachingResult.UpgradeRequired -> {
                    _showUpgradePrompt.value = true
                }
                is CoachingResult.Error -> {
                    Log.e("CoachingVM", "Breakdown error: ${result.message}")
                    _errorMessage.value = result.message
                }
            }
            _breakdownLoading.value = false
        }
    }

    fun dismissBreakdown() {
        _breakdownSubtasks.value = emptyList()
    }

    fun refreshRemainingBreakdowns() {
        viewModelScope.launch {
            _remainingBreakdowns.value = coachingRepository.getRemainingBreakdowns()
        }
    }

    // endregion

    // region Upgrade prompt

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    // endregion

    // region Error

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun dismissError() {
        _errorMessage.value = null
    }

    // endregion

    /**
     * Should be called once when the Today screen initializes to determine
     * if we should suggest a breakdown for a task.
     */
    fun shouldSuggestBreakdown(
        task: TaskEntity,
        subtaskCount: Int
    ): Boolean = coachingRepository.shouldSuggestBreakdown(task, subtaskCount)

    fun restorePurchases() {
        viewModelScope.launch {
            try {
                billingManager.restorePurchases()
                _statusMessages.emit("Purchases restored")
            } catch (e: Exception) {
                _statusMessages.emit("Couldn't restore purchases")
            }
        }
    }
}
