package com.averycorp.prismtask.data.repository

import android.content.Context
import android.net.Uri
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
constructor(private val attachmentDao: AttachmentDao, private val syncTracker: SyncTracker) {
    suspend fun addImageAttachment(context: Context, taskId: Long, sourceUri: Uri): Long {
        val fileName = "img_${UUID.randomUUID()}"
        val destDir = File(context.filesDir, "attachments")
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, fileName)

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val entity = AttachmentEntity(
            taskId = taskId,
            type = "image",
            uri = destFile.absolutePath,
            fileName = fileName,
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

    suspend fun deleteAttachment(context: Context, attachment: AttachmentEntity) {
        if (attachment.type == "image") {
            val file = File(attachment.uri)
            if (file.exists()) file.delete()
        }
        attachmentDao.delete(attachment)
        syncTracker.trackDelete(attachment.id, "attachment")
    }
}
