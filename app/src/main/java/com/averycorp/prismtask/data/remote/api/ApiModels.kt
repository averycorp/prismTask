package com.averycorp.prismtask.data.remote.api

import com.google.gson.annotations.SerializedName

/**
 * Data models exchanged with the PrismTask FastAPI backend.
 *
 * Field names use snake_case to match the backend JSON contract; Kotlin
 * property names use camelCase via [SerializedName].
 *
 * Sections below are delineated with `// region` / `// endregion` markers.
 */

data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class FirebaseTokenRequest(
    @SerializedName("firebase_token") val firebaseToken: String,
    val name: String? = null
)

data class RefreshRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String
)

data class UserInfoResponse(
    val id: Int,
    val email: String,
    val name: String,
    val tier: String = "FREE",
    @SerializedName("is_admin") val isAdmin: Boolean = false,
    @SerializedName("effective_tier") val effectiveTier: String = "FREE"
)

data class UpdateTierRequest(
    val tier: String,
    @SerializedName("purchase_token") val purchaseToken: String? = null,
    @SerializedName("product_id") val productId: String? = null
)

data class DeletionRequest(
    @SerializedName("initiated_from") val initiatedFrom: String = "android"
)

data class BetaRedeemRequest(
    val code: String
)

data class BetaRedeemResponse(
    val granted: Boolean,
    @SerializedName("pro_until") val proUntil: String? = null
)

data class DeletionStatusResponse(
    @SerializedName("deletion_pending_at") val deletionPendingAt: String? = null,
    @SerializedName("deletion_scheduled_for") val deletionScheduledFor: String? = null,
    @SerializedName("deletion_initiated_from") val deletionInitiatedFrom: String? = null,
    @SerializedName("grace_period_days") val gracePeriodDays: Int = 30
) {
    val isPending: Boolean get() = deletionPendingAt != null
}

// endregion

// region Tasks

data class ParseRequest(
    val text: String,
    /**
     * User's Start-of-Day hour (0..23). When supplied, the backend
     * resolves "today"/"tomorrow" relative to the user's *logical* day
     * — matching how habits, streaks, and the on-device parser behave.
     * Null falls back to calendar today on the server side, which keeps
     * older Android builds working unchanged.
     */
    @SerializedName("start_of_day_hour") val startOfDayHour: Int? = null,
    @SerializedName("start_of_day_minute") val startOfDayMinute: Int? = null
)

data class ParsedTaskResponse(
    val title: String,
    @SerializedName("project_suggestion") val projectSuggestion: String?,
    @SerializedName("tag_suggestions") val tagSuggestions: List<String>?,
    @SerializedName("due_date") val dueDate: String?,
    @SerializedName("due_time") val dueTime: String?,
    val priority: Int?,
    @SerializedName("recurrence_hint") val recurrenceHint: String?,
    val confidence: Double?
)

data class ExtractFromTextRequest(
    val text: String,
    val source: String? = null
)

data class ExtractedTaskCandidateResponse(
    val title: String,
    @SerializedName("suggested_due_date") val suggestedDueDate: String? = null,
    @SerializedName("suggested_priority") val suggestedPriority: Int = 0,
    @SerializedName("suggested_project") val suggestedProject: String? = null,
    val confidence: Float = 0.5f
)

data class ExtractFromTextResponse(
    val tasks: List<ExtractedTaskCandidateResponse> = emptyList()
)

// endregion

// region App version

data class VersionResponse(
    @SerializedName("version_code") val versionCode: Int,
    @SerializedName("version_name") val versionName: String,
    @SerializedName("release_notes") val releaseNotes: String?,
    @SerializedName("apk_url") val apkUrl: String,
    @SerializedName("apk_size_bytes") val apkSizeBytes: Long,
    val sha256: String?,
    @SerializedName("is_mandatory") val isMandatory: Boolean
)

// endregion

// region AI Productivity

data class EisenhowerRequest(
    // Backend now expects Firestore document IDs as strings (task_ids: list[str]).
    @SerializedName("task_ids") val taskIds: List<String>? = null
)

