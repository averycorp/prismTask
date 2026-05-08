package com.averycorp.prismtask.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatStreamEvent
import com.averycorp.prismtask.data.remote.api.ChatTaskContext
import com.averycorp.prismtask.data.repository.ChatMessage
import com.averycorp.prismtask.data.repository.ChatRepository
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.toCalendarDayOfWeek
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val habitRepository: HabitRepository,
    private val habitCompletionDao: HabitCompletionDao,
    private val proFeatureGate: ProFeatureGate,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val userPreferencesDataStore: UserPreferencesDataStore
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier
    val messages: StateFlow<List<ChatMessage>> = chatRepository.messages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * F7 D.1 + F8 D.2: state machine for the in-flight chat turn.
     * - [Idle]: no turn in progress; send button visible, input enabled.
     * - [Streaming]: a turn is being received; the partial text is
     *   rendered as the assistant's pending bubble; input shows a Stop
     *   button (D.2 cancel affordance) instead of Send.
     */
    sealed interface ChatTurnState {
        data object Idle : ChatTurnState
        data class Streaming(
            val partialText: String,
            val startedAt: Long
        ) : ChatTurnState
    }

    private val _turnState = MutableStateFlow<ChatTurnState>(ChatTurnState.Idle)
    val turnState: StateFlow<ChatTurnState> = _turnState.asStateFlow()

    /**
     * Backward-compat shim: `isTyping` is true whenever a turn is
     * streaming. Existing tests and screens read this flow; keeping it
     * derived (rather than removed) avoids a regression risk.
     */
    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private var streamingJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt.asStateFlow()

    /**
     * Result of an [executeAction] call, surfaced to the screen as a
     * snackbar. When [undoLabel] is non-null the snackbar renders an
     * action button which calls [undoAction]. Phase 2 audit fix #4 (C.2):
     * destructive ops (complete / reschedule / reschedule_batch / archive)
     * carry an undo callback; non-destructive ops (start_timer,
     * create_task) leave [undoAction] null.
     */
    data class ChatActionResult(
        val message: String,
        val undoLabel: String? = null,
        val undoAction: (suspend () -> Unit)? = null
    )

    private val _actionResults = MutableSharedFlow<ChatActionResult>(
        extraBufferCapacity = 4
    )
    val actionResults: SharedFlow<ChatActionResult> = _actionResults.asSharedFlow()

    /**
     * Idempotency guard for chat action chips. A signature like
     * "complete:42" or "reschedule_batch:1,2,3" is added on tap, removed
     * on completion. Phase 2 audit fix #3 (B.2): a fast double-tap on the
     * same chip used to issue duplicate Room mutations; now the second
     * tap silently no-ops while the first is still in flight.
     */
    private val _actionsInFlight = MutableStateFlow<Set<String>>(emptySet())
    val actionsInFlight: StateFlow<Set<String>> = _actionsInFlight.asStateFlow()

    private val _showDisclosure = MutableStateFlow(false)
    val showDisclosure: StateFlow<Boolean> = _showDisclosure.asStateFlow()

    /**
     * Asks the screen to confirm a destructive bulk action (currently
     * only Clear Chat). Phase 2 audit fix C.3: the DeleteSweep button
     * used to drop the conversation on a single tap with no undo path,
     * making accidental clears unrecoverable.
     */
    private val _showClearConfirm = MutableStateFlow(false)
    val showClearConfirm: StateFlow<Boolean> = _showClearConfirm.asStateFlow()

    /**
     * One-shot navigation requests emitted by chat actions that need to
     * leave the screen (B.4: start_timer opens the Timer screen). Kept
     * separate from [ChatActionResult] because nav events bypass the
     * snackbar plumbing — the screen layer collects this flow and calls
     * navController.navigate directly.
     */
    sealed interface ChatNavEvent {
        data class OpenTimer(val minutes: Int?) : ChatNavEvent
        /**
         * Hand the user's natural-language batch phrasing off to
         * BatchPreviewScreen. The destination screen calls
         * `/api/v1/ai/batch-parse` to resolve [commandText] into a
         * previewable mutation plan with full undo + history support.
         */
        data class OpenBatchPreview(val commandText: String) : ChatNavEvent
    }

    private val _navigationEvents = MutableSharedFlow<ChatNavEvent>(
        extraBufferCapacity = 1
    )
    val navigationEvents: SharedFlow<ChatNavEvent> = _navigationEvents.asSharedFlow()

    /** Task ID if chat was opened from a specific task. */
    val taskContextId: Long? = savedStateHandle.get<Long>("taskId")?.takeIf { it > 0 }

    private val _contextTask = MutableStateFlow<TaskEntity?>(null)
    val contextTask: StateFlow<TaskEntity?> = _contextTask.asStateFlow()

    init {
        if (taskContextId != null) {
            viewModelScope.launch {
                _contextTask.value = taskDao.getTaskByIdOnce(taskContextId)
            }
        }
        // First-run AI chat disclosure (CHAT_QUALITY_AUDIT C.1).
        // Show the dialog the first time chat opens; once dismissed,
        // the persisted flag prevents it from appearing again.
        //
        // V1 -> V2 (F8 chat privacy doc update) expanded the copy.
        // V2 -> V3 (D11 E.3) flags the retention change: chat content
        // is now persisted on backend Postgres + local Room until the
        // user deletes it, vs the prior "stateless per-turn" behaviour.
        viewModelScope.launch {
            val alreadyShown = userPreferencesDataStore.aiChatDisclosureShownV3Flow.first()
            if (!alreadyShown) {
                _showDisclosure.value = true
            }
        }
        // Reconcile any history written from another device. Errors are
        // swallowed — a failed pull just leaves the local Room cache as
        // the source-of-truth for this session; next pull retries.
        viewModelScope.launch {
            runCatching { chatRepository.pullHistory() }
        }
    }

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    fun dismissDisclosure() {
        _showDisclosure.value = false
        viewModelScope.launch {
            // V3 supersedes V2; setting V3 also implicitly satisfies the
            // V2 gate because V3 copy is a superset of V2 (it covers the
            // same task-context-fields + rolling history claims plus
            // the retention change).
            userPreferencesDataStore.setAiChatDisclosureShownV2(true)
            userPreferencesDataStore.setAiChatDisclosureShownV3(true)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // D.3 (F8 follow-on): leading idempotency guard. Compose's
        // `enabled` on the send button blocks most double taps, but
        // there is a ~16ms window between the user tap and the next
        // recomposition where a second tap can land. Reading the
        // StateFlow value here is synchronous and races with nothing.
        // Keyed off turnState now (not just _isTyping) so the same
        // guard covers streaming turns too.
        if (_turnState.value !is ChatTurnState.Idle) return

        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_CONVERSATIONAL)) {
            _showUpgradePrompt.value = true
            return
        }

        // Flip turnState synchronously BEFORE launching so a second
        // sendMessage call landing in the same dispatch tick sees the
        // non-Idle state at the guard above. Mirrors the synchronous
        // _isTyping flip from #1182.
        _turnState.value = ChatTurnState.Streaming(
            partialText = "",
            startedAt = System.currentTimeMillis()
        )
        _isTyping.value = true
        _error.value = null

        val contextSnapshotJob = viewModelScope.launch {
            val snapshot = buildTaskContextSnapshot(_contextTask.value)
            startStreamingTurn(text, snapshot)
        }
        // Track the snapshot-build job too so cancelInFlight works
        // even if the user taps Stop before the task-context snapshot
        // resolves.
        streamingJob = contextSnapshotJob
    }

    private fun startStreamingTurn(text: String, snapshot: ChatTaskContext?) {
        var sawDoneOrError = false
        val flowJob = chatRepository
            .streamMessage(
                userMessage = text,
                taskContextId = taskContextId,
                taskContext = snapshot
            )
            .onEach { event ->
                when (event) {
                    is ChatStreamEvent.Token -> {
                        val current = _turnState.value as? ChatTurnState.Streaming
                            ?: return@onEach
                        _turnState.value = current.copy(
                            partialText = current.partialText + event.text
                        )
                    }
                    is ChatStreamEvent.Done -> {
                        sawDoneOrError = true
                        chatRepository.commitAssistantTurn(
                            text = event.message,
                            actions = event.actions
                        )
                        finishTurn()
                    }
                    is ChatStreamEvent.Error -> {
                        sawDoneOrError = true
                        _error.value = event.message
                        finishTurn()
                    }
                }
            }
            .launchIn(viewModelScope)

        streamingJob = flowJob
        flowJob.invokeOnCompletion {
            // If we never saw a `Done` or `Error`, this is a cooperative
            // cancel (D.2 — user tapped Stop). cancelInFlight() owns the
            // commit-as-partial path; here we just guarantee turnState
            // returns to Idle so the UI re-enables.
            if (!sawDoneOrError && _turnState.value !is ChatTurnState.Idle) {
                finishTurn()
            }
        }
    }

    private fun finishTurn() {
        _turnState.value = ChatTurnState.Idle
        _isTyping.value = false
        streamingJob = null
    }

    /**
     * F8 D.2 — cancel the in-flight streaming turn. Commits whatever
     * partial text was collected so far (with a "(cancelled)" suffix
     * so the boundary is explicit) into the message list, then
     * transitions back to Idle. No-op when no turn is streaming.
     */
    fun cancelInFlight() {
        val current = _turnState.value as? ChatTurnState.Streaming ?: return
        val job = streamingJob
        // Take the snapshot BEFORE cancelling so we still have the
        // partial text — _turnState is mutated on the same dispatcher
        // but the job cancel propagates asynchronously.
        val partial = current.partialText
        job?.cancel()

        val committed = if (partial.isBlank()) {
            "(cancelled)"
        } else {
            "$partial (cancelled)"
        }
        chatRepository.commitAssistantTurn(
            text = committed,
            actions = emptyList()
        )
        finishTurn()
    }

    fun clearError() {
        _error.value = null
    }

    /** Show the C.3 confirm dialog before actually clearing chat. */
    fun requestClearConversation() {
        _showClearConfirm.value = true
    }

    /** User cancelled the C.3 confirm dialog; do nothing. */
    fun dismissClearConfirm() {
        _showClearConfirm.value = false
    }

    /**
     * Drop the conversation. Wired both directly (tests) and via
     * [requestClearConversation] → confirm dialog → here (UI).
     */
    fun clearConversation() {
        _showClearConfirm.value = false
        chatRepository.clearConversation()
    }

    /**
     * Executes an action from a chat AI response (inline action button tap).
     *
     * Idempotency: a duplicate tap on the same chip while the first is
     * still in flight is silently dropped. The signature is removed in a
     * `finally` block so a chip becomes tappable again after success or
     * failure (see Phase 2 audit fix #3 / B.2).
     *
     * Undo: destructive ops emit a [ChatActionResult] with an [undoAction]
     * that reverses the mutation. The screen layer renders this as a
     * snackbar with an action button (see Phase 2 audit fix #4 / C.2).
     */
    fun executeAction(action: ChatActionResponse) {
        val signature = actionSignature(action) ?: return
        if (_actionsInFlight.value.contains(signature)) {
            return
        }
        _actionsInFlight.value = _actionsInFlight.value + signature

        viewModelScope.launch {
            try {
                val result = when (action.type) {
                    "complete" -> handleComplete(action)
                    "reschedule" -> handleReschedule(action)
                    "reschedule_batch" -> handleRescheduleBatch(action)
                    "breakdown" -> handleBreakdown(action)
                    "archive" -> handleArchive(action)
                    "start_timer" -> handleStartTimer(action)
                    "create_task" -> handleCreateTask(action)
                    "batch_command" -> handleBatchCommand(action)
                    else -> null
                }
                if (result != null) {
                    _actionResults.emit(result)
                }
            } catch (e: Exception) {
                _actionResults.emit(ChatActionResult("Action failed"))
            } finally {
                _actionsInFlight.value = _actionsInFlight.value - signature
            }
        }
    }

    private fun actionSignature(action: ChatActionResponse): String? {
        return when (action.type) {
            "reschedule_batch" -> {
                val ids = action.taskIds?.takeIf { it.isNotEmpty() } ?: return null
                "reschedule_batch:${ids.sorted().joinToString(",")}"
            }
            "create_task" -> "create_task:${action.title.orEmpty()}:${action.due.orEmpty()}"
            "batch_command" -> {
                val cmd = action.commandText?.takeIf { it.isNotBlank() } ?: return null
                "batch_command:${cmd.trim().lowercase()}"
            }
            "breakdown" -> {
                val taskId = action.taskId ?: return null
                "breakdown:$taskId"
            }
            else -> {
                val taskId = action.taskId
                if (action.type in DESTRUCTIVE_TYPES_NEED_TASK_ID && taskId == null) return null
                "${action.type}:${taskId.orEmpty()}"
            }
        }
    }

    private suspend fun handleComplete(action: ChatActionResponse): ChatActionResult? {
        val taskId = action.taskId?.toLongOrNull() ?: return null
        taskRepository.completeTask(taskId)
        return ChatActionResult(
            message = "Task Completed",
            undoLabel = "Undo",
            undoAction = { taskRepository.uncompleteTask(taskId) }
        )
    }

    private suspend fun handleReschedule(action: ChatActionResponse): ChatActionResult? {
        val taskId = action.taskId?.toLongOrNull() ?: return null
        val originalDueDate = taskDao.getTaskByIdOnce(taskId)?.dueDate
        val newDate = resolveDate(action.to)
        taskRepository.rescheduleTask(taskId, newDate)
        return ChatActionResult(
            message = "Task Rescheduled",
            undoLabel = "Undo",
            undoAction = { taskRepository.rescheduleTask(taskId, originalDueDate) }
        )
    }

    private suspend fun handleRescheduleBatch(action: ChatActionResponse): ChatActionResult? {
        val ids = action.taskIds?.mapNotNull { it.toLongOrNull() }?.takeIf { it.isNotEmpty() }
            ?: return null
        val newDate = resolveDate(action.to)

        // Snapshot original due dates BEFORE mutation so undo can restore
        // each task's prior schedule even if some succeed and some fail.
        val originalDueDates = ids.associateWith { id ->
            taskDao.getTaskByIdOnce(id)?.dueDate
        }

        var succeeded = 0
        var failed = 0
        for (id in ids) {
            try {
                taskRepository.rescheduleTask(id, newDate)
                succeeded++
            } catch (_: Exception) {
                failed++
            }
        }

        val message = when {
            failed == 0 -> "$succeeded Tasks Rescheduled"
            succeeded == 0 -> "Reschedule Failed"
            else -> "Rescheduled $succeeded of ${ids.size} Tasks ($failed Failed)"
        }
        // Only offer undo when at least one task moved; nothing to undo
        // when every reschedule failed.
        return if (succeeded > 0) {
            ChatActionResult(
                message = message,
                undoLabel = "Undo",
                undoAction = {
                    for (id in ids) {
                        runCatching {
                            taskRepository.rescheduleTask(id, originalDueDates[id])
                        }
                    }
                }
            )
        } else {
            ChatActionResult(message = message)
        }
    }

    private suspend fun handleBreakdown(action: ChatActionResponse): ChatActionResult? {
        val taskId = action.taskId?.toLongOrNull() ?: return null
        val subtasks = action.subtasks?.takeIf { it.isNotEmpty() } ?: return null
        for (title in subtasks) {
            taskRepository.addSubtask(title, taskId)
        }
        return ChatActionResult("${subtasks.size} Subtasks Added")
    }

    private suspend fun handleArchive(action: ChatActionResponse): ChatActionResult? {
        val taskId = action.taskId?.toLongOrNull() ?: return null
        taskRepository.archiveTask(taskId)
        return ChatActionResult(
            message = "Task Archived",
            undoLabel = "Undo",
            undoAction = { taskRepository.unarchiveTask(taskId) }
        )
    }

    /**
     * B.4 (F8 follow-on): emit a navigation request so the screen layer
     * can route to the Timer screen. We deliberately do NOT pass
     * [ChatActionResponse.minutes] through the nav route — the timer
     * screen reads its duration from user preferences and we don't want
     * an AI-suggested duration to silently override the user's
     * configured length. The minutes value is surfaced in the snackbar
     * so the user knows what AI suggested.
     */
    /**
     * Hand the user's natural-language batch phrasing off to
     * BatchPreviewScreen via [ChatNavEvent.OpenBatchPreview]. The
     * destination screen calls `/api/v1/ai/batch-parse` to resolve the
     * phrasing into a previewable mutation plan with full undo +
     * history support, reusing the QuickAdd batch pipeline.
     *
     * No snackbar — the user lands on the preview screen and decides
     * there. Returning null keeps the result flow silent.
     */
    private suspend fun handleBatchCommand(action: ChatActionResponse): ChatActionResult? {
        val commandText = action.commandText?.trim()?.takeIf { it.isNotBlank() } ?: return null
        _navigationEvents.emit(ChatNavEvent.OpenBatchPreview(commandText = commandText))
        return null
    }

    private suspend fun handleStartTimer(action: ChatActionResponse): ChatActionResult {
        _navigationEvents.emit(ChatNavEvent.OpenTimer(minutes = action.minutes))
        val message = action.minutes
            ?.takeIf { it in 1..480 }
            ?.let { "Starting Timer ($it min)" }
            ?: "Timer Started"
        return ChatActionResult(message)
    }

    /**
     * B.3 (F8 follow-on): plumb the rich create_task fields. Switches
     * from [TaskRepository.insertTask] to [TaskRepository.addTask] for
     * behavior parity with QuickAdd / AddEditTask save-new — the latter
     * accepts description / projectId and runs the same emit / classify
     * / widget-update sequence. Tags are find-or-created by name and
     * applied post-insert through [TagRepository.addTagToTask].
     * Project name resolves to id via [ProjectDao.getProjectByNameOnce];
     * a name the user invented (no matching project) is silently dropped
     * — we never auto-create projects from chat.
     */
    private suspend fun handleCreateTask(action: ChatActionResponse): ChatActionResult? {
        val title = action.title?.takeIf { it.isNotBlank() } ?: return null
        val dueDate = resolveDate(action.due)
        val priority = resolvePriority(action.priority)
        val description = action.description?.takeIf { it.isNotBlank() }
        val projectId = action.project
            ?.takeIf { it.isNotBlank() }
            ?.let { projectDao.getProjectByNameOnce(it.trim())?.id }

        val taskId = taskRepository.addTask(
            title = title,
            description = description,
            dueDate = dueDate,
            priority = priority,
            projectId = projectId
        )

        action.tags
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            ?.distinctBy { it.lowercase() }
            ?.take(MAX_CHAT_CREATE_TASK_TAGS)
            ?.forEach { tagName ->
                val existing = tagRepository.getTagByNameOnce(tagName)
                val tagId = existing?.id ?: tagRepository.addTag(tagName)
                tagRepository.addTagToTask(taskId, tagId)
            }

        return ChatActionResult("Task Created: $title")
    }

    private companion object {
        /**
         * Action types whose [ChatActionResponse.taskId] must be present
         * for the chip tap to be acted on. Other types either carry
         * [ChatActionResponse.taskIds] (batch) or no id at all
         * (create_task, start_timer).
         */
        val DESTRUCTIVE_TYPES_NEED_TASK_ID = setOf(
            "complete",
            "reschedule",
            "archive"
        )

        /**
         * Cap applied client-side; the backend schema also caps at 10.
         * Defends against an AI response that hallucinates a long tag
         * list and exhausts insertions.
         */
        const val MAX_CHAT_CREATE_TASK_TAGS = 10
    }

    /**
     * Builds the [ChatTaskContext] snapshot the backend forwards to Anthropic.
     * Resolves the project name when [TaskEntity.projectId] is set so the AI
     * can refer to the task's project rather than an opaque integer id.
     */
    private suspend fun buildTaskContextSnapshot(task: TaskEntity?): ChatTaskContext? {
        if (task == null) return null
        val dueIso = task.dueDate?.let { millis ->
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }
        val projectName = task.projectId?.let { pid ->
            projectDao.getProjectByIdOnce(pid)?.name
        }
        return ChatTaskContext(
            title = task.title,
            description = task.description?.takeIf { it.isNotBlank() },
            dueDate = dueIso,
            priority = task.priority.takeIf { it > 0 },
            projectName = projectName,
            isCompleted = task.isCompleted
        )
    }

    private suspend fun resolveDate(dateStr: String?): Long? {
        if (dateStr == null) return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 0)

        return when (dateStr) {
            "today" -> cal.timeInMillis
            "tomorrow" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            "next_week" -> {
                val calDow = taskBehaviorPreferences.getFirstDayOfWeek().first().toCalendarDayOfWeek()
                cal.firstDayOfWeek = calDow
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                cal.set(Calendar.DAY_OF_WEEK, calDow)
                cal.timeInMillis
            }
            else -> {
                // Try to parse YYYY-MM-DD
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    sdf.parse(dateStr)?.time
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun resolvePriority(priorityStr: String?): Int = when (priorityStr) {
        "low" -> 1
        "medium" -> 2
        "high" -> 3
        "urgent" -> 4
        else -> 0
    }
}
