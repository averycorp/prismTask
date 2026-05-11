package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single AI-memory preference the chat coach has learned about the user.
 *
 * The backend (`/api/v1/ai/chat`) is the source of truth — when the AI
 * emits a `remember_preference` / `forget_preference` tool call, the chat
 * handler updates Postgres and returns the authoritative list on the chat
 * response. The Android side mirrors that list into Room so the Settings
 * UI ("AI Memory") can render the same data offline. The CRUD endpoints
 * under `/api/v1/ai/memory` keep the two sides in sync when the user
 * edits or deletes from Settings.
 *
 * `id` is the server-generated UUID hex; REPLACE-on-PK keeps mirror
 * upserts idempotent.
 *
 * Capped at 15 rows per user (enforced server-side; the client doesn't
 * defensively cap because that would mask a bug in the eviction logic).
 */
@Entity(
    tableName = "user_ai_preferences",
    indices = [Index("updated_at")]
)
data class UserAiPreferenceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "preference_text")
    val preferenceText: String,
    @ColumnInfo(name = "source_message_id")
    val sourceMessageId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
