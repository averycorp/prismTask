package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Projection used by [HabitCompletionDao.getLastCompletionDatesPerHabit].
 * Top-level so Room's KSP processor can reflect on its constructor without
 * the awkwardness of a nested class on an interface.
 */
data class HabitLastCompletion(val habitId: Long, val lastCompletedDate: Long)

@Dao
interface HabitCompletionDao {
    @Query("SELECT * FROM habit_completions WHERE habit_id = :habitId ORDER BY completed_date DESC")
    fun getCompletionsForHabit(habitId: Long): Flow<List<HabitCompletionEntity>>

    @Deprecated(
        message = "Use getCompletionsForDateLocal(date). Epoch-based date matching is " +
            "timezone-sensitive; completed_date_local is the timezone-neutral key. " +
            "Will be removed after the epoch column is dropped.",
        replaceWith = ReplaceWith("getCompletionsForDateLocal(date.toString())"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM habit_completions WHERE completed_date = :date")
    fun getCompletionsForDate(date: Long): Flow<List<HabitCompletionEntity>>

    @Query("SELECT * FROM habit_completions WHERE completed_date_local = :date")
    fun getCompletionsForDateLocal(date: String): Flow<List<HabitCompletionEntity>>

    @Query(
        "SELECT * FROM habit_completions " +
            "WHERE habit_id = :habitId AND completed_date >= :startDate AND completed_date <= :endDate " +
            "ORDER BY completed_date ASC"
    )
    fun getCompletionsInRange(habitId: Long, startDate: Long, endDate: Long): Flow<List<HabitCompletionEntity>>

    @Query(
        "SELECT * FROM habit_completions " +
            "WHERE completed_date >= :startDate AND completed_date <= :endDate " +
            "ORDER BY completed_date ASC"
    )
    fun getAllCompletionsInRange(startDate: Long, endDate: Long): Flow<List<HabitCompletionEntity>>

    @Query(
        "SELECT COUNT(*) FROM habit_completions WHERE habit_id = :habitId AND completed_date >= :startDate AND completed_date <= :endDate"
    )
    fun getCompletionCountInRange(habitId: Long, startDate: Long, endDate: Long): Flow<Int>

    @Deprecated(
        message = "Use isCompletedOnDateLocal(habitId, date). Will be removed after epoch column drop.",
        replaceWith = ReplaceWith("isCompletedOnDateLocal(habitId, date.toString())"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT EXISTS(SELECT 1 FROM habit_completions WHERE habit_id = :habitId AND completed_date = :date)")
    fun isCompletedOnDate(habitId: Long, date: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM habit_completions WHERE habit_id = :habitId AND completed_date_local = :date)")
    fun isCompletedOnDateLocal(habitId: Long, date: String): Flow<Boolean>

    @Deprecated(
        message = "Use isCompletedOnDateLocalOnce(habitId, date). Will be removed after epoch column drop.",
        replaceWith = ReplaceWith("isCompletedOnDateLocalOnce(habitId, date.toString())"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT EXISTS(SELECT 1 FROM habit_completions WHERE habit_id = :habitId AND completed_date = :date)")
    suspend fun isCompletedOnDateOnce(habitId: Long, date: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM habit_completions WHERE habit_id = :habitId AND completed_date_local = :date)")
    suspend fun isCompletedOnDateLocalOnce(habitId: Long, date: String): Boolean

    @Deprecated(
        message = "Use getCompletionCountForDateLocalOnce(habitId, date). Will be removed after epoch column drop.",
        replaceWith = ReplaceWith("getCompletionCountForDateLocalOnce(habitId, date.toString())"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT COUNT(*) FROM habit_completions WHERE habit_id = :habitId AND completed_date = :date")
    suspend fun getCompletionCountForDateOnce(habitId: Long, date: Long): Int

    @Query("SELECT COUNT(*) FROM habit_completions WHERE habit_id = :habitId AND completed_date_local = :date")
    suspend fun getCompletionCountForDateLocalOnce(habitId: Long, date: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: HabitCompletionEntity): Long

    @Deprecated(
        message = "Use getByHabitAndDateLocal(habitId, date). Will be removed after epoch column drop.",
        replaceWith = ReplaceWith("getByHabitAndDateLocal(habitId, date.toString())"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM habit_completions WHERE habit_id = :habitId AND completed_date = :date LIMIT 1")
    suspend fun getByHabitAndDate(habitId: Long, date: Long): HabitCompletionEntity?

    @Query("SELECT * FROM habit_completions WHERE habit_id = :habitId AND completed_date_local = :date LIMIT 1")
    suspend fun getByHabitAndDateLocal(habitId: Long, date: String): HabitCompletionEntity?

    @Query("SELECT * FROM habit_completions WHERE habit_id = :habitId AND completed_date_local = :date ORDER BY completed_at DESC LIMIT 1")
    suspend fun getLatestByHabitAndDateLocal(habitId: Long, date: String): HabitCompletionEntity?

    @Deprecated(
        message = "Use deleteByHabitAndDateLocal(habitId, date). Will be removed after epoch column drop.",
        replaceWith = ReplaceWith("deleteByHabitAndDateLocal(habitId, date.toString())"),
        level = DeprecationLevel.WARNING
    )
    @Query("DELETE FROM habit_completions WHERE habit_id = :habitId AND completed_date = :date")
    suspend fun deleteByHabitAndDate(habitId: Long, date: Long)

    @Query("DELETE FROM habit_completions WHERE habit_id = :habitId AND completed_date_local = :date")
    suspend fun deleteByHabitAndDateLocal(habitId: Long, date: String)

    @Deprecated(
        message = "Use deleteLatestByHabitAndDateLocal(habitId, date). Will be removed after epoch column drop.",
        replaceWith = ReplaceWith("deleteLatestByHabitAndDateLocal(habitId, date.toString())"),
        level = DeprecationLevel.WARNING
    )
    @Query(
        "DELETE FROM habit_completions WHERE id = (" +
            "SELECT id FROM habit_completions " +
            "WHERE habit_id = :habitId AND completed_date = :date " +
            "ORDER BY completed_at DESC LIMIT 1)"
    )
    suspend fun deleteLatestByHabitAndDate(habitId: Long, date: Long)

    @Query(
        "DELETE FROM habit_completions WHERE id = (" +
            "SELECT id FROM habit_completions " +
            "WHERE habit_id = :habitId AND completed_date_local = :date " +
            "ORDER BY completed_at DESC LIMIT 1)"
    )
    suspend fun deleteLatestByHabitAndDateLocal(habitId: Long, date: String)

    @Query("SELECT * FROM habit_completions WHERE habit_id = :habitId ORDER BY completed_date DESC LIMIT 1")
    fun getLastCompletion(habitId: Long): Flow<HabitCompletionEntity?>

    /**
     * Returns the most recent completion date (one row per habit). Drives the
     * Today-screen "skip if completed within N days" visibility window — the
     * resolver indexes the result by habit_id and computes day-of-the-month
     * deltas without re-querying per habit.
     */
    @Query("SELECT habit_id AS habitId, MAX(completed_date) AS lastCompletedDate FROM habit_completions GROUP BY habit_id")
    fun getLastCompletionDatesPerHabit(): Flow<List<HabitLastCompletion>>

    @Query("SELECT * FROM habit_completions WHERE habit_id = :habitId ORDER BY completed_date DESC")
    suspend fun getCompletionsForHabitOnce(habitId: Long): List<HabitCompletionEntity>

    @Query("SELECT * FROM habit_completions WHERE habit_id = :habitId ORDER BY completed_at DESC LIMIT 1")
    suspend fun getLastCompletionOnce(habitId: Long): HabitCompletionEntity?

    @Query("SELECT * FROM habit_completions ORDER BY completed_date DESC")
    suspend fun getAllCompletionsOnce(): List<HabitCompletionEntity>

    @Query("UPDATE habit_completions SET habit_id = :newHabitId WHERE habit_id = :oldHabitId")
    suspend fun reassignHabitId(oldHabitId: Long, newHabitId: Long)

    @Query("SELECT COUNT(*) FROM habit_completions WHERE habit_id = :habitId")
    suspend fun countByHabitOnce(habitId: Long): Int

    @Query("DELETE FROM habit_completions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM habit_completions")
    suspend fun deleteAll()
}
