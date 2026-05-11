package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE taskId = :taskId ORDER BY created_at ASC")
    fun getAttachmentsForTask(taskId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE project_id = :projectId ORDER BY created_at ASC")
    fun getAttachmentsForProject(projectId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT COUNT(*) FROM attachments WHERE project_id = :projectId")
    fun getAttachmentCountForProject(projectId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity): Long

    @Delete
    suspend fun delete(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE taskId = :taskId")
    suspend fun deleteAllForTask(taskId: Long)

    @Query("SELECT COUNT(*) FROM attachments WHERE taskId = :taskId")
    fun getAttachmentCountForTask(taskId: Long): Flow<Int>

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()

    @Query("SELECT * FROM attachments")
    suspend fun getAllOnce(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: Long): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE cloud_id = :cloudId LIMIT 1")
    suspend fun getByCloudIdOnce(cloudId: String): AttachmentEntity?

    @Query("UPDATE attachments SET cloud_id = :cloudId, updated_at = :now WHERE id = :id")
    suspend fun setCloudId(id: Long, cloudId: String?, now: Long)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: Long)
}
