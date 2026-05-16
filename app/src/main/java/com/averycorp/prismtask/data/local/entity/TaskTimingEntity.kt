package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One logged interval of work on a task. Multiple rows per task — manual
 * "Log time" entries, Pomodoro session completions, and explicit timer
 * stops all land here.
 *
 * Data layer underneath the analytics time-tracking aggregator (C4)
 * and bar chart (C5). `cloud_id` is included from the start so cross-device
 * sync can be wired in P2-D as a follow-up without a schema migration.
 *
 * `started_at` / `ended_at` are nullable: manual log entries can record
 * just a duration without a wall-clock window.
 */
@Entity(
    tableName = "task_timings",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("task_id"),
        Index("started_at"),
        Index("created_at"),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class TaskTimingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    @ColumnInfo(name = "task_id")
    val taskId: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    /** "manual" | "pomodoro" | "timer". Free-form to allow future sources. */
    @ColumnInfo(defaultValue = SOURCE_MANUAL)
    val source: String = SOURCE_MANUAL,
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_POMODORO = "pomodoro"
        const val SOURCE_TIMER = "timer"
    }
}
