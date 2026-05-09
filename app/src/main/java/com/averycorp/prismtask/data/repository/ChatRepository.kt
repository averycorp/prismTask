package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.ChatMessageDao
import com.averycorp.prismtask.data.local.entity.ChatMessageEntity
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatHistoryEntry
import com.averycorp.prismtask.data.remote.api.ChatMessageRecord
import com.averycorp.prismtask.data.remote.api.ChatRequest
import com.averycorp.prismtask.data.remote.api.ChatResponse
import com.averycorp.prismtask.data.remote.api.ChatStreamEvent
import com.averycorp.prismtask.data.remote.api.ChatTaskContext
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.sse.ChatStreamClient
import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.time.Instant
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

/**
 * D11 E.3 — DAO-backed chat repository. Postgres is source of truth
 * (see `docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md`); this layer
 * mirrors persisted turns into Room and exposes the active
 * conversation as a Flow off the DAO.
 *
 * Sync direction: server writes both turns inside the `/chat` handler;
 * the client mirrors locally on response. Cross-device reconciliation
 * runs through [pullHistory], called by `ChatViewModel` on init.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ChatRepository
@Inject
constructor(
    private val api: PrismTaskApi,
    private val chatMessageDao: ChatMessageDao,
    private val streamClient: ChatStreamClient
) {
    /** Maximum conversation pairs forwarded to the backend (spec: 6). */
    private val maxHistoryPairs = 6

    private val gson = Gson()

    private val _conversationId = MutableStateFlow(generateConversationId())
    val conversationId: StateFlow<String> = _conversationId.asStateFlow()

    private var conversationDate: LocalDate = LocalDate.now()

    /**
     * Active conversation rendered to the chat surface. Filtered to
     * the current `conversationId`; older conversations remain in Room
     * but are not surfaced. UI re-subscribes automatically when the
     * conversationId Flow flips on day rollover.
     */
    val messages: Flow<List<ChatMessage>> =
        _conversationId.flatMapLatest { id ->
            chatMessageDao.observeForConversation(id)
                .map { rows -> rows.map { it.toChatMessage() } }
        }

    /**
     * Returns the current conversation ID, resetting if the day has changed.
     * The returned ID is also pushed to [_conversationId] so any active
     * Flow subscriber re-subscribes to the new conversation.
     */
    fun getConversationId(): String {
        resetIfNewDay()
        return _conversationId.value
    }

    /**
     * Sends a message to the AI chat backend. The backend persists both
     * turns inside the handler and returns the server-assigned PKs on the
     * response; we mirror into Room using THOSE PKs so a subsequent
     * `pullHistory()`'s REPLACE-on-PK upsert is idempotent (D12 Gate (b)).
     *
     * Older backends / persistence-failure responses may omit the IDs,
     * in which case we fall back to fresh client-side UUIDs — the row
     * still renders correctly, it just won't dedup on the next pull.
     */
    suspend fun sendMessage(
        userMessage: String,
        taskContextId: Long? = null,
        taskContext: ChatTaskContext? = null
    ): ChatResponse {
        resetIfNewDay()
        val convId = _conversationId.value

        // Snapshot history BEFORE the new turn — backend wants the rolling
        // window of prior turns, not including the message we're about to
        // send.
        val priorRows = chatMessageDao.getForConversation(convId)
            .takeLast(maxHistoryPairs * 2)
        val historyPayload = priorRows.map {
            ChatHistoryEntry(role = it.role, content = it.content)
        }

        val response = api.aiChat(
            ChatRequest(
                message = userMessage,
                conversationId = convId,
                taskContextId = taskContextId,
                taskContext = taskContext,
                history = historyPayload
            )
        )

        // D12 Gate (b): use server-assigned PKs when present so REPLACE-on-PK
        // collapses cleanly when pullHistory later re-fetches the same rows.
        val now = System.currentTimeMillis()
        val userRow = ChatMessageEntity(
            id = response.userMessageId ?: UUID.randomUUID().toString(),
            conversationId = convId,
            role = "user",
            content = userMessage,
            taskContextJson = taskContext?.let { gson.toJson(it) },
            createdAt = now
        )
        val assistantRow = ChatMessageEntity(
            id = response.assistantMessageId ?: UUID.randomUUID().toString(),
            conversationId = convId,
            role = "assistant",
            content = response.message,
            actionsJson = response.actions.takeIf { it.isNotEmpty() }
                ?.let { gson.toJson(it) },
            tokensInput = response.tokensUsed?.input,
            tokensOutput = response.tokensUsed?.output,
            // +1ms so chronological retrieval orders user-then-assistant
            // even if wall-clock collapses.
            createdAt = now + 1
        )
        chatMessageDao.upsertAll(listOf(userRow, assistantRow))

        return response
    }

    /**
     * F7 D.1 streaming variant. The user-visible bubble renders from
     * `ChatViewModel`'s `_turnState.partialText` while the stream is
     * in flight; we therefore do NOT optimistically write the user row
     * to Room here (D12 Gate (b)) — the row is committed alongside the
     * assistant row at [commitAssistantTurn] using server-assigned IDs
     * from the SSE done event.
     */
    fun streamMessage(
        userMessage: String,
        taskContextId: Long? = null,
        taskContext: ChatTaskContext? = null
    ): Flow<ChatStreamEvent> {
        resetIfNewDay()
        val convId = _conversationId.value

        val historyPayload = runBlocking {
            chatMessageDao.getForConversation(convId)
                .takeLast(maxHistoryPairs * 2)
                .map { ChatHistoryEntry(role = it.role, content = it.content) }
        }

        return streamClient.stream(
            ChatRequest(
                message = userMessage,
                conversationId = convId,
                taskContextId = taskContextId,
                taskContext = taskContext,
                history = historyPayload
            )
        )
    }

    /**
     * Append BOTH turns (user + assistant) once a streaming turn resolves
     * (Done or user-cancel commit-as-partial). [userText] is the original
     * message the user sent, captured by ChatViewModel before the stream
     * started. When the SSE done event carried server-assigned PKs they
     * are used as Room PKs so pullHistory()'s REPLACE-on-PK collapses
     * cleanly; on cancel (or older backend) we fall back to fresh UUIDs.
     */
    fun commitAssistantTurn(
        userText: String,
        text: String,
        actions: List<ChatActionResponse>,
        userMessageId: String? = null,
        assistantMessageId: String? = null,
        userTaskContext: ChatTaskContext? = null
    ) {
        val convId = _conversationId.value
        val now = System.currentTimeMillis()
        val userRow = ChatMessageEntity(
            id = userMessageId ?: UUID.randomUUID().toString(),
            conversationId = convId,
            role = "user",
            content = userText,
            taskContextJson = userTaskContext?.let { gson.toJson(it) },
            createdAt = now
        )
        val assistantRow = ChatMessageEntity(
            id = assistantMessageId ?: UUID.randomUUID().toString(),
            conversationId = convId,
            role = "assistant",
            content = text,
            actionsJson = actions.takeIf { it.isNotEmpty() }
                ?.let { gson.toJson(it) },
            // +1ms so chronological retrieval orders user-then-assistant.
            createdAt = now + 1
        )
        runBlocking {
            chatMessageDao.upsertAll(listOf(userRow, assistantRow))
        }
    }

    /**
     * Pulls server history for the current conversation and upserts into
     * Room. Idempotent — REPLACE-on-PK ensures duplicate rows from the
     * server collapse to one.
     */
    suspend fun pullHistory(conversationId: String? = null) {
        val convId = conversationId ?: _conversationId.value
        val response = api.aiChatHistory(conversationId = convId, limit = 200)
        val rows = response.messages.map { it.toEntity() }
        if (rows.isNotEmpty()) {
            chatMessageDao.upsertAll(rows)
        }
    }

    /**
     * Mints a new conversation ID. Old messages remain in Room and on the
     * backend under their original conversation_id; the UI Flow flips to
     * the empty new conversation (Item 7 of the audit doc).
     */
    fun clearConversation() {
        _conversationId.value = generateConversationId()
        conversationDate = LocalDate.now()
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now()
        if (today != conversationDate) {
            clearConversation()
        }
    }

    private fun generateConversationId(): String {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "chat_${date}_${UUID.randomUUID().toString().take(8)}"
    }

    private fun ChatMessageEntity.toChatMessage(): ChatMessage {
        val actions: List<ChatActionResponse> =
            actionsJson?.let {
                runCatching {
                    gson.fromJson(
                        it,
                        Array<ChatActionResponse>::class.java
                    ).toList()
                }.getOrDefault(emptyList())
            } ?: emptyList()
        return ChatMessage(
            id = id,
            role = if (role == "user") ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
            text = content,
            actions = actions,
            timestamp = createdAt
        )
    }

    private fun ChatMessageRecord.toEntity(): ChatMessageEntity {
        val createdMillis = runCatching {
            Instant.parse(createdAt).toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
        return ChatMessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            actionsJson = actions.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) },
            taskContextJson = taskContextSnapshot?.let { gson.toJson(it) },
            tokensInput = tokensUsed?.input,
            tokensOutput = tokensUsed?.output,
            createdAt = createdMillis
        )
    }
}
