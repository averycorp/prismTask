package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Leisure Budget v2.0 — Item 8. Session history + analytics-shaped
 * aggregation queries consumed by:
 *
 * * [com.averycorp.prismtask.domain.usecase.LeisureScorer] — for the
 *   daily-target progress + variety bonus.
 * * `LeisureScoreSection` on `TaskAnalyticsScreen` — for the 7-day
 *   sparkline + 30-day budget-hit-rate + category-variety chart.
 *
 * Aggregations are done in SQL rather than streaming all rows because
 * the dashboard surfaces a rolling window of weeks/months — letting
 * Room flatten that to a single COUNT/SUM row saves a lot of churn.
 */
@Dao
interface LeisureSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: LeisureSessionEntity): Long

    @Query("DELETE FROM leisure_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM leisure_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LeisureSessionEntity?

    @Query(
        "SELECT * FROM leisure_sessions " +
            "WHERE logged_at >= :sinceMillis AND logged_at < :untilMillis " +
            "ORDER BY logged_at DESC"
    )
    fun getInRange(sinceMillis: Long, untilMillis: Long): Flow<List<LeisureSessionEntity>>

    @Query(
        "SELECT * FROM leisure_sessions " +
            "WHERE logged_at >= :sinceMillis AND logged_at < :untilMillis"
    )
    suspend fun getInRangeOnce(
        sinceMillis: Long,
        untilMillis: Long
    ): List<LeisureSessionEntity>

    @Query(
        "SELECT COALESCE(SUM(duration_minutes), 0) FROM leisure_sessions " +
            "WHERE logged_at >= :sinceMillis AND logged_at < :untilMillis"
    )
    fun getMinutesInRange(
        sinceMillis: Long,
        untilMillis: Long
    ): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(duration_minutes), 0) FROM leisure_sessions " +
            "WHERE logged_at >= :sinceMillis AND logged_at < :untilMillis"
    )
    suspend fun getMinutesInRangeOnce(
        sinceMillis: Long,
        untilMillis: Long
    ): Int

    @Query(
        "SELECT COUNT(DISTINCT category) FROM leisure_sessions " +
            "WHERE logged_at >= :sinceMillis AND logged_at < :untilMillis"
    )
    suspend fun getDistinctCategoryCountInRange(
        sinceMillis: Long,
        untilMillis: Long
    ): Int

    @Query("SELECT * FROM leisure_sessions ORDER BY logged_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<LeisureSessionEntity>>
}
