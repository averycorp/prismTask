package com.averycorp.prismtask.ui.screens.timeline

import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.AiTimeBlockUseCase
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.TimeBlockHorizon
import com.averycorp.prismtask.ui.components.QuickRescheduleFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class TimeBlock(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val taskId: Long?,
    val priority: Int,
    val color: String
)

data class TimeBlockConfig(
    val dayStart: String = "09:00",
    val dayEnd: String = "18:00",
    val blockSizeMinutes: Int = 30,
    val includeBreaks: Boolean = true,
    val breakFrequencyMinutes: Int = 90,
    val breakDurationMinutes: Int = 15
)

data class AiScheduleBlock(
    val start: String,
    val end: String,
    val type: String,
    val taskId: Long?,
    val title: String,
    val reason: String,
    // v1.4.40: ISO date of the day this block belongs to. Always populated
    // on new-flow responses — the use case backfills from the request
    // anchor when Haiku omits it.
    val date: String
)

data class AiTimeBlockStats(
    val totalWorkMinutes: Int,
    val totalBreakMinutes: Int,
    val totalFreeMinutes: Int,
    val tasksScheduled: Int,
    val tasksDeferred: Int
)

data class AiSchedule(
    val blocks: List<AiScheduleBlock>,
    // (Long?, String): taskId is null for unresolved cross-device tasks
    // (Firestore doc ID returned by the AI but not yet synced down to this
    // device); the title is shown in the deferred footer either way.
    val unscheduledTasks: List<Pair<Long?, String>>,
    val stats: AiTimeBlockStats,
    // v1.4.40: how many days the proposed schedule covers (1 / 2 / 7).
    val horizonDays: Int = 1
)

/**
 * Sealed UI state for the Auto-Schedule action. Prevents the old failure
 * mode where an empty schedule silently rendered "0 tasks • 0h work" —
 * the [Empty] variant drives an explicit "Nothing to schedule" message.
 */
sealed interface AiScheduleUiState {
    data object Idle : AiScheduleUiState
    data object Loading : AiScheduleUiState
    data class Success(val schedule: AiSchedule) : AiScheduleUiState
    data class Empty(val reason: String) : AiScheduleUiState
    data class Error(val message: String) : AiScheduleUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val sortPreferences: SortPreferences,
    private val aiTimeBlockUseCase: AiTimeBlockUseCase,
    private val proFeatureGate: ProFeatureGate,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val billingManager: BillingManager
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    private val _timeBlockConfig = MutableStateFlow(TimeBlockConfig())
    val timeBlockConfig: StateFlow<TimeBlockConfig> = _timeBlockConfig

    // v1.4.40: Auto-block my day flow state. Horizon selection is
    // session-only (no DataStore) — matches how timeBlockConfig lives.
    private val _selectedHorizon = MutableStateFlow(TimeBlockHorizon.TODAY)
    val selectedHorizon: StateFlow<TimeBlockHorizon> = _selectedHorizon

    private val _showHorizonSheet = MutableStateFlow(false)
    val showHorizonSheet: StateFlow<Boolean> = _showHorizonSheet

    private val _showPreviewSheet = MutableStateFlow(false)
    val showPreviewSheet: StateFlow<Boolean> = _showPreviewSheet

    private val _scheduleUiState = MutableStateFlow<AiScheduleUiState>(AiScheduleUiState.Idle)
    val scheduleUiState: StateFlow<AiScheduleUiState> = _scheduleUiState