data class EisenhowerCategorization(
    // String to match Firestore document IDs echoed back by the server.
    @SerializedName("task_id") val taskId: String,
    val quadrant: String,
    val reason: String
)

data class EisenhowerSummary(
    @SerializedName("Q1") val q1: Int = 0,
    @SerializedName("Q2") val q2: Int = 0,
    @SerializedName("Q3") val q3: Int = 0,
    @SerializedName("Q4") val q4: Int = 0
)

data class EisenhowerResponse(
    val categorizations: List<EisenhowerCategorization>,
    val summary: EisenhowerSummary
)

/**
 * Single-task text-based Eisenhower classification. Unlike the batch endpoint
 * which loads tasks from server storage by id, this accepts raw task fields so
 * it can run immediately on task creation before the local row has been synced.
 */
data class EisenhowerClassifyTextRequest(
    val title: String,
    val description: String? = null,
    @SerializedName("due_date") val dueDate: String? = null,
    val priority: Int = 0
)

data class EisenhowerClassifyTextResponse(
    val quadrant: String,
    val reason: String
)

/**
 * Single-task text-based Work-Life Balance category classification. Invoked
 * from the OrganizeTab "Auto" button — the on-device keyword
 * [com.averycorp.prismtask.domain.usecase.LifeCategoryClassifier] runs first
 * for instant feedback; this AI path overwrites the on-device guess when the
 * remote call succeeds.
 */
data class LifeCategoryClassifyTextRequest(
    val title: String,
    val description: String? = null
)

data class LifeCategoryClassifyTextResponse(
    val category: String,
    val reason: String
)

data class PomodoroRequest(
    @SerializedName("available_minutes") val availableMinutes: Int = 120,
    @SerializedName("session_length") val sessionLength: Int = 25,
    @SerializedName("break_length") val breakLength: Int = 5,
    @SerializedName("long_break_length") val longBreakLength: Int = 15,
    @SerializedName("focus_preference") val focusPreference: String = "balanced"
)

data class SessionTaskResponse(
    // Firestore document ID (alphanumeric). Resolved to a local Long task id
    // via TaskDao.getIdByCloudId at the ViewModel boundary.
    @SerializedName("task_id") val taskId: String,
    val title: String,
    @SerializedName("allocated_minutes") val allocatedMinutes: Int
)

data class PomodoroSessionResponse(
    @SerializedName("session_number") val sessionNumber: Int,
    val tasks: List<SessionTaskResponse>,
    val rationale: String
)

data class SkippedTaskResponse(
    @SerializedName("task_id") val taskId: String,
    val reason: String
)

data class PomodoroResponse(
    val sessions: List<PomodoroSessionResponse>,
    @SerializedName("total_sessions") val totalSessions: Int,
    @SerializedName("total_work_minutes") val totalWorkMinutes: Int,
    @SerializedName("total_break_minutes") val totalBreakMinutes: Int,
    @SerializedName("skipped_tasks") val skippedTasks: List<SkippedTaskResponse> = emptyList()
)

// A2 Pomodoro+ AI Coaching — pre-session / break-activity / session-recap.

data class PomodoroCoachingTaskRequest(
    @SerializedName("task_id") val taskId: String? = null,
    val title: String,
    @SerializedName("allocated_minutes") val allocatedMinutes: Int? = null
)

data class PomodoroCoachingRequest(
    /** One of: "pre_session", "break_activity", "session_recap". */
    val trigger: String,
    @SerializedName("upcoming_tasks") val upcomingTasks: List<PomodoroCoachingTaskRequest>? = null,
    @SerializedName("session_length_minutes") val sessionLengthMinutes: Int? = null,
    @SerializedName("elapsed_minutes") val elapsedMinutes: Int? = null,
    @SerializedName("break_type") val breakType: String? = null,
    @SerializedName("recent_suggestions") val recentSuggestions: List<String>? = null,
    @SerializedName("completed_tasks") val completedTasks: List<PomodoroCoachingTaskRequest>? = null,
    @SerializedName("started_tasks") val startedTasks: List<PomodoroCoachingTaskRequest>? = null,
    @SerializedName("session_duration_minutes") val sessionDurationMinutes: Int? = null
)

data class PomodoroCoachingResponse(
    val message: String
)

