package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.remote.api.PomodoroCoachingRequest
import com.averycorp.prismtask.data.remote.api.PomodoroCoachingTaskRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.ui.screens.pomodoro.SessionTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A2 Pomodoro+ AI Coaching service. Wraps backend Haiku calls for the three
 * coaching surfaces: pre-session, break-activity, and session-recap.
 *
 * Returns [Result.success] on a non-blank Haiku response, [Result.failure]
 * on any network/parse error. Callers decide whether to render a fallback
 * or silently skip — the audit chose silent-skip because coaching is
 * supplementary.
 */
@Singleton
class PomodoroAICoach
@Inject
constructor(private val api: PrismTaskApi) {
    /** Pre-session coaching: given the tasks about to be worked on, suggest an approach. */
    suspend fun suggestPreSession(
        upcomingTasks: List<SessionTask>,
        sessionLengthMinutes: Int
    ): Result<String> = runBackendCall(
        PomodoroCoachingRequest(
            trigger = TRIGGER_PRE_SESSION,
            upcomingTasks = upcomingTasks.toRequestList(),
            sessionLengthMinutes = sessionLengthMinutes
        )
    )

    /** Break-time suggestion. `breakType` must be "short" or "long". */
    suspend fun suggestBreakActivity(
        elapsedMinutes: Int,
        breakType: String,
        recentSuggestions: List<String> = emptyList()
    ): Result<String> = runBackendCall(
        PomodoroCoachingRequest(
            trigger = TRIGGER_BREAK_ACTIVITY,
            elapsedMinutes = elapsedMinutes,
            breakType = breakType,
            recentSuggestions = recentSuggestions.takeIf { it.isNotEmpty() }
        )
    )

    /** Session-end recap: what got done + a "carry forward" suggestion. */
    suspend fun recapSession(
        completed: List<SessionTask>,
        started: List<SessionTask>,
        sessionDurationMinutes: Int
    ): Result<String> = runBackendCall(
        PomodoroCoachingRequest(
            trigger = TRIGGER_SESSION_RECAP,
            completedTasks = completed.toRequestList(),
            startedTasks = started.toRequestList(),
            sessionDurationMinutes = sessionDurationMinutes
        )
    )

    private suspend fun runBackendCall(request: PomodoroCoachingRequest): Result<String> = try {
        val response = api.getPomodoroCoaching(request)
        val msg = response.message.trim()
        if (msg.isEmpty()) {
            Result.failure(IllegalStateException("Empty coaching response"))
        } else {
            Result.success(msg)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun List<SessionTask>.toRequestList(): List<PomodoroCoachingTaskRequest> = map {
        PomodoroCoachingTaskRequest(
            taskId = it.taskId.toString(),
            title = it.title,
            allocatedMinutes = it.allocatedMinutes
        )
    }

    companion object {
        const val TRIGGER_PRE_SESSION = "pre_session"
        const val TRIGGER_BREAK_ACTIVITY = "break_activity"
        const val TRIGGER_SESSION_RECAP = "session_recap"
        const val BREAK_TYPE_SHORT = "short"
        const val BREAK_TYPE_LONG = "long"
    }
}
