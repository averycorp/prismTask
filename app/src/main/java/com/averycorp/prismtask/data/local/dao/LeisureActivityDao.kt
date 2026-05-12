package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import kotlinx.coroutines.flow.Flow

/**
 * Leisure Budget v2.0 — Item 7. Pool CRUD + recency-weighted reads
 * for [com.averycorp.prismtask.domain.usecase.LeisureSampler].
 */
@Dao
interface LeisureActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(activity: LeisureActivityEntity): Long

    @Update
    suspend fun update(activity: LeisureActivityEntity)

    @Query("DELETE FROM leisure_activities WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM leisure_activities WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LeisureActivityEntity?

    @Query("SELECT * FROM leisure_activities ORDER BY category, name")
    fun getAll(): Flow<List<LeisureActivityEntity>>

    @Query("SELECT * FROM leisure_activities ORDER BY category, name")
    suspend fun getAllOnce(): List<LeisureActivityEntity>

    @Query(
        "SELECT * FROM leisure_activities " +
            "WHERE enabled = 1 AND category IN (:categories) " +
            "ORDER BY category, name"
    )
    fun getEnabledInCategories(
        categories: List<String>
    ): Flow<List<LeisureActivityEntity>>

    @Query(
        "SELECT * FROM leisure_activities " +
            "WHERE enabled = 1 AND category IN (:categories)"
    )
    suspend fun getEnabledInCategoriesOnce(
        categories: List<String>
    ): List<LeisureActivityEntity>

    @Query(
        "UPDATE leisure_activities " +
            "SET last_completed_at = :timestamp, updated_at = :timestamp " +
            "WHERE id = :id AND (last_completed_at IS NULL OR last_completed_at < :timestamp)"
    )
    suspend fun touchLastCompletedAt(id: Long, timestamp: Long)

    @Query("SELECT COUNT(*) FROM leisure_activities WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Query("SELECT * FROM leisure_activities WHERE cloud_id = :cloudId LIMIT 1")
    suspend fun getByCloudIdOnce(cloudId: String): LeisureActivityEntity?

    @Query(
        "UPDATE leisure_activities " +
            "SET cloud_id = :cloudId, updated_at = :timestamp " +
            "WHERE id = :id"
    )
    suspend fun setCloudId(
        id: Long,
        cloudId: String,
        timestamp: Long = System.currentTimeMillis()
    )
}
