package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationTierStateDao {
    @Query("SELECT * FROM medication_tier_states WHERE log_date = :date")
    fun observeForDate(date: String): Flow<List<MedicationTierStateEntity>>

    @Query("SELECT * FROM medication_tier_states ORDER BY log_date DESC, logged_at DESC")
    fun observeAll(): Flow<List<MedicationTierStateEntity>>

    @Query("SELECT * FROM medication_tier_states WHERE log_date = :date")
    suspend fun getForDateOnce(date: String): List<MedicationTierStateEntity>

    @Query(
        """
        SELECT * FROM medication_tier_states
        WHERE medication_id = :medicationId AND log_date = :date AND slot_id = :slotId
          AND time_of_day IS NULL
        LIMIT 1
        """
    )
    suspend fun getForTripleOnce(
        medicationId: Long,
        date: String,
        slotId: Long
    ): MedicationTierStateEntity?

    /**
     * D8 Item 8 — per-block lookup. Used by the live dual-write +
     * DataImporter backfill to upsert one row per `(med, slot, date,
     * timeOfDay)` quadruple.
     */
    @Query(
        """
        SELECT * FROM medication_tier_states
        WHERE medication_id = :medicationId AND log_date = :date AND slot_id = :slotId
          AND time_of_day = :timeOfDay
        LIMIT 1
        """
    )
    suspend fun getForQuadrupleOnce(
        medicationId: Long,
        date: String,
        slotId: Long,
        timeOfDay: String
    ): MedicationTierStateEntity?

    /**
     * D8 Item 8 — reader query for `HabitListViewModel`. Returns the
     * distinct non-null `time_of_day` values that have any tier row
     * for the given date. Used to count completed blocks without
     * falling back to the legacy `tiers_by_time` JSON column.
     */
    @Query(
        """
        SELECT DISTINCT time_of_day FROM medication_tier_states
        WHERE log_date = :date AND time_of_day IS NOT NULL
        """
    )
    suspend fun getDistinctTimeOfDayForDateOnce(date: String): List<String>

    @Query("SELECT * FROM medication_tier_states WHERE slot_id = :slotId AND log_date = :date")
    suspend fun getForSlotDateOnce(slotId: Long, date: String): List<MedicationTierStateEntity>

    @Query("SELECT * FROM medication_tier_states WHERE id = :id")
    suspend fun getByIdOnce(id: Long): MedicationTierStateEntity?

    @Query("SELECT * FROM medication_tier_states WHERE cloud_id = :cloudId LIMIT 1")
    suspend fun getByCloudIdOnce(cloudId: String): MedicationTierStateEntity?

    @Query("SELECT * FROM medication_tier_states")
    suspend fun getAllOnce(): List<MedicationTierStateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(state: MedicationTierStateEntity): Long

    @Update
    suspend fun update(state: MedicationTierStateEntity)

    @Delete
    suspend fun delete(state: MedicationTierStateEntity)

    @Query("DELETE FROM medication_tier_states WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE medication_tier_states SET cloud_id = :cloudId, updated_at = :now WHERE id = :id")
    suspend fun setCloudId(id: Long, cloudId: String?, now: Long)
}
