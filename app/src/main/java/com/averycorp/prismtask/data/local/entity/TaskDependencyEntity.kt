package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A directed dependency edge between two [TaskEntity] rows.
 *
 * Semantics: completing [blockerTaskId] unblocks [blockedTaskId]. The
 * domain layer surfaces a blocked task as
 * `TaskState.BlockedByDependency` rather than `Overdue` so the
 * forgiveness-first streak does not break on a transitively-stalled
 * task.
 *
 * The unique `(blocker, blocked)` index prevents duplicate edges; the
 * write-time [com.averycorp.prismtask.domain.usecase.DependencyCycleGuard]
 * rejects edges that would close a cycle (P5 — mirrors
 * `AutomationEngine.kt:73-86`).
 *
 * Added in v1.8.x as part of the PrismTask-timeline-class scope (PR-2).
 */
@Entity(
    tableName = "task_dependencies",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["blocker_task_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["blocked_task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["blocker_task_id", "blocked_task_id"], unique = true),
        Index("blocked_task_id"),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class TaskDependencyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    @ColumnInfo(name = "blocker_task_id")
    val blockerTaskId: Long,
    @ColumnInfo(name = "blocked_task_id")
    val blockedTaskId: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
