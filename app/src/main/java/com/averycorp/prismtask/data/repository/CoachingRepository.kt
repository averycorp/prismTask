package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.CoachingPreferences
import com.averycorp.prismtask.data.remote.api.CoachingContext
import com.averycorp.prismtask.data.remote.api.CoachingRequest
import com.averycorp.prismtask.data.remote.api.CoachingResponse
import com.averycorp.prismtask.data.remote.api.CoachingTaskSummary
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result wrapper for coaching API calls that includes tier gating outcomes.
 */
sealed class CoachingResult {
    data class Success(val response: CoachingResponse) : CoachingResult()

    data class UpgradeRequired(val requiredTier: UserTier) : CoachingResult()

    data class FreeLimitReached(val dailyLimit: Int) : CoachingResult()

    data class Error(val message: String) : CoachingResult()
}

@Singleton
class CoachingRepository
@Inject
constructor(
    private val api: PrismTaskApi,
    private val taskDao: TaskDao,
    private val proFeatureGate: ProFeatureGate,
    private val coachingPreferences: CoachingPreferences
) {
    private fun tierString(): String = when (proFeatureGate.userTier.value) {
        UserTier.PRO -> "PRO"
        UserTier.FREE -> "FREE"
    }

    private fun formatDate(millis: Long?): String? {
        if (millis == null) return null
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    }

    private fun daysSince(millis: Long): Int {
        val elapsed = System.currentTimeMillis() - millis
        return TimeUnit.MILLISECONDS.toDays(elapsed).toInt()
    }

    // region Trigger 1: "Why am I stuck?" (Pro+)

    /**
     * Provides coaching for a stuck/overdue task. Pro+ only.
     * Free users get [CoachingResult.UpgradeRequired].
     */
    suspend fun getStuckCoaching(taskId: Long): CoachingResult {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_COACHING)) {
            return CoachingResult.UpgradeRequired(
                proFeatureGate.requiredTier(ProFeatureGate.AI_COACHING)
            )
        }

        val task = taskDao.getTaskByIdOnce(taskId) ?: return CoachingResult.Error("Task not found")
        val subtasks = taskDao.getSubtasksOnce(taskId)

        val context = CoachingContext(
            taskTitle = task.title,
            taskDescription = task.description,
            daysSinceCreation = daysSince(task.createdAt),
            dueDate = formatDate(task.dueDate),
            priority = task.priority,
            subtaskCount = subtasks.size,
            completedSubtasks = subtasks.count { it.isCompleted }
        )

        return callApi("stuck", taskId, context)
    }

    // endregion

    // region Trigger 2: Perfectionism detection (Pro+)

    /**
     * Checks if a task shows signs of perfectionism-driven stalling and
     * provides coaching. Pro+ only. Free users never see this.
     *
     * @param editCount Number of times the task has been edited without completion.
     * @param rescheduleCount Number of times the task has been rescheduled.
     */
    suspend fun getPerfectionismCoaching(
        taskId: Long,
        editCount: Int = 0,
        rescheduleCount: Int = 0
    ): CoachingResult {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_COACHING)) {
            return CoachingResult.UpgradeRequired(
                proFeatureGate.requiredTier(ProFeatureGate.AI_COACHING)
            )
        }

        val task = taskDao.getTaskByIdOnce(taskId) ?: return CoachingResult.Error("Task not found")
        val subtasks = taskDao.getSubtasksOnce(taskId)
        val subtasksAdded = subtasks.size
        val subtasksCompleted = subtasks.count { it.isCompleted }

        val reason = buildPerfectionismReason(editCount, rescheduleCount, subtasksAdded, subtasksCompleted)

        val context = CoachingContext(
            taskTitle = task.title,
            daysSinceCreation = daysSince(task.createdAt),
            editCount = editCount,
            rescheduleCount = rescheduleCount,
            subtasksAdded = subtasksAdded,
            subtasksCompleted = subtasksCompleted,
            reason = reason
        )

        return callApi("perfectionism", taskId, context)
    }

    /**
     * Determines whether a task qualifies for perfectionism detection.
     */
    fun shouldShowPerfectionismCard(
        editCount: Int,
        rescheduleCount: Int,
        subtasksAdded: Int,
        subtasksCompleted: Int
    ): Boolean = editCount >= 3 ||
        rescheduleCount >= 2 ||
        (subtasksAdded >= 2 && subtasksCompleted == 0)

    private fun buildPerfectionismReason(
        editCount: Int,
        rescheduleCount: Int,
        subtasksAdded: Int,
        subtasksCompleted: Int
    ): String = buildString {
        if (editCount >= 3) append("edited $editCount times without completion")
        if (rescheduleCount >= 2) {
            if (isNotEmpty()) append("; ")
            append("rescheduled $rescheduleCount times")
        }
        if (subtasksAdded >= 2 && subtasksCompleted == 0) {
            if (isNotEmpty()) append("; ")
            append("$subtasksAdded subtasks added but none completed")
        }
    }

    // endregion

    // region Trigger 3: Energy-adaptive daily planning (Premium only)

    /**
     * Generates an energy-adaptive daily plan. Premium only.
     * Pro users get [CoachingResult.UpgradeRequired].
     */
    suspend fun getEnergyPlan(
        energyLevel: String,
        todayTasks: List<TaskEntity>,
        overdueCount: Int,
        yesterdayCompleted: Int,
        yesterdayTotal: Int
    ): CoachingResult {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_DAILY_PLANNING)) {
            return CoachingResult.UpgradeRequired(
                proFeatureGate.requiredTier(ProFeatureGate.AI_DAILY_PLANNING)
            )
        }

        val taskSummaries = todayTasks.map { task ->
            CoachingTaskSummary(
                taskId = task.id,
                title = task.title,
                priority = task.priority,
                estimatedMinutes = task.estimatedDuration
            )
        }

        val context = CoachingContext(
            energyLevel = energyLevel,
            tasksDueToday = taskSummaries,
            overdueCount = overdueCount,
            yesterdayCompleted = yesterdayCompleted,
            yesterdayTotal = yesterdayTotal
        )

        return callApi("energy_plan", null, context)
    }

    // endregion

    // region Trigger 4: Welcome back (Premium only)

    /**
     * Generates a welcome-back message after user absence. Premium only.
     */
    suspend fun getWelcomeBack(
        daysAbsent: Int,
        overdueCount: Int,
        recentCompletions: Int
    ): CoachingResult {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_REENGAGEMENT)) {
            return CoachingResult.UpgradeRequired(
                proFeatureGate.requiredTier(ProFeatureGate.AI_REENGAGEMENT)
            )
        }

        val context = CoachingContext(
            daysAbsent = daysAbsent,
            overdueCount = overdueCount,
            recentCompletions = recentCompletions
        )

        return callApi("welcome_back", null, context)
    }

    /**
     * Returns the number of days since the user last opened the app,
     * or null if no prior record. Fires welcome-back when 3+ days.
     */
    suspend fun checkWelcomeBackEligibility(): Int? {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_REENGAGEMENT)) return null
        if (coachingPreferences.isWelcomeBackDismissedToday()) return null
        val days = coachingPreferences.getDaysSinceLastOpen() ?: return null
        return if (days >= 3) days else null
    }

    suspend fun recordAppOpen() {
        coachingPreferences.setLastAppOpen(System.currentTimeMillis())
    }

    suspend fun dismissWelcomeBack() {
        coachingPreferences.dismissWelcomeBack()
    }

    // endregion

    // region Trigger 5: Celebration (Pro+)

    /**
     * Generates a celebration message for task completion. Pro+ only.
     * Free users don't see celebrations.
     */
    suspend fun getCelebration(
        taskId: Long,
        completedSubtaskCount: Int = 0,
        totalSubtaskCount: Int = 0,
        daysOverdue: Int = 0,
        firstAfterGap: Boolean = false
    ): CoachingResult {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_COACHING)) {
            return CoachingResult.UpgradeRequired(
                proFeatureGate.requiredTier(ProFeatureGate.AI_COACHING)
            )
        }

        val task = taskDao.getTaskByIdOnce(taskId) ?: return CoachingResult.Error("Task not found")

        val context = CoachingContext(
            taskTitle = task.title,
            completedSubtaskCount = completedSubtaskCount,
            totalSubtaskCount = totalSubtaskCount,
            daysOverdue = daysOverdue,
            firstAfterGap = firstAfterGap
        )

        return callApi("celebration", taskId, context)
    }

    /**
     * Determines whether a completion event qualifies for celebration.
     */
    fun shouldCelebrate(
        completedSubtaskCount: Int,
        totalSubtaskCount: Int,
        daysOverdue: Int,
        firstAfterGap: Boolean
    ): Boolean = proFeatureGate.hasAccess(ProFeatureGate.AI_COACHING) &&
        (
            (completedSubtaskCount in 1 until totalSubtaskCount) ||
                daysOverdue >= 2 ||
                firstAfterGap
            )

    // endregion

    // region Trigger 6: Task breakdown (Free 3/day, Pro+ unlimited)

    /**
     * Breaks a task into subtasks. Free users get 3/day; Pro+ unlimited.
     * Returns [CoachingResult.FreeLimitReached] when the free daily cap is hit.
     */
    suspend fun getTaskBreakdown(
        taskId: Long,
        projectName: String? = null
    ): CoachingResult {
        val tier = proFeatureGate.userTier.value

        // Free users: check daily limit
        if (tier == UserTier.FREE) {
            if (coachingPreferences.hasReachedBreakdownLimit()) {
                return CoachingResult.FreeLimitReached(
                    CoachingPreferences.FREE_BREAKDOWN_DAILY_LIMIT
                )
            }
        }

        val task = taskDao.getTaskByIdOnce(taskId) ?: return CoachingResult.Error("Task not found")

        val context = CoachingContext(
            taskTitle = task.title,
            taskDescription = task.description,
            durationMinutes = task.estimatedDuration,
            projectName = projectName
        )

        val result = callApi("breakdown", taskId, context)

        // Increment counter on success for free users
        if (result is CoachingResult.Success && tier == UserTier.FREE) {
            coachingPreferences.incrementBreakdownCount()
        }

        return result
    }

    /**
     * Returns the number of free breakdowns remaining today.
     * Pro+ users get Int.MAX_VALUE (unlimited).
     */
    suspend fun getRemainingBreakdowns(): Int = if (proFeatureGate.isPro()) {
        Int.MAX_VALUE
    } else {
        coachingPreferences.getRemainingBreakdowns()
    }

    /**
     * Heuristic: should the app suggest a task breakdown?
     * True when the task has no subtasks and either has an estimated duration
     * over 30 minutes or the title contains vague words.
     */
    fun shouldSuggestBreakdown(task: TaskEntity, subtaskCount: Int): Boolean {
        if (subtaskCount > 0) return false
        if ((task.estimatedDuration ?: 0) > 30) return true
        val vague = listOf("finish", "complete", "work on", "figure out", "do", "handle", "deal with")
        return vague.any { task.title.lowercase().contains(it) }
    }

    // endregion

    // region Energy check-in state

    suspend fun shouldShowEnergyCheckIn(todayTaskCount: Int): Boolean {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_DAILY_PLANNING)) return false
        if (todayTaskCount < 2) return false
        return coachingPreferences.shouldShowEnergyCheckIn()
    }

    suspend fun getTodayEnergyLevel(): String? = coachingPreferences.getTodayEnergyLevel()

    suspend fun setTodayEnergyLevel(level: String) {
        coachingPreferences.setTodayEnergyLevel(level)
    }

    // endregion

    // region Internal

    private suspend fun callApi(
        trigger: String,
        taskId: Long?,
        context: CoachingContext
    ): CoachingResult = try {
        val request = CoachingRequest(
            trigger = trigger,
            taskId = taskId,
            context = context,
            tier = tierString()
        )
        val response = api.getCoaching(request)
        CoachingResult.Success(response)
    } catch (e: retrofit2.HttpException) {
        if (e.code() == 403) {
            CoachingResult.UpgradeRequired(UserTier.PRO)
        } else {
            CoachingResult.Error("Coach is unavailable right now")
        }
    } catch (e: Exception) {
        CoachingResult.Error("Couldn't reach coach")
    }

    // endregion
}