// endregion

// region AI Daily Briefing

data class DailyBriefingRequest(
    val date: String? = null
)

data class BriefingPriorityResponse(
    // Firestore document ID (alphanumeric). Resolved to a local Long task id
    // via TaskDao.getIdByCloudId at the ViewModel boundary.
    @SerializedName("task_id") val taskId: String,
    val title: String,
    val reason: String
)

data class SuggestedTaskResponse(
    // Firestore document ID (alphanumeric). Resolved to a local Long task id
    // via TaskDao.getIdByCloudId at the ViewModel boundary.
    @SerializedName("task_id") val taskId: String,
    val title: String,
    @SerializedName("suggested_time") val suggestedTime: String,
    val reason: String
)

data class DailyBriefingResponse(
    val greeting: String,
    @SerializedName("top_priorities") val topPriorities: List<BriefingPriorityResponse>,
    @SerializedName("heads_up") val headsUp: List<String> = emptyList(),
    @SerializedName("suggested_order") val suggestedOrder: List<SuggestedTaskResponse>,
    @SerializedName("habit_reminders") val habitReminders: List<String> = emptyList(),
    @SerializedName("day_type") val dayType: String
)

// endregion

// region AI Weekly Plan

data class WeeklyPlanPreferencesRequest(
    @SerializedName("work_days") val workDays: List<String> = listOf("MO", "TU", "WE", "TH", "FR"),
    @SerializedName("focus_hours_per_day") val focusHoursPerDay: Int = 6,
    @SerializedName("prefer_front_loading") val preferFrontLoading: Boolean = true
)

data class WeeklyPlanRequest(
    @SerializedName("week_start") val weekStart: String? = null,
    val preferences: WeeklyPlanPreferencesRequest = WeeklyPlanPreferencesRequest()
)

data class PlannedTaskResponse(
    // Firestore document ID (alphanumeric). Resolved to a local Long task id
    // via TaskDao.getIdByCloudId at the ViewModel boundary.
    @SerializedName("task_id") val taskId: String,
    val title: String,
    @SerializedName("suggested_time") val suggestedTime: String,
    @SerializedName("duration_minutes") val durationMinutes: Int,
    val reason: String
)

data class DayPlanResponse(
    val date: String,
    val tasks: List<PlannedTaskResponse>,
    @SerializedName("total_hours") val totalHours: Double,
    @SerializedName("calendar_events") val calendarEvents: List<String> = emptyList(),
    val habits: List<String> = emptyList()
)

data class UnscheduledTaskResponse(
    // Firestore document ID (alphanumeric). Resolved to a local Long task id
    // via TaskDao.getIdByCloudId at the ViewModel / use-case boundary. Shared
    // by Weekly Plan and Time-Block responses.
    @SerializedName("task_id") val taskId: String,
    val title: String,
    val reason: String
)

data class WeeklyPlanResponse(
    val plan: Map<String, DayPlanResponse>,
    val unscheduled: List<UnscheduledTaskResponse> = emptyList(),
    @SerializedName("week_summary") val weekSummary: String,
    val tips: List<String> = emptyList()
)

// endregion

// region AI Weekly Review (schema v2 — hybrid: client-provided task lists
// + server-side Firestore enrichment)

/**
 * Per-task summary sent to the backend for a weekly review. Matches the
 * backend WeeklyTaskSummary schema. Task IDs are strings because they
 * originate from Firestore.
 */
data class WeeklyTaskSummary(
    @SerializedName("task_id") val taskId: String,
    val title: String,
    // ISO-8601 datetime; null for slipped tasks.
    @SerializedName("completed_at") val completedAt: String? = null,
    val priority: Int,
    @SerializedName("eisenhower_quadrant") val eisenhowerQuadrant: String? = null,
    @SerializedName("life_category") val lifeCategory: String? = null,
    @SerializedName("task_mode") val taskMode: String? = null,
    @SerializedName("cognitive_load") val cognitiveLoad: String? = null,
    @SerializedName("project_id") val projectId: String? = null
)

