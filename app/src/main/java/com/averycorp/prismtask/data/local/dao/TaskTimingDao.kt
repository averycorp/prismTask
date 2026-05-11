package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import kotlinx.coroutines.flow.Flow

/**
 * Projection used by aggregations: total minutes per group key.
 */
data class TimingGroupTotal(val groupKey: String, val totalMinutes: Int, val taskCount: Int)

@Dao
interface TaskTimingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(timing: TaskTimingEntity): Long

    @Update
    suspend fun update(timing: TaskTimingEntity)

    @Delete
    suspend fun delete(timing: TaskTimingEntity)

    @Query("DELETE FROM task_timings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM task_timings WHERE task_id = :taskId")
    suspend fun deleteByTaskId(taskId: Long)

    @Query("SELECT * FROM task_timings WHERE id = :id")
    suspend fun getByIdOnce(id: Long): TaskTimingEntity?

    @Query("SELECT * FROM task_timings WHERE task_id = :taskId ORDER BY created_at DESC")
    fun getTimingsForTask(taskId: Long): Flow<List<TaskTimingEntity>>

    @Query("SELECT * FROM task_timings WHERE task_id = :taskId ORDER BY created_at DESC")
    suspend fun getTimingsForTaskOnce(taskId: Long): List<TaskTimingEntity>

    @Query("SELECT * FROM task_timings ORDER BY created_at DESC")
    suspend fun getAllOnce(): List<TaskTimingEntity>

    @Query(
        "SELECT * FROM task_timings " +
            "WHERE created_at >= :startMillis AND created_at < :endMillis " +
            "ORDER BY created_at ASC"
    )
    fun getTimingsInRange(startMillis: Long, endMillis: Long): Flow<List<TaskTimingEntity>>

    @Query(
        "SELECT COALESCE(SUM(duration_minutes), 0) FROM task_timings " +
            "WHERE task_id = :taskId"
    )
    suspend fun sumMinutesForTask(taskId: Long): Int

    @Query(
        "SELECT COALESCE(SUM(duration_minutes), 0) FROM task_timings " +
            "WHERE task_id = :taskId"
    )
    fun observeSumMinutesForTask(taskId: Long): Flow<Int>

    @Query("DELETE FROM task_timings")
    suspend fun deleteAll()
}
