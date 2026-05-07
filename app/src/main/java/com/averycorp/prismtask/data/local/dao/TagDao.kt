package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    fun getTagById(id: Long): Flow<TagEntity?>

    @Query("SELECT t.* FROM tags t INNER JOIN task_tags tt ON t.id = tt.tagId WHERE tt.taskId = :taskId")
    fun getTagsForTask(taskId: Long): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun getAllTagsOnce(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTagByIdOnce(id: Long): TagEntity?

    @Query("SELECT tagId FROM task_tags WHERE taskId = :taskId")
    suspend fun getTagIdsForTaskOnce(taskId: Long): List<Long>

    @Query("SELECT t.name FROM tags t INNER JOIN task_tags tt ON t.id = tt.tagId WHERE tt.taskId = :taskId")
    suspend fun getTagNamesForTaskOnce(taskId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE name LIKE '%' || :query || '%'")
    fun searchTags(query: String): Flow<List<TagEntity>>

    /** Exact-match lookup (case-insensitive via SQLite NOCASE). */
    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getTagByNameOnce(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTagToTask(crossRef: TaskTagCrossRef)

    @Query("DELETE FROM task_tags WHERE taskId = :taskId AND tagId = :tagId")
    suspend fun removeTagFromTask(taskId: Long, tagId: Long)

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    suspend fun removeAllTagsFromTask(taskId: Long)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    @Query("DELETE FROM task_tags")
    suspend fun deleteAllCrossRefs()
}