data class WeeklyReviewRequest(
    // ISO dates. Backend rejects if end < start or span > 14 days.
    @SerializedName("week_start") val weekStart: String,
    @SerializedName("week_end") val weekEnd: String,
    @SerializedName("completed_tasks") val completedTasks: List<WeeklyTaskSummary> = emptyList(),
    @SerializedName("slipped_tasks") val slippedTasks: List<WeeklyTaskSummary> = emptyList(),
    // Opaque pass-through maps; the backend forwards them into the prompt
    // verbatim. Keep flexible so the client controls the shape.
    @SerializedName("habit_summary") val habitSummary: Map<String, @JvmSuppressWildcards Any?>? = null,
    @SerializedName("pomodoro_summary") val pomodoroSummary: Map<String, @JvmSuppressWildcards Any?>? = null,
    val notes: String? = null
)

data class WeeklyReviewResponse(
    @SerializedName("week_start") val weekStart: String,
    @SerializedName("week_end") val weekEnd: String,
    val wins: List<String> = emptyList(),
    val slips: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    @SerializedName("next_week_focus") val nextWeekFocus: List<String> = emptyList(),
    val narrative: String = ""
)

// endregion

// region AI Time Block

data class TimeBlockTaskSignal(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("eisenhower_quadrant") val eisenhowerQuadrant: String? = null,
    @SerializedName("estimated_pomodoro_sessions") val estimatedPomodoroSessions: Int? = null,
    @SerializedName("estimated_duration_minutes") val estimatedDurationMinutes: Int? = null,
    // "recorded" when sessions come from a persisted Pomodoro log;
    // "estimated_from_duration" when derived from estimatedDuration.
    @SerializedName("pomodoro_source") val pomodoroSource: String? = null
)

data class TimeBlockExistingBlock(
    val date: String,
    val start: String,
    val end: String,
    val title: String,
    // "task" for a PrismTask block, "pomodoro" for a Pomodoro session.
    val source: String,
    @SerializedName("task_id") val taskId: String? = null
)

data class TimeBlockRequest(
    val date: String? = null,
    @SerializedName("day_start") val dayStart: String = "09:00",
    @SerializedName("day_end") val dayEnd: String = "18:00",
    @SerializedName("block_size_minutes") val blockSizeMinutes: Int = 30,
    @SerializedName("include_breaks") val includeBreaks: Boolean = true,
    @SerializedName("break_frequency_minutes") val breakFrequencyMinutes: Int = 90,
    @SerializedName("break_duration_minutes") val breakDurationMinutes: Int = 15,
    // v1.4.40: horizon selector. 1 = today, 2 = today+tomorrow, 7 = 7-day window.
    @SerializedName("horizon_days") val horizonDays: Int = 1,
    @SerializedName("task_signals") val taskSignals: List<TimeBlockTaskSignal> = emptyList(),
    @SerializedName("existing_blocks") val existingBlocks: List<TimeBlockExistingBlock> = emptyList()
)

data class ScheduleBlockResponse(
    val start: String,
    val end: String,
    val type: String,
    // Firestore document ID (alphanumeric). Resolved to a local Long task id
    // via TaskDao.getIdByCloudId in AiTimeBlockUseCase. Null for non-task
    // blocks (events, breaks).
    @SerializedName("task_id") val taskId: String?,
    val title: String,
    val reason: String,
    // v1.4.40: ISO date of the day this block belongs to. Null on legacy
    // single-day responses; callers should default to the request's ``date``.
    val date: String? = null
)

data class TimeBlockStatsResponse(
    @SerializedName("total_work_minutes") val totalWorkMinutes: Int,
    @SerializedName("total_break_minutes") val totalBreakMinutes: Int,
    @SerializedName("total_free_minutes") val totalFreeMinutes: Int,
    @SerializedName("tasks_scheduled") val tasksScheduled: Int,
    @SerializedName("tasks_deferred") val tasksDeferred: Int
)

data class TimeBlockResponse(
    val schedule: List<ScheduleBlockResponse>,
    @SerializedName("unscheduled_tasks") val unscheduledTasks: List<UnscheduledTaskResponse> = emptyList(),
    val stats: TimeBlockStatsResponse,
    // v1.4.40: the Android client must treat every time-block response as
    // a preview and surface an explicit Approve/Cancel before writing.
    val proposed: Boolean = true,
    @SerializedName("horizon_days") val horizonDays: Int = 1
)

