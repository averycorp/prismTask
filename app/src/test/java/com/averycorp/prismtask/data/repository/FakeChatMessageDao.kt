package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.ChatMessageDao
import com.averycorp.prismtask.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory ChatMessageDao for unit tests. Observability semantics
 * mirror Room: emits a snapshot of rows for a given conversation_id
 * whenever the underlying list changes.
 */
class FakeChatMessageDao : ChatMessageDao {
    private val state = MutableStateFlow<List<ChatMessageEntity>>(emptyList())

    val rowsSnapshot: List<ChatMessageEntity>
        get() = state.value

    override suspend fun upsert(message: ChatMessageEntity) {
        state.value = state.value.filterNot { it.id == message.id } + message
    }

    override suspend fun upsertAll(messages: List<ChatMessageEntity>) {
        val keep = state.value.filterNot { row -> messages.any { it.id == row.id } }
        state.value = keep + messages
    }

    override fun observeForConversation(conversationId: String): Flow<List<ChatMessageEntity>> =
        state.map { all ->
            all.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }
        }

    override suspend fun getForConversation(conversationId: String): List<ChatMessageEntity> =
        state.value.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }

    override suspend fun getById(id: String): ChatMessageEntity? =
        state.value.firstOrNull { it.id == id }

    override suspend fun getDistinctConversationIds(): List<String> =
        state.value.map { it.conversationId }.distinct().sortedDescending()

    override suspend fun deleteAll() {
        state.value = emptyList()
    }

    override suspend fun deleteForConversation(conversationId: String) {
        state.value = state.value.filterNot { it.conversationId == conversationId }
    }
}
