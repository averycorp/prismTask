package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SelfCareDao {
    @Query("SELECT * FROM self_care_logs WHERE routine_type = :routineType AND date = :date LIMIT 1")
    fun getLogForDate(routineType: String, date: Long): Flow<SelfCareLogEntity?>

    @Query("SELECT * FROM self_care_logs WHERE date = :date")
    fun getLogsForDate(date: Long): Flow<List<SelfCareLogEntity>>

    @Query("SELECT * FROM self_care_logs WHERE routine_type = :routineType AND date = :date LIMIT 1")
    suspend fun getLogForDateOnce(routineType: String, date: Long): SelfCareLogEntity?

    @Query("SELECT * FROM self_care_logs WHERE id = :id LIMIT 1")
    suspend fun getLogById(id: Long): SelfCareLogEntity?

    @Query("SELECT * FROM self_care_steps WHERE id = :id LIMIT 1")
    suspend fun getStepById(id: Long): SelfCareStepEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SelfCareLogEntity): Long

    @Update
    suspend fun updateLog(log: SelfCareLogEntity)

    // Step CRUD
    @Query("SELECT * FROM self_care_steps WHERE routine_type = :routineType ORDER BY sort_order ASC")
    fun getStepsForRoutine(routineType: String): Flow<List<SelfCareStepEntity>>

    /**
     * Steps filtered to a single `time_of_day` value. The column stores a
     * CSV-like string such as `"morning"` or `"morning,evening"` so a
     * substring match is used instead of exact equality, mirroring the
     * reader helper in `SelfCareRoutines.parseTimeOfDay`.
     */
    @Query(
        "SELECT * FROM self_care_steps " +
            "WHERE routine_type = :routineType " +
            "AND time_of_day LIKE '%' || :timeOfDay || '%' " +
            "ORDER BY sort_order ASC"
    )
    fun getStepsForRoutineByTimeOfDay(
        routineType: String,
        timeOfDay: String
    ): Flow<List<SelfCareStepEntity>>

    @Query("SELECT * FROM self_care_steps WHERE routine_type = :routineType ORDER BY sort_order ASC")
    suspend fun getStepsForRoutineOnce(routineType: String): List<SelfCareStepEntity>

    @Query("SELECT COUNT(*) FROM self_care_steps")
    suspend fun getStepCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: SelfCareStepEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<SelfCareStepEntity>)

    @Update
    suspend fun updateStep(step: SelfCareStepEntity)

    @Delete
    suspend fun deleteStep(step: SelfCareStepEntity)

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM self_care_steps WHERE routine_type = :routineType")
    suspend fun getMaxSortOrder(routineType: String): Int

    @Update
    suspend fun updateSteps(steps: List<SelfCareStepEntity>)

    @Query("SELECT * FROM self_care_logs ORDER BY date DESC")
    suspend fun getAllLogsOnce(): List<SelfCareLogEntity>

    @Query("SELECT * FROM self_care_logs WHERE routine_type = :routineType ORDER BY date DESC")
    fun getLogsForRoutine(routineType: String): Flow<List<SelfCareLogEntity>>

    @Query("SELECT * FROM self_care_steps ORDER BY sort_order ASC")
    suspend fun getAllStepsOnce(): List<SelfCareStepEntity>

    @Query("SELECT * FROM self_care_steps WHERE step_id = :stepId AND routine_type = :routineType LIMIT 1")
    suspend fun getStepByStepIdOnce(stepId: String, routineType: String): SelfCareStepEntity?

    @Query("DELETE FROM self_care_steps WHERE id = :id")
    suspend fun deleteStepById(id: Long)

    @Query("DELETE FROM self_care_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    /**
     * v1.4.0 V10 follow-up: find any self-care step linked to a
     * specific medication by exact name. Used when the user records a
     * dose in the medication routine so the paired refill row gets
     * decremented.
     */
    @Query("SELECT * FROM self_care_steps WHERE medication_name = :name LIMIT 1")
    suspend fun getStepByMedicationName(name: String): SelfCareStepEntity?

    /**
     * Debug-only: removes every step for [routineType] whose `step_id` appears
     * in [stepIds]. Used by the Settings long-press re-seed action to wipe
     * seeded starter steps without touching user-added ones (which have
     * fresh UUID-shaped step_ids rather than the hardcoded ones in
     * [com.averycorp.prismtask.domain.model.SelfCareRoutines]).
     */
    @Query("DELETE FROM self_care_steps WHERE routine_type = :routineType AND step_id IN (:stepIds)")
    suspend fun deleteStepsByStepIds(routineType: String, stepIds: List<String>)
}
