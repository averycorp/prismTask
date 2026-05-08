package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query(
        "SELECT * FROM chat_messages " +
            "WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC"
    )
    fun observeForConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM chat_messages " +
            "WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC"
    )
    suspend fun getForConversation(conversationId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChatMessageEntity?

    @Query("SELECT DISTINCT conversation_id FROM chat_messages ORDER BY conversation_id DESC")
    suspend fun getDistinctConversationIds(): List<String>

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()

    @Query("DELETE FROM chat_messages WHERE conversation_id = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}
