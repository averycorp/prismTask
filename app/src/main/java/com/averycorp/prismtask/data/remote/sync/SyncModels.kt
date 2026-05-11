package com.averycorp.prismtask.data.remote.sync

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Data models exchanged with the FastAPI backend sync endpoints
 * (`/api/v1/sync/push` and `/api/v1/sync/pull`).
 *
 * These mirror the Pydantic models in `backend/app/schemas/sync.py`. Keep the
 * field names and nullability aligned with the server to avoid 422 Unprocessable
 * Entity validation errors.
 *
 * Entity types (string): "task", "project", "tag", "habit", "habit_completion"
 * Operations (string): "create", "update", "delete" (push) / "upsert" (pull)
 *
 * Push models: [SyncOperation], [SyncPushRequest], [SyncPushResponse].
 * Pull models: [SyncChange], [SyncPullResponse].
 */

data class SyncOperation(
    @SerializedName("entity_type") val entityType: String,
    val operation: String,
    @SerializedName("entity_id") val entityId: Long? = null,
    val data: JsonObject? = null,
    @SerializedName("client_timestamp") val clientTimestamp: String
)

data class SyncPushRequest(val operations: List<SyncOperation>, @SerializedName("last_sync") val lastSync: String? = null)

data class SyncPushResponse(
    val processed: Int = 0,
    val errors: List<String> = emptyList(),
    @SerializedName("server_timestamp") val serverTimestamp: String? = null
)

data class SyncChange(
    @SerializedName("entity_type") val entityType: String,
    val operation: String,
    @SerializedName("entity_id") val entityId: Long,
    val data: JsonObject? = null,
    val timestamp: String? = null
)

data class SyncPullResponse(
    val changes: List<SyncChange> = emptyList(),
    @SerializedName("server_timestamp") val serverTimestamp: String? = null
)
