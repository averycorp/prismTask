package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Completed leisure-session row (Leisure Budget v2.0 — Item 8).
 *
 * Inserted by either [com.averycorp.prismtask.notifications.LeisureTimerService]
 * on TIMER completion or the "Log past activity" modal (MANUAL). The
 * [activityId] FK uses SET_NULL on delete so deleting a pool entry
 * preserves the historical session row + its category snapshot — the
 * dashboard analytics keep working without orphan-row gymnastics.
 *
 * [category] is denormalized off the activity at insertion time so the
 * analytics queries don't need to join through `leisure_activities`,
 * AND so a free-text/orphan row (without a pool entry) still has a
 * categorisation.
 */
@Entity(
    tableName = "leisure_sessions",
    foreignKeys = [
        ForeignKey(
            entity = LeisureActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("activity_id"),
        Index("logged_at"),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class LeisureSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    @ColumnInfo(name = "activity_id")
    val activityId: Long? = null,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    @ColumnInfo(name = "logged_at")
    val loggedAt: Long,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
