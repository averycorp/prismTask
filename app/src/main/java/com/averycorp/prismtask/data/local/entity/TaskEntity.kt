package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_task_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProjectPhaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phase_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("project_id"),
        Index("parent_task_id"),
        Index("phase_id"),
        Index("due_date"),
        Index("is_completed"),
        Index("priority"),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    val title: String,
    val description: String? = null,
    @ColumnInfo(name = "due_date")
    val dueDate: Long? = null,
    @ColumnInfo(name = "due_time")
    val dueTime: Long? = null,
    val priority: Int = 0,
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,
    @ColumnInfo(name = "project_id")
    val projectId: Long? = null,
    @ColumnInfo(name = "parent_task_id")
    val parentTaskId: Long? = null,
    @ColumnInfo(name = "recurrence_rule")
    val recurrenceRule: String? = null,
    @ColumnInfo(name = "reminder_offset")
    val reminderOffset: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "planned_date")
    val plannedDate: Long? = null,
    @ColumnInfo(name = "estimated_duration")
    val estimatedDuration: Int? = null,
    @ColumnInfo(name = "scheduled_start_time")
    val scheduledStartTime: Long? = null,
    @ColumnInfo(name = "source_habit_id")
    val sourceHabitId: Long? = null,
    @ColumnInfo(name = "eisenhower_quadrant")
    val eisenhowerQuadrant: String? = null,
    @ColumnInfo(name = "eisenhower_updated_at")
    val eisenhowerUpdatedAt: Long? = null,
    @ColumnInfo(name = "eisenhower_reason")
    val eisenhowerReason: String? = null,
    /**
     * True when the user manually moved this task to a quadrant, so auto-classification
     * must not clobber it. Cleared by `TaskRepository.reclassify`.
     */
    @ColumnInfo(name = "user_overrode_quadrant", defaultValue = "0")
    val userOverrodeQuadrant: Boolean = false,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "is_flagged", defaultValue = "0")
    val isFlagged: Boolean = false,
    /**
     * Work-Life Balance category. Stored as the [com.averycorp.prismtask.domain.model.LifeCategory]
     * enum name (e.g. "WORK", "SELF_CARE"). Null or unknown values are treated as
     * [com.averycorp.prismtask.domain.model.LifeCategory.UNCATEGORIZED].
     */
    @ColumnInfo(name = "life_category")
    val lifeCategory: String? = null,
    /**
     * Reward / output type for the task. Stored as the
     * [com.averycorp.prismtask.domain.model.TaskMode] enum name (e.g. "WORK",
     * "PLAY", "RELAX"). Null or unknown values are treated as
     * [com.averycorp.prismtask.domain.model.TaskMode.UNCATEGORIZED].
     *
     * Orthogonal to [lifeCategory] — see `docs/WORK_PLAY_RELAX.md`.
     */
    @ColumnInfo(name = "task_mode")
    val taskMode: String? = null,
    /**
     * Start-friction / cognitive-load-to-start dimension. Stored as the
     * [com.averycorp.prismtask.domain.model.CognitiveLoad] enum name
     * (e.g. "EASY", "MEDIUM", "HARD"). Null or unknown values are treated
     * as [com.averycorp.prismtask.domain.model.CognitiveLoad.UNCATEGORIZED].
     *
     * Orthogonal to [lifeCategory], [taskMode], and [eisenhowerQuadrant] —
     * see `docs/COGNITIVE_LOAD.md`.
     */
    @ColumnInfo(name = "cognitive_load")
    val cognitiveLoad: String? = null,
    /**
     * Focus & Release Mode per-task overrides.
     *
     * Good Enough Timer override in minutes. Null = use global default.
     */
    @ColumnInfo(name = "good_enough_minutes_override")
    val goodEnoughMinutesOverride: Int? = null,
    /** Per-task max revision limit override. Null = use global default. */
    @ColumnInfo(name = "max_revisions_override")
    val maxRevisionsOverride: Int? = null,
    /** How many times this task has been re-opened for editing after completion. */
    @ColumnInfo(name = "revision_count", defaultValue = "0")
    val revisionCount: Int = 0,
    /** When true, task cannot be re-opened without explicit unlock. */
    @ColumnInfo(name = "revision_locked", defaultValue = "0")
    val revisionLocked: Boolean = false,
    /** Cumulative editing time in minutes (for Good Enough Timer tracking). */
    @ColumnInfo(name = "cumulative_edit_minutes", defaultValue = "0")
    val cumulativeEditMinutes: Int = 0,
    /**
     * Optional [ProjectPhaseEntity] this task belongs to. NULL when the
     * task is unphased (the legacy default) or when the task has no
     * project at all. Phase deletion sets this back to NULL via FK.
     *
     * Added in v1.8.x as part of the PrismTask-timeline-class scope (PR-1).
     */
    @ColumnInfo(name = "phase_id")
    val phaseId: Long? = null,
    /**
     * Per-task fractional progress in `0..100`. NULL means "binary" —
     * the task uses [isCompleted] as its source of truth (legacy
     * default). Non-NULL values are only authored by tasks that live
     * under a project; other surfaces keep reading [isCompleted].
     *
     * Added in v1.8.x as part of the PrismTask-timeline-class scope (PR-1).
     */
    @ColumnInfo(name = "progress_percent")
    val progressPercent: Int? = null
)
