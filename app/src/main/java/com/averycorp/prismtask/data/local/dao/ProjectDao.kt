package com.averycorp.prismtask.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

data class ProjectWithCount(
    val id: Long,
    val name: String,
    val color: String,
    val icon: String,
    val createdAt: Long,
    val updatedAt: Long,
    val taskCount: Int
)

/**
 * Aggregate row used by [com.averycorp.prismtask.data.repository.ProjectRepository.observeProjects]
 * to build a list of [com.averycorp.prismtask.domain.model.ProjectWithProgress] without a
 * per-row round trip. Milestones, tasks, and task-completion activity are
 * computed in follow-up in-memory passes; this projection keeps the SQL
 * single-query for the list screen and widget.
 */
data class ProjectAggregateRow(
    val id: Long,
    val totalMilestones: Int,
    val completedMilestones: Int,
    val upcomingMilestoneTitle: String?,
    val totalTasks: Int,
    val openTasks: Int
)

@Dao
interface ProjectDao {
    // ---------------------------------------------------------------------
    // Legacy API (v1.3 and earlier). Still in use across the app; do not
    // break the signatures here without sweeping the call sites.
    // ---------------------------------------------------------------------

    @Query("SELECT * FROM projects")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getProjectById(id: Long): Flow<ProjectEntity?>

    @Query(
        """
        SELECT p.id, p.name, p.color, p.icon,
               p.created_at AS createdAt, p.updated_at AS updatedAt,
               COUNT(t.id) AS taskCount
        FROM projects p
        LEFT JOIN tasks t ON t.project_id = p.id
        GROUP BY p.id
    """
    )
    fun getProjectWithTaskCount(): Flow<List<ProjectWithCount>>

    @Query("SELECT * FROM projects")
    suspend fun getAllProjectsOnce(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectByIdOnce(id: Long): ProjectEntity?

    /** Exact-match lookup (case-insensitive via SQLite NOCASE). */
    @Query("SELECT * FROM projects WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getProjectByNameOnce(name: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity): Long

    @Query("SELECT * FROM projects WHERE name LIKE '%' || :query || '%'")
    fun searchProjects(query: String): Flow<List<ProjectEntity>>

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()

    // ---------------------------------------------------------------------
    // v1.4.0 Projects feature API. Status-aware streams + aggregate
    // projections for list / detail / widget rendering.
    // ---------------------------------------------------------------------

    @Query("SELECT * FROM projects WHERE status = :status ORDER BY updated_at DESC")
    fun observeByStatus(status: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    /**
     * One-row-per-project aggregate. Upcoming milestone title is the earliest
     * open milestone (by `order_index`, then `id`). Task counts exclude
     * subtasks so the UI counter ("3 of 8 tasks") matches a user's mental
     * model — subtasks are accessed inside a parent task, not standalone.
     */
    @Query(
        """
        SELECT
            p.id AS id,
            (SELECT COUNT(*) FROM milestones m WHERE m.project_id = p.id) AS totalMilestones,
            (SELECT COUNT(*) FROM milestones m WHERE m.project_id = p.id AND m.is_completed = 1) AS completedMilestones,
            (SELECT m.title FROM milestones m
                WHERE m.project_id = p.id AND m.is_completed = 0
                ORDER BY m.order_index ASC, m.id ASC LIMIT 1) AS upcomingMilestoneTitle,
            (SELECT COUNT(*) FROM tasks t
                WHERE t.project_id = p.id AND t.parent_task_id IS NULL AND t.archived_at IS NULL) AS totalTasks,
            (SELECT COUNT(*) FROM tasks t
                WHERE t.project_id = p.id AND t.parent_task_id IS NULL AND t.archived_at IS NULL
                  AND t.is_completed = 0) AS openTasks
        FROM projects p
        WHERE p.id = :projectId
        """
    )
    suspend fun getAggregateRow(projectId: Long): ProjectAggregateRow?

    /**
     * Task-completion activity dates for a project. Includes subtask
     * completions whose parent task is on this project (read-time
     * inheritance). Returned as epoch millis on the `completed_date` column.
     */
    @Query(
        """
        SELECT DISTINCT tc.completed_date AS completedDate
        FROM task_completions tc
        INNER JOIN tasks t ON t.id = tc.task_id
        WHERE t.project_id = :projectId
           OR t.parent_task_id IN (SELECT id FROM tasks WHERE project_id = :projectId)
        """
    )
    suspend fun getTaskActivityDates(projectId: Long): List<Long>
}
