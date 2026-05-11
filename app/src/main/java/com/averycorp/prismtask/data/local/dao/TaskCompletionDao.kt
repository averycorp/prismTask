package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import kotlinx.coroutines.flow.Flow

data class DateCount(val date: Long, val count: Int)

data class DayOfWeekCount(val dayOfWeek: Int, val count: Int)

data class HourCount(val hour: Int, val count: Int)

@Dao
interface TaskCompletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: TaskCompletionEntity): Long

    @Query("SELECT * FROM task_completions WHERE completed_date >= :startDate AND completed_date <= :endDate ORDER BY completed_date ASC")
    fun getCompletionsInRange(startDate: Long, endDate: Long): Flow<List<TaskCompletionEntity>>

    @Query(
        "SELECT * FROM task_completions " +
            "WHERE project_id = :projectId AND completed_date >= :startDate AND completed_date <= :endDate " +
            "ORDER BY completed_date ASC"
    )
    fun getCompletionsByProject(projectId: Long, startDate: Long, endDate: Long): Flow<List<TaskCompletionEntity>>

    @Query(
        "SELECT completed_date AS date, COUNT(*) AS count FROM task_completions " +
            "WHERE completed_date >= :startDate AND completed_date <= :endDate " +
            "GROUP BY completed_date ORDER BY completed_date ASC"
    )
    fun getCompletionCountByDate(startDate: Long, endDate: Long): Flow<List<DateCount>>

    @Query(
        "SELECT CAST(strftime('%w', completed_date / 1000, 'unixepoch', 'localtime') AS INTEGER) AS dayOfWeek, " +
            "COUNT(*) AS count FROM task_completions " +
            "WHERE completed_date >= :startDate AND completed_date <= :endDate GROUP BY dayOfWeek"
    )
    fun getCompletionCountByDayOfWeek(startDate: Long, endDate: Long): Flow<List<DayOfWeekCount>>

    @Query(
        "SELECT CAST(strftime('%H', completed_at_time / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, " +
            "COUNT(*) AS count FROM task_completions " +
            "WHERE completed_date >= :startDate AND completed_date <= :endDate GROUP BY hour"
    )
    fun getCompletionCountByHour(startDate: Long, endDate: Long): Flow<List<HourCount>>

    @Query("SELECT COUNT(*) FROM task_completions")
    fun getTotalCompletions(): Flow<Int>

    @Query(
        "SELECT AVG(days_to_complete) FROM task_completions " +
            "WHERE days_to_complete IS NOT NULL AND completed_date >= :startDate AND completed_date <= :endDate"
    )
    fun getAverageDaysToComplete(startDate: Long, endDate: Long): Flow<Double?>

    @Query(
        "SELECT CAST(SUM(CASE WHEN was_overdue = 1 THEN 1 ELSE 0 END) AS REAL) / CAST(COUNT(*) AS REAL) * 100.0 " +
            "FROM task_completions " +
            "WHERE completed_date >= :startDate AND completed_date <= :endDate"
    )
    fun getOverdueRate(startDate: Long, endDate: Long): Flow<Double?>

    @Query("DELETE FROM task_completions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM task_completions WHERE task_id = :taskId")
    suspend fun deleteByTaskId(taskId: Long)

    // Returns the most recent completion for a task, scoped to the row that
    // was inserted most recently (completed_at_time DESC) so a same-day
    // complete-uncomplete-recomplete cycle finds the right entry. NULL if
    // the task has never been completed.
    @Query(
        "SELECT * FROM task_completions WHERE task_id = :taskId " +
            "ORDER BY completed_at_time DESC LIMIT 1"
    )
    suspend fun getLatestCompletionForTask(taskId: Long): TaskCompletionEntity?

    @Query("SELECT * FROM task_completions ORDER BY completed_date DESC")
    suspend fun getAllCompletionsOnce(): List<TaskCompletionEntity>

    @Query(
        "SELECT * FROM task_completions " +
            "WHERE completed_date >= :startDate AND completed_date <= :endDate " +
            "ORDER BY completed_date ASC"
    )
    suspend fun getCompletionsInRangeOnce(startDate: Long, endDate: Long): List<TaskCompletionEntity>

    @Query(
        "SELECT * FROM task_completions " +
            "WHERE project_id = :projectId AND completed_date >= :startDate AND completed_date <= :endDate " +
            "ORDER BY completed_date ASC"
    )
    suspend fun getCompletionsByProjectOnce(projectId: Long, startDate: Long, endDate: Long): List<TaskCompletionEntity>

    @Query("DELETE FROM task_completions")
    suspend fun deleteAll()
}
