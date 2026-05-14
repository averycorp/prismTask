package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [MoodEnergyLogEntity] — the v1.4.0 V7 daily mood/energy check-ins.
 */
@Dao
interface MoodEnergyLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: MoodEnergyLogEntity): Long

    @Update
    suspend fun update(log: MoodEnergyLogEntity)

    @Query("SELECT * FROM mood_energy_logs WHERE date = :date ORDER BY time_of_day")
    suspend fun getByDate(date: Long): List<MoodEnergyLogEntity>

    @Query(
        "SELECT * FROM mood_energy_logs WHERE date = :date " +
            "AND time_of_day = :timeOfDay LIMIT 1"
    )
    suspend fun getByDateAndTimeOfDayOnce(
        date: Long,
        timeOfDay: String
    ): MoodEnergyLogEntity?

    @Query("SELECT * FROM mood_energy_logs WHERE date >= :start AND date <= :end ORDER BY date ASC, time_of_day ASC")
    fun observeRange(start: Long, end: Long): Flow<List<MoodEnergyLogEntity>>

    @Query("SELECT * FROM mood_energy_logs WHERE date >= :start AND date <= :end ORDER BY date ASC, time_of_day ASC")
    suspend fun getRange(start: Long, end: Long): List<MoodEnergyLogEntity>

    @Query("SELECT * FROM mood_energy_logs ORDER BY date DESC")
    suspend fun getAll(): List<MoodEnergyLogEntity>

    @Query("DELETE FROM mood_energy_logs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM mood_energy_logs")
    suspend fun deleteAll()

    @Query("SELECT * FROM mood_energy_logs ORDER BY date DESC")
    suspend fun getAllOnce(): List<MoodEnergyLogEntity>

    @Query("SELECT * FROM mood_energy_logs WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: Long): MoodEnergyLogEntity?

    @Query("SELECT * FROM mood_energy_logs WHERE cloud_id = :cloudId LIMIT 1")
    suspend fun getByCloudIdOnce(cloudId: String): MoodEnergyLogEntity?

    /**
     * Returns the count of mood entries whose [MoodEnergyLogEntity.mood] is
     * less than or equal to [moodCeiling] and whose
     * [MoodEnergyLogEntity.createdAt] is at or after [sinceCreatedAtMillis].
     *
     * Used by `RecentMoodSignal` to decide whether to suppress
     * non-critical notification cadence after a low-mood log. Filters on
     * `created_at` (wall-clock entry time) rather than `date`
     * (midnight-normalized) so a 48-hour window is exact rather than
     * day-aligned.
     */
    @Query(
        "SELECT COUNT(*) FROM mood_energy_logs " +
            "WHERE mood <= :moodCeiling AND created_at >= :sinceCreatedAtMillis"
    )
    suspend fun countLowMoodSinceOnce(
        moodCeiling: Int,
        sinceCreatedAtMillis: Long
    ): Int

    @Query("UPDATE mood_energy_logs SET cloud_id = :cloudId, updated_at = :now WHERE id = :id")
    suspend fun setCloudId(id: Long, cloudId: String?, now: Long)

    @Query("DELETE FROM mood_energy_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
