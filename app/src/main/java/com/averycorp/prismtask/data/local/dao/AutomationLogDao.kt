package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.averycorp.prismtask.data.local.entity.AutomationLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationLogDao {
    @Query("SELECT * FROM automation_logs ORDER BY fired_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AutomationLogEntity>>

    @Query("SELECT * FROM automation_logs WHERE rule_id = :ruleId ORDER BY fired_at DESC LIMIT :limit")
    fun observeForRule(ruleId: Long, limit: Int = 100): Flow<List<AutomationLogEntity>>

    @Query("SELECT COUNT(*) FROM automation_logs WHERE fired_at >= :since")
    suspend fun countSince(since: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM automation_logs
        WHERE fired_at >= :since
          AND actions_executed_json LIKE '%"type":"ai.%'
        """
    )
    suspend fun countAiSince(since: Long): Int

    /**
     * Count prior firings of [ruleId] in the window that fired at least
     * one notify action against the same [taskId]. Backs the per-task
     * notify soft cap (7th engine safety mechanism, mental-health
     * guardrails Option C).
     *
     * `taskMarkerComma` and `taskMarkerBrace` are pre-built LIKE patterns
     * (`%"taskId":<n>,%` and `%"taskId":<n>}%`) — the OR covers both
     * "taskId is last field" and "taskId followed by another field"
     * shapes in `trigger_event_json`. Two patterns prevent prefix
     * collisions (e.g. `taskId:12` matching a log for `taskId:123`).
     */
    @Query(
        """
        SELECT COUNT(*) FROM automation_logs
        WHERE rule_id = :ruleId
          AND fired_at >= :since
          AND condition_passed = 1
          AND actions_executed_json LIKE '%"type":"notify"%'
          AND (trigger_event_json LIKE :taskMarkerComma
               OR trigger_event_json LIKE :taskMarkerBrace)
        """
    )
    suspend fun countNotifiesForRuleAndTaskSince(
        ruleId: Long,
        taskMarkerComma: String,
        taskMarkerBrace: String,
        since: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AutomationLogEntity): Long

    @Query("DELETE FROM automation_logs WHERE fired_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM automation_logs WHERE rule_id = :ruleId")
    suspend fun deleteForRule(ruleId: Long)
}
