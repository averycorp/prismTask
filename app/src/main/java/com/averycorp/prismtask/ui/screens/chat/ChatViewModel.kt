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
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatTaskContext
import com.averycorp.prismtask.data.repository.ChatMessage
import com.averycorp.prismtask.data.repository.ChatRepository
import com.averycorp.prismtask.data.repository.HabitRepository
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
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val habitRepository: HabitRepository,
    private val habitCompletionDao: HabitCompletionDao,
    private val proFeatureGate: ProFeatureGate,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier
    val messages: StateFlow<List<ChatMessage>> = chatRepository.messages

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _showDisclosure = MutableStateFlow(false)
    val showDisclosure: StateFlow<Boolean> = _showDisclosure.asStateFlow()

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
    }

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    fun dismissDisclosure() {
        _showDisclosure.value = false
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

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

    fun clearConversation() {
        chatRepository.clearConversation()
    }

    /**
     * Executes an action from a chat AI response (inline action button tap).
     */
    fun executeAction(action: ChatActionResponse) {
        viewModelScope.launch {
            try {
                when (action.type) {
                    "complete" -> {
                        val taskId = action.taskId?.toLongOrNull() ?: return@launch
                        taskRepository.completeTask(taskId)
                        _toastMessage.emit("Task Completed")
                    }

                    "reschedule" -> {
                        val taskId = action.taskId?.toLongOrNull() ?: return@launch
                        val newDate = resolveDate(action.to)
                        taskRepository.rescheduleTask(taskId, newDate)
                        _toastMessage.emit("Task Rescheduled")
                    }

                    "reschedule_batch" -> {
                        val ids = action.taskIds?.mapNotNull { it.toLongOrNull() } ?: return@launch
                        val newDate = resolveDate(action.to)
                        for (id in ids) {
                            taskRepository.rescheduleTask(id, newDate)
                        }
                        _toastMessage.emit("${ids.size} tasks rescheduled")
                    }

                    "breakdown" -> {
                        val taskId = action.taskId?.toLongOrNull() ?: return@launch
                        val subtasks = action.subtasks ?: return@launch
                        for (title in subtasks) {
                            taskRepository.addSubtask(title, taskId)
                        }
                        _toastMessage.emit("${subtasks.size} subtasks added")
                    }

                    "archive" -> {
                        val taskId = action.taskId?.toLongOrNull() ?: return@launch
                        taskRepository.archiveTask(taskId)
                        _toastMessage.emit("Task Archived")
                    }

                    "start_timer" -> {
                        // Timer launch is handled by the UI layer via navigation
                        _toastMessage.emit("Timer started")
                    }

                    "create_task" -> {
                        val title = action.title ?: return@launch
                        val dueDate = resolveDate(action.due)
                        val priority = resolvePriority(action.priority)
                        val now = System.currentTimeMillis()
                        val task = TaskEntity(
                            title = title,
                            dueDate = dueDate,
                            priority = priority,
                            createdAt = now,
                            updatedAt = now
                        )
                        taskRepository.insertTask(task)
                        _toastMessage.emit("Task Created: $title")
                    }
                }
            } catch (e: Exception) {
                _toastMessage.emit("Action failed")
            }
        }
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
