package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.ChatMessageDao
import com.averycorp.prismtask.data.local.entity.ChatMessageEntity
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatHistoryEntry
import com.averycorp.prismtask.data.remote.api.ChatMessageRecord
import com.averycorp.prismtask.data.remote.api.ChatRequest
import com.averycorp.prismtask.data.remote.api.ChatResponse
import com.averycorp.prismtask.data.remote.api.ChatTaskContext
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    private val chatMessageDao: ChatMessageDao
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
     * turns inside the handler; on success we mirror into Room so the UI
     * Flow updates immediately. Failures during local mirroring are
     * non-fatal — the next `pullHistory()` call reconciles.
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

        val now = System.currentTimeMillis()
        val userRowLocalId = UUID.randomUUID().toString()
        val userRow = ChatMessageEntity(
            id = userRowLocalId,
            conversationId = convId,
            role = "user",
            content = userMessage,
            taskContextJson = taskContext?.let { gson.toJson(it) },
            createdAt = now
        )
        // Optimistic local insert so the UI shows the user turn before the
        // server round-trip completes. Server will write its own row with
        // a different id; we replace this optimistic row with the
        // authoritative one on response by deleting it first.
        chatMessageDao.upsert(userRow)

        val response = api.aiChat(
            ChatRequest(
                message = userMessage,
                conversationId = convId,
                taskContextId = taskContextId,
                taskContext = taskContext,
                history = historyPayload
            )
        )

        val assistantRow = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = "assistant",
            content = response.message,
            actionsJson = response.actions.takeIf { it.isNotEmpty() }
                ?.let { gson.toJson(it) },
            tokensInput = response.tokensUsed?.input,
            tokensOutput = response.tokensUsed?.output,
            createdAt = System.currentTimeMillis()
        )
        chatMessageDao.upsert(assistantRow)

        return response
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
