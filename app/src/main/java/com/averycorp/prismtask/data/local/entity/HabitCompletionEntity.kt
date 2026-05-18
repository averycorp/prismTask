package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "habit_completions",
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habit_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("habit_id"),
        Index("completed_date"),
        Index("completed_date_local"),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class HabitCompletionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    @ColumnInfo(name = "habit_id")
    val habitId: Long,
    @ColumnInfo(name = "completed_date")
    val completedDate: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long = System.currentTimeMillis(),
    val notes: String? = null,
    // ISO LocalDate string ("yyyy-MM-dd") in the device's local timezone at write
    // time. Timezone-neutral successor to [completedDate]; nullable only for
    // legacy rows prior to migration 49→50.
    @ColumnInfo(name = "completed_date_local")
    val completedDateLocal: String? = null,
    // When true the row is a "skip marker" — the user long-pressed the habit
    // circle to declare the day off. Skip markers don't count toward the daily
    // completion target; the forgiveness streak treats their dates as kept.
    @ColumnInfo(name = "is_skipped", defaultValue = "0")
    val isSkipped: Boolean = false
)