// endregion

// region AI Batch Ops (A2 — pulled-from-H)

data class BatchTaskContext(
    val id: String,
    val title: String,
    @SerializedName("due_date") val dueDate: String? = null,
    @SerializedName("scheduled_start_time") val scheduledStartTime: String? = null,
    val priority: Int = 0,
    @SerializedName("project_id") val projectId: String? = null,
    @SerializedName("project_name") val projectName: String? = null,
    val tags: List<String> = emptyList(),
    @SerializedName("life_category") val lifeCategory: String? = null,
    @SerializedName("task_mode") val taskMode: String? = null,
    @SerializedName("cognitive_load") val cognitiveLoad: String? = null,
    @SerializedName("is_completed") val isCompleted: Boolean = false
)

data class BatchHabitContext(
    val id: String,
    val name: String,
    @SerializedName("is_archived") val isArchived: Boolean = false
)

data class BatchProjectContext(
    val id: String,
    val name: String,
    val status: String? = null
)

data class BatchMedicationContext(
    val id: String,
    val name: String,
    @SerializedName("display_label") val displayLabel: String? = null
)

data class BatchUserContext(
    val today: String,
    val timezone: String = "UTC",
    val tasks: List<BatchTaskContext> = emptyList(),
    val habits: List<BatchHabitContext> = emptyList(),
    val projects: List<BatchProjectContext> = emptyList(),
    val medications: List<BatchMedicationContext> = emptyList(),
    /**
     * Phrase → medication entity_id pairs the client has already resolved
     * deterministically via `MedicationNameMatcher`. The backend treats
     * these as authoritative: if a mutation references one of these phrases
     * the entity_id must come from this map, and the phrase is never flagged
     * as ambiguous. Empty when the local matcher returned NoMatch / Ambiguous.
     */
    @SerializedName("committed_medication_matches")
    val committedMedicationMatches: Map<String, String> = emptyMap(),
    /**
     * Phrases the local matcher classified as ambiguous (≥2 candidate meds
     * share a name). Backend appends these to its `ambiguous_entities`
     * response so the client banner / picker always surfaces them, even if
     * Haiku decided the phrase was clear.
     */
    @SerializedName("forced_ambiguous_phrases")
    val forcedAmbiguousPhrases: List<ForcedAmbiguousPhrase> = emptyList()
)

data class ForcedAmbiguousPhrase(
    val phrase: String,
    @SerializedName("candidate_entity_type") val candidateEntityType: String = "MEDICATION",
    @SerializedName("candidate_entity_ids") val candidateEntityIds: List<String> = emptyList()
)

data class BatchParseRequest(
    @SerializedName("command_text") val commandText: String,
    @SerializedName("user_context") val userContext: BatchUserContext
)

data class ProposedMutationResponse(
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("mutation_type") val mutationType: String,
    @SerializedName("proposed_new_values") val proposedNewValues: Map<String, @JvmSuppressWildcards Any?> = emptyMap(),
    @SerializedName("human_readable_description") val humanReadableDescription: String
)

data class AmbiguousEntityHintResponse(
    val phrase: String,
    @SerializedName("candidate_entity_type") val candidateEntityType: String,
    @SerializedName("candidate_entity_ids") val candidateEntityIds: List<String> = emptyList(),
    val note: String? = null
)

data class BatchParseResponse(
    val mutations: List<ProposedMutationResponse> = emptyList(),
    val confidence: Float = 1.0f,
    @SerializedName("ambiguous_entities") val ambiguousEntities: List<AmbiguousEntityHintResponse> = emptyList(),
    val proposed: Boolean = true
)

// endregion

// region AI Chat

/**
 * One prior turn forwarded by the client so the AI has multi-turn memory.
 * Role is "user" or "assistant"; the backend rejects anything else.
 */
data class ChatHistoryEntry(
    val role: String,
    val content: String
)

/**
 * Snapshot of the task the user is talking about, sent when chat is
 * opened from a specific task. Without this the AI only sees the opaque
 * [taskContextId] integer it cannot dereference.
 */
