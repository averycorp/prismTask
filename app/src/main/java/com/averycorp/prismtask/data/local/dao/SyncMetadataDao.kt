package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: SyncMetadataEntity)

    @Query("SELECT * FROM sync_metadata WHERE local_id = :localId AND entity_type = :entityType")
    suspend fun get(localId: Long, entityType: String): SyncMetadataEntity?

    @Query("SELECT * FROM sync_metadata WHERE pending_action IS NOT NULL ORDER BY entity_type ASC")
    suspend fun getPendingActions(): List<SyncMetadataEntity>

    @Query("SELECT * FROM sync_metadata WHERE pending_action IS NOT NULL")
    fun observePending(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT COUNT(*) FROM sync_metadata WHERE pending_action IS NOT NULL")
    fun getPendingCount(): Flow<Int>

    @Query(
        "UPDATE sync_metadata SET pending_action = NULL, last_synced_at = :now, retry_count = 0 " +
            "WHERE local_id = :localId AND entity_type = :entityType"
    )
    suspend fun clearPendingAction(localId: Long, entityType: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM sync_metadata WHERE local_id = :localId AND entity_type = :entityType")
    suspend fun delete(localId: Long, entityType: String)

    @Query("DELETE FROM sync_metadata")
    suspend fun deleteAll()

    @Query("SELECT cloud_id FROM sync_metadata WHERE local_id = :localId AND entity_type = :entityType")
    suspend fun getCloudId(localId: Long, entityType: String): String?

    @Query("SELECT local_id FROM sync_metadata WHERE cloud_id = :cloudId AND entity_type = :entityType")
    suspend fun getLocalId(cloudId: String, entityType: String): Long?

    /**
     * Returns every non-null `cloud_id` for a given `entity_type`. Used by
     * [com.averycorp.prismtask.data.privacy.MentalHealthDataWiper] to find
     * the Firestore docs that need deleting after a partial-table local wipe.
     */
    @Query(
        "SELECT cloud_id FROM sync_metadata " +
            "WHERE entity_type = :entityType AND cloud_id IS NOT NULL"
    )
    suspend fun getAllCloudIdsForType(entityType: String): List<String>

    /**
     * Drops every `sync_metadata` row of a given `entity_type` after its
     * underlying local rows have been wiped. Keeps the metadata table from
     * pointing at orphaned local IDs that a future sync would otherwise try
     * to push or re-pull.
     */
    @Query("DELETE FROM sync_metadata WHERE entity_type = :entityType")
    suspend fun deleteAllForType(entityType: String)

    /**
     * Increments retry_count and, once it crosses [MAX_RETRIES], drops the
     * pending action so the queue cannot loop forever on a permanently
     * failing operation. The row is preserved so the dead-letter is visible
     * via [getDeadLettered] for surfacing to the user.
     */
    @Query(
        "UPDATE sync_metadata SET " +
            "retry_count = retry_count + 1, " +
            "pending_action = CASE WHEN retry_count + 1 >= :maxRetries THEN NULL ELSE pending_action END " +
            "WHERE local_id = :localId AND entity_type = :entityType"
    )
    suspend fun incrementRetry(localId: Long, entityType: String, maxRetries: Int = MAX_RETRIES)

    @Query("SELECT * FROM sync_metadata WHERE retry_count >= :maxRetries")
    suspend fun getDeadLettered(maxRetries: Int = MAX_RETRIES): List<SyncMetadataEntity>

    companion object {
        const val MAX_RETRIES: Int = 10
    }
}
