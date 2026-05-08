package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted chat turn for the conversational coach.
 *
 * Per `docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md` (Item 5, Path A).
 * The backend authors writes inside `/api/v1/ai/chat`; the Android client
 * mirrors them into Room as a local cache so the chat surface is
 * available offline and re-renders instantly on reopen.
 *
 * `id` is the server-generated UUID (hex). Conflict strategy on cross-
 * device sync is REPLACE — same PK, same row, idempotent.
 *
 * No `user_id` column: PrismTaskDatabase is per-account-per-device, so
 * multi-tenant isolation lives on the server. JSON columns
 * (`actions_json`, `task_context_json`) are Gson-serialized strings.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["conversation_id", "created_at"]),
        Index("created_at")
    ]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    // "user" | "assistant"
    val role: String,
    val content: String,
    @ColumnInfo(name = "actions_json")
    val actionsJson: String? = null,
    @ColumnInfo(name = "task_context_json")
    val taskContextJson: String? = null,
    @ColumnInfo(name = "tokens_input")
    val tokensInput: Int? = null,
    @ColumnInfo(name = "tokens_output")
    val tokensOutput: Int? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
