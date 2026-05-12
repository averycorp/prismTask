package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-owned leisure-activity pool entry (Leisure Budget v2.0 — Item 7).
 *
 * Categories are spec-locked to four buckets stored as plain strings
 * matching the [com.averycorp.prismtask.domain.model.LeisureCategory]
 * enum (PHYSICAL / SOCIAL / CREATIVE / PASSIVE). Stored as String not
 * enum so Room can index it without a converter.
 *
 * [lastCompletedAt] is denormalized off `leisure_sessions` so the
 * recency-weighted random-pull algorithm can sample without a join.
 * The repository keeps it in sync on every session insert.
 */
@Entity(
    tableName = "leisure_activities",
    indices = [
        Index("category"),
        Index("enabled"),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class LeisureActivityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "default_duration_minutes")
    val defaultDurationMinutes: Int? = null,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
    @ColumnInfo(name = "last_completed_at")
    val lastCompletedAt: Long? = null
)
