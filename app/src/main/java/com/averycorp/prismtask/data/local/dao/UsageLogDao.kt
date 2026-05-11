package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.UsageLogEntity
import kotlinx.coroutines.flow.Flow

data class EntityFrequency(val entityId: Long, val entityName: String, val count: Int)

@Dao
interface UsageLogDao {
    @Insert
    suspend fun insert(log: UsageLogEntity)

    @Query(
        """
        SELECT entity_id AS entityId, entity_name AS entityName, COUNT(*) AS count
        FROM usage_logs
        WHERE event_type = 'tag_assigned' AND entity_id IS NOT NULL
        GROUP BY entity_id
        ORDER BY count DESC
    """
    )
    fun getTagFrequency(): Flow<List<EntityFrequency>>

    @Query(
        """
        SELECT entity_id AS entityId, entity_name AS entityName, COUNT(*) AS count
        FROM usage_logs
        WHERE event_type = 'project_assigned' AND entity_id IS NOT NULL
        GROUP BY entity_id
        ORDER BY count DESC
    """
    )
    fun getProjectFrequency(): Flow<List<EntityFrequency>>

    @Query(
        """
        SELECT entity_id AS entityId, entity_name AS entityName, COUNT(*) AS count
        FROM usage_logs
        WHERE event_type = 'tag_assigned' AND entity_id IS NOT NULL
          AND title_keywords LIKE '%' || :keyword || '%'
        GROUP BY entity_id
        ORDER BY count DESC
        LIMIT 5
    """
    )
    fun getTagsForKeyword(keyword: String): Flow<List<EntityFrequency>>

    @Query(
        """
        SELECT entity_id AS entityId, entity_name AS entityName, COUNT(*) AS count
        FROM usage_logs
        WHERE event_type = 'project_assigned' AND entity_id IS NOT NULL
          AND title_keywords LIKE '%' || :keyword || '%'
        GROUP BY entity_id
        ORDER BY count DESC
        LIMIT 3
    """
    )
    fun getProjectsForKeyword(keyword: String): Flow<List<EntityFrequency>>

    @Query("SELECT * FROM usage_logs ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<UsageLogEntity>
}
