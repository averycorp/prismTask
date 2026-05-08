package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatHistoryEntry
import com.averycorp.prismtask.data.remote.api.ChatRequest
import com.averycorp.prismtask.data.remote.api.ChatResponse
import com.averycorp.prismtask.data.remote.api.ChatStreamEvent
import com.averycorp.prismtask.data.remote.api.ChatTaskContext
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.sse.ChatStreamClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val actions: List<ChatActionResponse> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT }
}

@Singleton
class ChatRepository
@Inject
constructor(
    private val api: PrismTaskApi,
    private val streamClient: ChatStreamClient
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var conversationId: String = generateConversationId()
    private var conversationDate: LocalDate = LocalDate.now()

    /** Maximum conversation pairs kept and forwarded to the backend (spec: 6). */
    private val maxHistoryPairs = 6

    /**
     * Returns the current conversation ID, resetting if the day has changed.
     */
    fun getConversationId(): String {
        resetIfNewDay()
        return conversationId
    }

    /**
     * Sends a message to the AI chat backend (single-shot, non-streaming)
     * and returns the response. Retained as a fallback path; the primary
     * UI path is [streamMessage] (F7 D.1).
     */
    suspend fun sendMessage(
        userMessage: String,
        taskContextId: Long? = null,
        taskContext: ChatTaskContext? = null
    ): ChatResponse {
        resetIfNewDay()

        val historyPayload = snapshotHistoryPayload()

        val userMsg = ChatMessage(
            role = ChatMessage.Role.USER,
            text = userMessage
        )
        _messages.value = _messages.value + userMsg

        val response = api.aiChat(
            ChatRequest(
                message = userMessage,
                conversationId = conversationId,
                taskContextId = taskContextId,
                taskContext = taskContext,
                history = historyPayload
            )
        )

        val assistantMsg = ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            text = response.message,
            actions = response.actions
        )
        _messages.value = _messages.value + assistantMsg

        trimHistory()

        return response
    }

    /**
     * F7 D.1 streaming variant. Appends the user's turn to [messages]
     * synchronously so the user bubble renders immediately, then returns
     * a [Flow] of [ChatStreamEvent]s the caller (ChatViewModel) consumes
     * to render incremental tokens. The caller is responsible for
     * calling [commitAssistantTurn] on a `Done` (or to commit a partial
     * on user-cancel) so the message list stays single-source-of-truth.
     */
    fun streamMessage(
        userMessage: String,
        taskContextId: Long? = null,
        taskContext: ChatTaskContext? = null
    ): Flow<ChatStreamEvent> {
        resetIfNewDay()

        val historyPayload = snapshotHistoryPayload()

        _messages.value = _messages.value + ChatMessage(
            role = ChatMessage.Role.USER,
            text = userMessage
        )

        return streamClient.stream(
            ChatRequest(
                message = userMessage,
                conversationId = conversationId,
                taskContextId = taskContextId,
                taskContext = taskContext,
                history = historyPayload
            )
        )
    }

    /**
     * Append the assistant's turn to the message list once a streaming
     * turn has resolved (either `Done` or user-cancel commit-as-partial).
     * Trims rolling history per spec.
     */
    fun commitAssistantTurn(
        text: String,
        actions: List<ChatActionResponse>
    ) {
        _messages.value = _messages.value + ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            text = text,
            actions = actions
        )
        trimHistory()
    }

    fun clearConversation() {
        _messages.value = emptyList()
        conversationId = generateConversationId()
        conversationDate = LocalDate.now()
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now()
        if (today != conversationDate) {
            clearConversation()
        }
    }

    private fun snapshotHistoryPayload(): List<ChatHistoryEntry> =
        _messages.value
            .takeLast(maxHistoryPairs * 2)
            .map {
                ChatHistoryEntry(
                    role = if (it.role == ChatMessage.Role.USER) "user" else "assistant",
                    content = it.text
                )
            }

    /**
     * Trims conversation history to keep at most [maxHistoryPairs] user+assistant pairs.
     * Oldest pairs are dropped silently per spec.
     */
    private fun trimHistory() {
        val current = _messages.value
        if (current.size > maxHistoryPairs * 2) {
            _messages.value = current.takeLast(maxHistoryPairs * 2)
        }
    }

    private fun generateConversationId(): String {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "chat_${date}_${UUID.randomUUID().toString().take(8)}"
    }
}
