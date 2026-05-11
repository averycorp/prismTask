package com.averycorp.prismtask.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.averycorp.prismtask.data.local.dao.AttachmentDao
import com.averycorp.prismtask.data.local.entity.AttachmentEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepository
@Inject
constructor(
    private val attachmentDao: AttachmentDao,
    private val syncTracker: SyncTracker
) {
    /**
     * Legacy entry point — kept so existing photo-picker callers don't
     * have to change. Delegates to [addFileAttachment] with `image/\*`
     * MIME so the new schema fields get filled.
     */
    suspend fun addImageAttachment(context: Context, taskId: Long, sourceUri: Uri): Long =
        addFileAttachment(
            context = context,
            taskId = taskId,
            projectId = null,
            sourceUri = sourceUri,
            mimeType = context.contentResolver.getType(sourceUri) ?: "image/*",
            type = "image"
        )

    /**
     * Copy `sourceUri` into the app sandbox and persist an attachment row.
     *
     *  - Pass `taskId` to attach to a task (`projectId` stays null).
     *  - Pass `projectId` to attach to a project (`taskId` becomes the
     *    sentinel `0`).
     *  - Passing both is allowed but discouraged — UI surfaces only one.
     *
     * `mimeType` defaults to whatever ContentResolver reports for the URI
     * (or `application/octet-stream` if unknown). `type` is `"file"` for
     * any-MIME uploads and `"image"` for the legacy image path.
     */
    suspend fun addFileAttachment(
        context: Context,
        taskId: Long? = null,
        projectId: Long? = null,
        sourceUri: Uri,
        mimeType: String? = null,
        type: String = "file"
    ): Long {
        require(taskId != null || projectId != null) {
            "addFileAttachment requires at least one of taskId / projectId"
        }
        val resolver = context.contentResolver
        val resolvedMime = mimeType ?: resolver.getType(sourceUri) ?: "application/octet-stream"
        val displayName = queryDisplayName(context, sourceUri)
        val sandboxName = "att_${UUID.randomUUID()}"
        val destDir = File(context.filesDir, "attachments")
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, sandboxName)

        resolver.openInputStream(sourceUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Could not open input stream for $sourceUri")

        val entity = AttachmentEntity(
            taskId = taskId ?: PROJECT_ONLY_TASK_SENTINEL,
            projectId = projectId,
            type = type,
            uri = destFile.absolutePath,
            fileName = displayName ?: sandboxName,
            mimeType = resolvedMime,
            sizeBytes = destFile.length().takeIf { it > 0 },
            updatedAt = System.currentTimeMillis()
        )
        val id = attachmentDao.insert(entity)
        syncTracker.trackCreate(id, "attachment")
        return id
    }

    suspend fun addLinkAttachment(taskId: Long, url: String): Long {
        val entity = AttachmentEntity(
            taskId = taskId,
            type = "link",
            uri = url,
            fileName = url,
            updatedAt = System.currentTimeMillis()
        )
        val id = attachmentDao.insert(entity)
        syncTracker.trackCreate(id, "attachment")
        return id
    }

    fun getAttachments(taskId: Long): Flow<List<AttachmentEntity>> =
        attachmentDao.getAttachmentsForTask(taskId)

    fun getAttachmentCount(taskId: Long): Flow<Int> =
        attachmentDao.getAttachmentCountForTask(taskId)

    fun getProjectAttachments(projectId: Long): Flow<List<AttachmentEntity>> =
        attachmentDao.getAttachmentsForProject(projectId)

    fun getProjectAttachmentCount(projectId: Long): Flow<Int> =
        attachmentDao.getAttachmentCountForProject(projectId)

    suspend fun deleteAttachment(context: Context, attachment: AttachmentEntity) {
        if (attachment.type == "image" || attachment.type == "file") {
            val file = File(attachment.uri)
            if (file.exists()) file.delete()
        }
        attachmentDao.delete(attachment)
        syncTracker.trackDelete(attachment.id, "attachment")
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    private companion object {
        /**
         * Sentinel value for the legacy `taskId NOT NULL` column when an
         * attachment is project-only. SQLite ALTER cannot relax `NOT NULL`
         * without a table rebuild, so we use id `0` (which never exists in
         * `tasks`) and let the repository enforce the "exactly one of"
         * invariant on insert.
         */
        const val PROJECT_ONLY_TASK_SENTINEL: Long = 0L
    }
}