data class ChatTaskContext(
    val title: String,
    val description: String? = null,
    @SerializedName("due_date") val dueDate: String? = null,
    val priority: Int? = null,
    @SerializedName("project_name") val projectName: String? = null,
    @SerializedName("is_completed") val isCompleted: Boolean? = null
)

data class ChatRequest(
    val message: String,
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("task_context_id") val taskContextId: Long? = null,
    @SerializedName("task_context") val taskContext: ChatTaskContext? = null,
    val history: List<ChatHistoryEntry> = emptyList()
)

data class ChatActionResponse(
    val type: String,
    @SerializedName("task_id") val taskId: String? = null,
    @SerializedName("task_ids") val taskIds: List<String>? = null,
    val to: String? = null,
    val subtasks: List<String>? = null,
    val minutes: Int? = null,
    val title: String? = null,
    val due: String? = null,
    val priority: String? = null,
    // B.3 (F8 follow-on): rich create_task fields. The backend prompt
    // emits these only when the user named or strongly implied them;
    // any subset is valid.
    val description: String? = null,
    val tags: List<String>? = null,
    val project: String? = null
)

data class ChatTokensUsed(
    val input: Int,
    val output: Int
)

data class ChatResponse(
    val message: String,
    val actions: List<ChatActionResponse> = emptyList(),
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("tokens_used") val tokensUsed: ChatTokensUsed? = null
)

// region AI Evening Summary

data class EveningSummaryRequest(
    @SerializedName("completed_tasks") val completedTasks: List<String>,
    @SerializedName("remaining_count") val remainingCount: Int,
    @SerializedName("habits_done") val habitsDone: Int,
    @SerializedName("habits_total") val habitsTotal: Int,
    @SerializedName("completed_overdue") val completedOverdue: Boolean,
    @SerializedName("completed_stalled") val completedStalled: Boolean
)

data class EveningSummaryResponse(
    val summary: String
)

// endregion

// region AI Re-engagement Nudge

data class ReengagementRequest(
    @SerializedName("days_absent") val daysAbsent: Int,
    @SerializedName("last_task_title") val lastTaskTitle: String?,
    @SerializedName("total_pending") val totalPending: Int
)

data class ReengagementResponse(
    val nudge: String
)

// endregion

// region AI Coaching

data class CoachingTaskSummary(
    @SerializedName("task_id") val taskId: Long,
    val title: String,
    val priority: Int,
    @SerializedName("estimated_minutes") val estimatedMinutes: Int? = null
)

data class CoachingContext(
    // Task-scoped fields
    @SerializedName("task_title") val taskTitle: String? = null,
    @SerializedName("task_description") val taskDescription: String? = null,
    @SerializedName("days_since_creation") val daysSinceCreation: Int? = null,
    @SerializedName("due_date") val dueDate: String? = null,
    val priority: Int? = null,
    @SerializedName("subtask_count") val subtaskCount: Int? = null,
    @SerializedName("completed_subtasks") val completedSubtasks: Int? = null,
    // Perfectionism-scoped fields
    @SerializedName("edit_count") val editCount: Int? = null,
    @SerializedName("reschedule_count") val rescheduleCount: Int? = null,
    @SerializedName("subtasks_added") val subtasksAdded: Int? = null,
    @SerializedName("subtasks_completed") val subtasksCompleted: Int? = null,
    val reason: String? = null,
    // Energy plan fields
    @SerializedName("energy_level") val energyLevel: String? = null,
    @SerializedName("tasks_due_today") val tasksDueToday: List<CoachingTaskSummary>? = null,
    @SerializedName("overdue_count") val overdueCount: Int? = null,
    @SerializedName("yesterday_completed") val yesterdayCompleted: Int? = null,
    @SerializedName("yesterday_total") val yesterdayTotal: Int? = null,
    // Welcome back fields
    @SerializedName("days_absent") val daysAbsent: Int? = null,
    @SerializedName("recent_completions") val recentCompletions: Int? = null,
    // Celebration fields
    @SerializedName("completed_subtask_count") val completedSubtaskCount: Int? = null,
    @SerializedName("total_subtask_count") val totalSubtaskCount: Int? = null,
    @SerializedName("days_overdue") val daysOverdue: Int? = null,
    @SerializedName("first_after_gap") val firstAfterGap: Boolean? = null,
    // Breakdown fields
    @SerializedName("duration_minutes") val durationMinutes: Int? = null,
    @SerializedName("project_name") val projectName: String? = null
)

