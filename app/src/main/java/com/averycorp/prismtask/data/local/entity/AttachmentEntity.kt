package com.averycorp.prismtask.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An attachment row points at one of:
 *   - an image / arbitrary file in the app's sandbox (`type = "image"` or
 *     `"file"`), or
 *   - a raw URL (`type = "link"`).
 *
 * Historically attachments were task-only (legacy `taskId` column). The v80
 * migration adds optional `project_id` so a file can also be attached to a
 * project (with no task scope) — the `Files` section on a project surfaces
 * those rows. Exactly one of `taskId` / `project_id` should be set:
 *  - task attachments: `taskId > 0`, `project_id = null` (matches all
 *    pre-v80 rows; the column nullability lets us avoid recreating the
 *    table to relax the existing NOT NULL).
 *  - project attachments: `taskId = 0` sentinel, `project_id` set. The
 *    `0` sentinel exists because the legacy column is `INTEGER NOT NULL`
 *    and SQLite cannot drop NOT NULL via ALTER without a table rebuild;
 *    the FK to `tasks(id)` is gated by [ForeignKey.CASCADE] on a real id,
 *    and id `0` never exists in the tasks table so the constraint is a
 *    no-op. The repository enforces the "exactly one of" invariant.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("taskId"),
        Index("project_id"),
        Index(value = ["cloud_id"], unique = true)
    ]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "taskId") val taskId: Long,
    @ColumnInfo(name = "project_id") val projectId: Long? = null,
    /**
     * One of `"image"`, `"file"`, or `"link"`. `"image"` predates `"file"`
     * and is preserved for back-compat with rows written before any-MIME
     * uploads — it always points at an image inside the sandbox. New
     * non-image uploads are written as `"file"`.
     */
    val type: String,
    /**
     * For `link` attachments, this is the raw URL and round-trips cleanly via
     * sync. For `image` and `file` attachments it's a `file://` path into
     * this device's sandbox — the pointer syncs across devices, but the
     * actual bytes don't; opening a synced attachment on a different device
     * needs a future content-upload extension.
     */
    val uri: String,
    @ColumnInfo(name = "file_name") val fileName: String? = null,
    @ColumnInfo(name = "thumbnail_uri") val thumbnailUri: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "cloud_id") val cloudId: String? = null,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L
)