    // Back-compat derived views so existing screen call sites reading
    // "is there a schedule?" and "is a generate in flight?" keep working.
    val aiSchedule: StateFlow<AiSchedule?> = _scheduleUiState
        .map { (it as? AiScheduleUiState.Success)?.schedule }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isGeneratingSchedule: StateFlow<Boolean> = _scheduleUiState
        .map { it is AiScheduleUiState.Loading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _showTimeBlockSheet = MutableStateFlow(false)
    val showTimeBlockSheet: StateFlow<Boolean> = _showTimeBlockSheet

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt

    private val _scheduleError = MutableStateFlow<String?>(null)
    val scheduleError: StateFlow<String?> = _scheduleError

    val snackbarHostState = SnackbarHostState()

    val projects: StateFlow<List<ProjectEntity>> = projectRepository
        .getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taskCountByProject: StateFlow<Map<Long, Int>> = taskRepository
        .getIncompleteRootTasks()
        .map { tasks ->
            tasks
                .groupingBy { it.projectId }
                .eachCount()
                .mapNotNull { (id, count) -> id?.let { it to count } }
                .toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val zone = ZoneId.systemDefault()

    private val _currentDate = MutableStateFlow(LocalDate.now())
    val currentDate: StateFlow<LocalDate> = _currentDate

    val currentSort: StateFlow<String> =
        sortPreferences
            .observeSortMode(SortPreferences.ScreenKeys.TIMELINE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortPreferences.SortModes.DEFAULT)

    fun onChangeSort(sortMode: String) {
        viewModelScope.launch {
            sortPreferences.setSortMode(SortPreferences.ScreenKeys.TIMELINE, sortMode)
        }
    }

    private val dayTasks = _currentDate.flatMapLatest { date ->
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        taskRepository.getTasksDueOnDate(start, end).map { tasks ->
            tasks.filter { it.parentTaskId == null && it.archivedAt == null && !it.isCompleted }
        }
    }

    val scheduledBlocks: StateFlow<List<TimeBlock>> = dayTasks
        .map { tasks ->
            tasks
                .filter { it.scheduledStartTime != null }
                .mapNotNull { task ->
                    val start = task.scheduledStartTime ?: return@mapNotNull null
                    val duration = (task.estimatedDuration ?: 30) * 60 * 1000L
                    TimeBlock(
                        id = "task_${task.id}",
                        title = task.title,
                        startTime = start,
                        endTime = start + duration,
                        taskId = task.id,
                        priority = task.priority,
                        color = ""
                    )
                }.sortedBy { it.startTime }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unscheduledTasks: StateFlow<List<TaskEntity>> = combine(dayTasks, currentSort) { tasks, sort ->
        val unscheduled = tasks.filter { it.scheduledStartTime == null }
        when (sort) {
            SortPreferences.SortModes.DUE_DATE -> unscheduled.sortedWith(
                compareBy<TaskEntity> { it.dueDate == null }.thenBy { it.dueDate }.thenByDescending { it.priority }
            )
            SortPreferences.SortModes.PRIORITY -> unscheduled.sortedByDescending { it.priority }
            SortPreferences.SortModes.ALPHABETICAL -> unscheduled.sortedBy { it.title.lowercase() }
            SortPreferences.SortModes.DATE_CREATED -> unscheduled.sortedByDescending { it.createdAt }
            else -> unscheduled
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onNavigateDate(date: LocalDate) {
        _currentDate.value = date
    }

    fun onPreviousDay() {
        _currentDate.value = _currentDate.value.minusDays(1)
    }

    fun onNextDay() {
        _currentDate.value = _currentDate.value.plusDays(1)
    }

    fun onGoToToday() {
        _currentDate.value = LocalDate.now()
    }

    fun onScheduleTask(taskId: Long, startTimeMillis: Long) {
        viewModelScope.launch {
            val task = taskRepository.getTaskByIdOnce(taskId) ?: return@launch
            taskRepository.updateTask(task.copy(scheduledStartTime = startTimeMillis))
        }
    }

    fun onUnscheduleTask(taskId: Long) {
        viewModelScope.launch {
            val task = taskRepository.getTaskByIdOnce(taskId) ?: return@launch
            taskRepository.updateTask(task.copy(scheduledStartTime = null))
        }
    }

    /**
     * Helper for the quick-reschedule popup, which needs the full [TaskEntity]
     * (for the `hasDueDate` flag) but the Timeline screen only has a taskId
     * inside its scheduled blocks. Runs in [viewModelScope] so the caller
     * receives the result on the main dispatcher.
     */
    fun loadTaskForPopup(taskId: Long, onLoaded: (TaskEntity?) -> Unit) {
        viewModelScope.launch {
            onLoaded(taskRepository.getTaskByIdOnce(taskId))
        }
    }

    fun onRescheduleTask(taskId: Long, newDueDate: Long?) {
        viewModelScope.launch {
            try {
                val previous = taskRepository.getTaskByIdOnce(taskId)?.dueDate
                taskRepository.rescheduleTask(taskId, newDueDate)
                val sod = taskBehaviorPreferences.getStartOfDay().first()
                val label = QuickRescheduleFormatter.describe(
                    newDueDate,
                    sodHour = sod.hour,
                    sodMinute = sod.minute
                )
                val result = snackbarHostState.showSnackbar(
                    message = "Rescheduled to $label",
                    actionLabel = "UNDO",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    taskRepository.rescheduleTask(taskId, previous)
                }
            } catch (e: Exception) {
                Log.e("TimelineVM", "Failed to reschedule", e)
            }
        }
    }

    fun onPlanTaskForToday(taskId: Long) {
        viewModelScope.launch {
            try {
                taskRepository.planTaskForToday(taskId)
                snackbarHostState.showSnackbar("Planned for today", duration = SnackbarDuration.Short)
            } catch (e: Exception) {
                Log.e("TimelineVM", "Failed to plan for today", e)
            }
        }
    }

    fun onMoveToProject(
        taskId: Long,
        newProjectId: Long?,
        cascadeSubtasks: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                val task = taskRepository.getTaskByIdOnce(taskId) ?: return@launch
                val previousParentProjectId = task.projectId
                val previousSubtaskProjects: Map<Long, Long?> = if (cascadeSubtasks) {
                    taskRepository.getSubtasks(taskId).first().associate { it.id to it.projectId }
                } else {
                    emptyMap()
                }

                taskRepository.moveToProject(taskId, newProjectId, cascadeSubtasks)
                val projectName = newProjectId?.let { id ->
                    projects.value.find { it.id == id }?.name
                } ?: "No Project"
                val result = snackbarHostState.showSnackbar(
                    message = "Moved '${task.title}' to $projectName",
                    actionLabel = "UNDO",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    taskRepository.moveToProject(taskId, previousParentProjectId, false)
                    previousSubtaskProjects.entries
                        .groupBy { it.value }
                        .forEach { (origProjectId, entries) ->
                            taskRepository.batchMoveToProject(entries.map { it.key }, origProjectId)
                        }
                }
            } catch (e: Exception) {
                Log.e("TimelineVM", "Failed to move task to project", e)
            }
        }
    }

    fun onCreateProjectAndMoveTask(
        taskId: Long,
        name: String,
        cascadeSubtasks: Boolean = false
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val newId = projectRepository.addProject(name.trim())
                onMoveToProject(taskId, newId, cascadeSubtasks)
            } catch (e: Exception) {
                Log.e("TimelineVM", "Failed to create project", e)
            }
        }
    }

    fun onDeleteTaskWithUndo(taskId: Long) {
        viewModelScope.launch {
            try {
                val savedTask = taskRepository.getTaskByIdOnce(taskId) ?: return@launch
                taskRepository.deleteTask(taskId)
                val result = snackbarHostState.showSnackbar(
                    message = "Task Deleted",
                    actionLabel = "UNDO",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    taskRepository.insertTask(savedTask)
                }
            } catch (e: Exception) {
                Log.e("TimelineVM", "Failed to delete task", e)
            }
        }
    }

    fun onDuplicateTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val newId = taskRepository.duplicateTask(taskId, includeSubtasks = false)
                if (newId <= 0L) {
                    snackbarHostState.showSnackbar("Couldn't duplicate task")
                    return@launch
                }
                snackbarHostState.showSnackbar(
                    message = "Task Duplicated",
                    duration = SnackbarDuration.Short
                )
            } catch (e: Exception) {
                Log.e("TimelineVM", "Failed to duplicate task", e)
            }
        }
    }

    suspend fun getSubtaskCount(taskId: Long): Int =
        taskRepository.getSubtasks(taskId).first().size

    // Time blocking methods

    fun showAutoScheduleSheet() {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_TIME_BLOCK)) {
            _showUpgradePrompt.value = true
            return
        }
        _showTimeBlockSheet.value = true
    }

    fun dismissTimeBlockSheet() {
        _showTimeBlockSheet.value = false
    }

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    fun updateTimeBlockConfig(config: TimeBlockConfig) {
        _timeBlockConfig.value = config
    }

    // v1.4.40 entry point — opens the horizon picker rather than the legacy
    // config sheet. Gated on Pro identically to [showAutoScheduleSheet].
    fun showAutoBlockMyDaySheet() {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_TIME_BLOCK)) {
            _showUpgradePrompt.value = true
            return
        }
        _showHorizonSheet.value = true
    }

    fun dismissHorizonSheet() {
        _showHorizonSheet.value = false
    }

    fun selectHorizon(horizon: TimeBlockHorizon) {
        _selectedHorizon.value = horizon
    }

    fun dismissPreviewSheet() {
        _showPreviewSheet.value = false
    }

    /**
     * Horizon-aware Auto-Block flow. Sends the current horizon, per-task
     * signals, and pre-existing blocks to Haiku, then surfaces the result
     * in the preview sheet for the user to approve or cancel.
     */
    fun runAutoBlockMyDay() {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_TIME_BLOCK)) {
            _showUpgradePrompt.value = true
            return
        }
        viewModelScope.launch {
            _scheduleUiState.value = AiScheduleUiState.Loading
            _scheduleError.value = null
            _showHorizonSheet.value = false
            try {
                val cfg = _timeBlockConfig.value
                val horizon = _selectedHorizon.value
                val anchor = _currentDate.value
                val response = aiTimeBlockUseCase(
                    anchorDate = anchor,
                    horizon = horizon,
                    dayStart = cfg.dayStart,
                    dayEnd = cfg.dayEnd,
                    blockSizeMinutes = cfg.blockSizeMinutes,
                    includeBreaks = cfg.includeBreaks,
                    breakFrequencyMinutes = cfg.breakFrequencyMinutes,
                    breakDurationMinutes = cfg.breakDurationMinutes
                )
                val anchorIso = anchor.format(DateTimeFormatter.ISO_LOCAL_DATE)
                // Resolve Firestore doc IDs to local Long task ids. Schedule
                // blocks whose task id can't be resolved (created on another
                // device, not yet synced down) get demoted into the
                // unscheduled list so the rest of the plan still renders.
                val resolvedBlocks = mutableListOf<AiScheduleBlock>()
                val resolvedUnscheduled = mutableListOf<Pair<Long?, String>>()
                for (block in response.schedule) {
                    val cloudId = block.taskId
                    val localId = cloudId?.let { taskRepository.getIdByCloudId(it) }
                    if (cloudId != null && localId == null) {
                        resolvedUnscheduled += null to block.title
                        continue
                    }
                    resolvedBlocks += AiScheduleBlock(
                        start = block.start,
                        end = block.end,
                        type = block.type,
                        taskId = localId,
                        title = block.title,
                        reason = block.reason,
                        date = block.date ?: anchorIso
                    )
                }
                for (u in response.unscheduledTasks) {
                    resolvedUnscheduled += taskRepository.getIdByCloudId(u.taskId) to u.title
                }
                val schedule = AiSchedule(
                    blocks = resolvedBlocks,
                    unscheduledTasks = resolvedUnscheduled,
                    stats = AiTimeBlockStats(
                        totalWorkMinutes = response.stats.totalWorkMinutes,
                        totalBreakMinutes = response.stats.totalBreakMinutes,
                        totalFreeMinutes = response.stats.totalFreeMinutes,
                        tasksScheduled = response.stats.tasksScheduled,
                        tasksDeferred = response.stats.tasksDeferred
                    ),
                    horizonDays = response.horizonDays
                )
                if (schedule.blocks.isEmpty() && schedule.unscheduledTasks.isEmpty()) {
                    _scheduleUiState.value = AiScheduleUiState.Empty(
                        "Nothing to schedule right now."
                    )
                    return@launch
                }
                _scheduleUiState.value = AiScheduleUiState.Success(schedule)
                // Mandatory preview — never auto-commit. User approves or
                // cancels via [commitProposedSchedule] / [cancelProposedSchedule].
                _showPreviewSheet.value = true
            } catch (e: Exception) {
                Log.w("TimelineVM", "Auto-block my day failed", e)
                val msg = classifyScheduleError(e)
                _scheduleUiState.value = AiScheduleUiState.Error(msg)
                _scheduleError.value = msg
            }
        }
    }

    fun generateTimeBlocks() {
        // v1.4.40: the legacy config-sheet flow now funnels through the
        // horizon-aware use case with a single-day horizon. Keeps the
        // older entry point functional without duplicating the request
        // path.
        _selectedHorizon.value = TimeBlockHorizon.TODAY
        _showTimeBlockSheet.value = false
        runAutoBlockMyDay()
    }

    fun commitProposedSchedule() {
        // Read from _scheduleUiState directly rather than the derived
        // [aiSchedule] StateFlow — the latter is WhileSubscribed, so it's
        // null whenever there's no active collector (incl. unit tests).
        val schedule = (_scheduleUiState.value as? AiScheduleUiState.Success)
            ?.schedule ?: return
        viewModelScope.launch {
            try {
                for (block in schedule.blocks) {
                    if (block.type != "task" || block.taskId == null) continue
                    val blockDate = try {
                        LocalDate.parse(block.date)
                    } catch (ex: Exception) {
                        Log.w("TimelineVM", "Skipping block with bad date: ${block.date}", ex)
                        continue
                    }
                    val dayStartMillis = blockDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val startMinutes = parseHourMinute(block.start) ?: continue
                    val endMinutes = parseHourMinute(block.end) ?: continue
                    val startMillis = dayStartMillis + startMinutes * 60 * 1000L
                    val durationMin = (endMinutes - startMinutes).coerceAtLeast(1)

                    val task = taskRepository.getTaskByIdOnce(block.taskId) ?: continue
                    taskRepository.updateTask(
                        task.copy(
                            scheduledStartTime = startMillis,
                            estimatedDuration = durationMin
                        )
                    )
                }
                _showPreviewSheet.value = false
                snackbarHostState.showSnackbar(
                    "Schedule applied!",
                    duration = SnackbarDuration.Short
                )
            } catch (e: Exception) {
                Log.w("TimelineVM", "Commit proposed schedule failed", e)
                _scheduleError.value = "Couldn't apply schedule"
            }
        }
    }

    fun cancelProposedSchedule() {
        _showPreviewSheet.value = false
        _scheduleUiState.value = AiScheduleUiState.Idle
    }

    // Retained for backward compat with the existing Success banner's Apply button.
    fun applyAiSchedule() = commitProposedSchedule()

    fun resetAiSchedule() {
        _scheduleUiState.value = AiScheduleUiState.Idle
        _scheduleError.value = null
        _showPreviewSheet.value = false
    }

    private fun parseHourMinute(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    /**
     * Map a failed AI call to a short user-facing message. The ViewModel
     * receives Retrofit's [retrofit2.HttpException] for 4xx/5xx; we
     * pattern-match on the status code so the UI can distinguish rate
     * limiting (429) from a generic backend failure.
     */
    private fun classifyScheduleError(e: Exception): String {
        val httpException = e as? retrofit2.HttpException
        return when (httpException?.code()) {
            429 -> "You've hit the AI scheduling limit. Try again in a bit."
            503 -> "AI service is temporarily unavailable. Try again shortly."
            500 -> "The AI returned an unexpected response. Try again."
            null -> "Couldn't reach the scheduling service. Check your connection."
            else -> e.message ?: "Couldn't generate schedule"
        }
    }

    fun clearScheduleError() {
        _scheduleError.value = null
        // Also clear a transient Error/Empty banner so it stops rendering.
        when (_scheduleUiState.value) {
            is AiScheduleUiState.Error, is AiScheduleUiState.Empty -> {
                _scheduleUiState.value = AiScheduleUiState.Idle
            }
            else -> Unit
        }
    }

    fun restorePurchases() {
        viewModelScope.launch {
            try {
                billingManager.restorePurchases()
                snackbarHostState.showSnackbar("Purchases restored")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Couldn't restore purchases")
            }
        }
    }
}
