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
import com.averycorp.prismtask.data.remote.api.ChatTaskContext
import com.averycorp.prismtask.data.repository.ChatMessage
import com.averycorp.prismtask.data.repository.ChatRepository
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.toCalendarDayOfWeek
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

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
        // F8 chat privacy doc update bumped the flag from V1 to V2
        // so the expanded copy (enumerating task-context fields +
        // rolling history) is re-acknowledged once by every user.
        viewModelScope.launch {
            val alreadyShown = userPreferencesDataStore.aiChatDisclosureShownV2Flow.first()
            if (!alreadyShown) {
                _showDisclosure.value = true
            }
        }
    }

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    fun dismissDisclosure() {
        _showDisclosure.value = false
        viewModelScope.launch {
            userPreferencesDataStore.setAiChatDisclosureShownV2(true)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // D.3 (F8 follow-on): leading idempotency guard. Compose's
        // `enabled = !isTyping` on the send button blocks most double
        // taps, but there is a ~16ms window between the user tap and
        // the next recomposition where a second tap can land. Reading
        // the StateFlow value here is synchronous and races with
        // nothing — closes the gap cheaply.
        if (_isTyping.value) return

        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_CONVERSATIONAL)) {
            _showUpgradePrompt.value = true
            return
        }

        viewModelScope.launch {
            _isTyping.value = true
            _error.value = null
            try {
                chatRepository.sendMessage(
                    userMessage = text,
                    taskContextId = taskContextId,
                    taskContext = buildTaskContextSnapshot(_contextTask.value)
                )
            } catch (e: java.net.UnknownHostException) {
                _error.value = "I need an internet connection to chat. Your tasks are still available offline."
            } catch (e: java.net.ConnectException) {
                _error.value = "I need an internet connection to chat. Your tasks are still available offline."
            } catch (e: retrofit2.HttpException) {
                android.util.Log.e("ChatViewModel", "Chat HTTP ${e.code()} ${e.message()}", e)
                _error.value = when (e.code()) {
                    401 -> "Sign in to use chat — your session has expired."
                    403 -> "Chat requires Pro. Upgrade in Settings to continue."
                    429 -> "Daily chat limit reached. Try again later."
                    451 -> "AI features are disabled. Re-enable them in Settings → AI Features."
                    503 -> "Chat backend is unavailable. Try again in a moment."
                    else -> "Chat is unavailable right now (HTTP ${e.code()})."
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Chat send failed", e)
                _error.value = "Chat is unavailable right now: ${e.javaClass.simpleName} — ${e.message ?: "unknown error"}"
            } finally {
                _isTyping.value = false
            }
        }
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
