package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.remote.SyncTracker
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository
@Inject
constructor(
    private val tagDao: TagDao,
    private val syncTracker: SyncTracker
) {
    fun getAllTags(): Flow<List<TagEntity>> = tagDao.getAllTags()

    fun searchTags(query: String): Flow<List<TagEntity>> = tagDao.searchTags(query)

    /** Exact-match (case-insensitive) lookup. Used by chat find-or-create. */
    suspend fun getTagByNameOnce(name: String): TagEntity? = tagDao.getTagByNameOnce(name)

    suspend fun addTag(name: String, color: String = "#6B7280"): Long {
        val id = tagDao.insert(TagEntity(name = name, color = color))
        syncTracker.trackCreate(id, "tag")
        return id
    }

    suspend fun updateTag(tag: TagEntity) {
        tagDao.update(tag)
        syncTracker.trackUpdate(tag.id, "tag")
    }

    suspend fun deleteTag(tag: TagEntity) {
        syncTracker.trackDelete(tag.id, "tag")
        tagDao.delete(tag)
    }

    fun getTagsForTask(taskId: Long): Flow<List<TagEntity>> = tagDao.getTagsForTask(taskId)

    suspend fun setTagsForTask(taskId: Long, tagIds: List<Long>) {
        tagDao.removeAllTagsFromTask(taskId)
        tagIds.forEach { tagId ->
            tagDao.addTagToTask(TaskTagCrossRef(taskId = taskId, tagId = tagId))
        }
    }

    suspend fun addTagToTask(taskId: Long, tagId: Long) {
        tagDao.addTagToTask(TaskTagCrossRef(taskId = taskId, tagId = tagId))
    }

    suspend fun removeTagFromTask(taskId: Long, tagId: Long) {
        tagDao.removeTagFromTask(taskId, tagId)
    }
}