data class CoachingRequest(
    val trigger: String,
    @SerializedName("task_id") val taskId: Long? = null,
    val context: CoachingContext,
    val tier: String
)

data class CoachingResponse(
    val message: String? = null,
    val subtasks: List<String>? = null
)

// endregion

// region Export / Import

data class ImportResponse(
    @SerializedName("tasks_imported") val tasksImported: Int,
    @SerializedName("projects_imported") val projectsImported: Int,
    @SerializedName("tags_imported") val tagsImported: Int,
    @SerializedName("habits_imported") val habitsImported: Int,
    val mode: String
)

data class BugReportMirrorResponse(
    val id: String,
    val status: String
)

data class AdminBugReportResponse(
    val id: Int,
    @SerializedName("report_id") val reportId: String,
    @SerializedName("user_id") val userId: Int? = null,
    val category: String,
    val description: String,
    val severity: String,
    val steps: String = "[]",
    @SerializedName("screenshot_uris") val screenshotUris: String = "[]",
    @SerializedName("device_model") val deviceModel: String = "",
    @SerializedName("device_manufacturer") val deviceManufacturer: String = "",
    @SerializedName("android_version") val androidVersion: Int = 0,
    @SerializedName("app_version") val appVersion: String = "",
    @SerializedName("app_version_code") val appVersionCode: Int = 0,
    @SerializedName("build_type") val buildType: String = "",
    @SerializedName("user_tier") val userTier: String = "",
    @SerializedName("current_screen") val currentScreen: String = "",
    @SerializedName("task_count") val taskCount: Int = 0,
    @SerializedName("habit_count") val habitCount: Int = 0,
    @SerializedName("available_ram_mb") val availableRamMb: Int = 0,
    @SerializedName("free_storage_mb") val freeStorageMb: Int = 0,
    @SerializedName("network_type") val networkType: String = "",
    @SerializedName("battery_percent") val batteryPercent: Int = 0,
    @SerializedName("is_charging") val isCharging: Boolean = false,
    val status: String,
    @SerializedName("admin_notes") val adminNotes: String? = null,
    @SerializedName("diagnostic_log") val diagnosticLog: String? = null,
    @SerializedName("submitted_via") val submittedVia: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

data class BugReportStatusUpdateRequest(
    val status: String,
    @SerializedName("admin_notes") val adminNotes: String? = null
)

// endregion

// region AI Import Parse

data class ParseImportRequest(
    val content: String
)

data class ParsedImportItemResponse(
    val title: String,
    val description: String? = null,
    val dueDate: String? = null,
    val priority: Int = 0,
    val completed: Boolean = false,
    val subtasks: List<ParsedImportItemResponse> = emptyList()
)

data class ParseImportResponse(
    val name: String? = null,
    val items: List<ParsedImportItemResponse>
)

// endregion

// region Syllabus Import

data class SyllabusTaskResponse(
    val title: String,
    @SerializedName("due_date") val dueDate: String? = null,
    @SerializedName("due_time") val dueTime: String? = null,
    val type: String = "other",
    val notes: String? = null
)

data class SyllabusEventResponse(
    val title: String,
    val date: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    val location: String? = null
)

data class SyllabusRecurringItemResponse(
    val title: String,
    @SerializedName("day_of_week") val dayOfWeek: String,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    val location: String? = null,
    @SerializedName("recurrence_end_date") val recurrenceEndDate: String? = null
)

data class SyllabusParseResponse(
    @SerializedName("course_name") val courseName: String,
    val tasks: List<SyllabusTaskResponse> = emptyList(),
    val events: List<SyllabusEventResponse> = emptyList(),
    @SerializedName("recurring_schedule") val recurringSchedule: List<SyllabusRecurringItemResponse> = emptyList()
)

data class SyllabusConfirmRequest(
    @SerializedName("course_name") val courseName: String = "My Course",
    val tasks: List<SyllabusTaskResponse> = emptyList(),
    val events: List<SyllabusEventResponse> = emptyList(),
    @SerializedName("recurring_schedule") val recurringSchedule: List<SyllabusRecurringItemResponse> = emptyList()
)

data class SyllabusConfirmResponse(
    @SerializedName("tasks_created") val tasksCreated: Int = 0,
    @SerializedName("events_created") val eventsCreated: Int = 0,
    @SerializedName("recurring_created") val recurringCreated: Int = 0
)

// endregion

// region AI Checklist Parse

data class ParseChecklistRequest(
    val content: String
)

data class ParsedChecklistCourseResponse(
    val code: String,
    val name: String
)

data class ParsedChecklistProjectResponse(
    val name: String,
    val color: String,
    val icon: String
)

data class ParsedChecklistTagResponse(
    val name: String,
    val color: String? = null
)

data class ParsedChecklistTaskResponse(
    val title: String,
    val description: String? = null,
    val dueDate: String? = null,
    val priority: Int = 0,
    val completed: Boolean = false,
    val tags: List<String> = emptyList(),
    val estimatedMinutes: Int? = null,
    // F.8: refs phases[].name when source groups task under a phase.
    val phaseName: String? = null,
    val subtasks: List<ParsedChecklistTaskResponse> = emptyList()
)

data class ParsedProjectPhaseResponse(
    val name: String,
    val description: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val orderIndex: Int = 0
)

data class ParsedProjectRiskResponse(
    val title: String,
    val description: String? = null,
    val level: String = "MEDIUM"
)

data class ParsedExternalAnchorResponse(
    val title: String,
    val type: String = "calendar_deadline",
    val phaseName: String? = null,
    val targetDate: String? = null
)

data class ParsedTaskDependencyResponse(
    val blockerTitle: String,
    val blockedTitle: String
)

data class ParseChecklistResponse(
    val course: ParsedChecklistCourseResponse,
    val project: ParsedChecklistProjectResponse,
    val tags: List<ParsedChecklistTagResponse>,
    val tasks: List<ParsedChecklistTaskResponse>,
    // F.8 project-import extensions. Default empty so existing schoolwork
    // callers (which ignore these) are unaffected. Project-import callers
    // read them when populated.
    val phases: List<ParsedProjectPhaseResponse> = emptyList(),
    val risks: List<ParsedProjectRiskResponse> = emptyList(),
    val externalAnchors: List<ParsedExternalAnchorResponse> = emptyList(),
    val taskDependencies: List<ParsedTaskDependencyResponse> = emptyList()
)

// endregion

// region Habit correlations (Phase I)

/**
 * Per-habit correlation entry from `/analytics/habit-correlations`.
 * Mirrors `backend/app/schemas/analytics.py::HabitCorrelation`.
 */
data class HabitCorrelationItem(
    val habit: String,
    @SerializedName("done_productivity") val doneProductivity: Double,
    @SerializedName("not_done_productivity") val notDoneProductivity: Double,
    /** "positive", "negative", "neutral", or weak variants. */
    val correlation: String,
    val interpretation: String
)

data class HabitCorrelationsResponse(
    val correlations: List<HabitCorrelationItem>,
    @SerializedName("top_insight") val topInsight: String,
    val recommendation: String
)

// endregion

// region Automation action AI (A7)
//
// `ai.complete` and `ai.summarize` automation action handlers route through
// these schemas. Routes live under the existing `/ai/` prefix so they
// inherit `AiFeatureGateInterceptor`'s 451 short-circuit when the master
// AI toggle is off — no `AI_PATH_PREFIXES` update required.

data class AutomationCompleteRequest(
    val prompt: String,
    val context: Map<String, @JvmSuppressWildcards Any?>? = null
)

data class AutomationCompleteResponse(
    val text: String
)

data class AutomationSummarizeRequest(
    val scope: String,
    @SerializedName("max_items") val maxItems: Int = 50,
    val context: Map<String, @JvmSuppressWildcards Any?>? = null
)

data class AutomationSummarizeResponse(
    val summary: String
)

// endregion
