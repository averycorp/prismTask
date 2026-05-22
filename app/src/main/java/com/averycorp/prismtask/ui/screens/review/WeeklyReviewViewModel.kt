package com.averycorp.prismtask.ui.screens.review

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.WeeklyReviewRequest
import com.averycorp.prismtask.data.remote.api.WeeklyReviewResponse
import com.averycorp.prismtask.data.remote.api.WeeklyTaskSummary
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.WeeklyReviewRepository
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.WeeklyReviewAggregator
import com.averycorp.prismtask.domain.usecase.WeeklyReviewStats
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Sealed UI state for the Weekly Review screen.
 *
 * Parallel to the other AI ViewModels' sealed states, but with an extra
 * twist: [Error] carries the local fallback stats + narrative so the
 * screen can still render something useful when the backend is
 * unreachable. Pro gate blocks the API call, not the screen — Free users
 * always get [Success] with `backendResponse = null` and the local
 * rule-based narrative from the aggregator.
 */
sealed interface WeeklyReviewUiState {
    data object Idle : WeeklyReviewUiState
    data object Loading : WeeklyReviewUiState

    /**
     * @param thisWeek Aggregator output (local, always present).
     * @param lastWeek Aggregator output for the prior week (used by the
     *                 local narrative to compare).
     * @param localNarrative Rule-based narrative computed from the
     *                       aggregator. Shown for Free users, and also
     *                       as the visible content when the backend
     *                       call fails.
     * @param backendResponse Hybrid-endpoint response. Null for Free
     *                        users and for Pro users during a fallback.
     */
    data class Success(
        val thisWeek: WeeklyReviewStats,
        val lastWeek: WeeklyReviewStats,
        val localNarrative: WeeklyReviewNarrative,
        val backendResponse: WeeklyReviewResponse?
    ) : WeeklyReviewUiState

    /** Nothing to review and the backend returned nothing either. */
    data object Empty : WeeklyReviewUiState

    /**
     * Backend call failed. Carries the same local fallback that Free
     * users see so the screen can render "here's what we know locally"
     * with a dismissible banner explaining the AI review was unavailable.
     */
    data class Error(
        val thisWeek: WeeklyReviewStats,
        val lastWeek: WeeklyReviewStats,
        val localNarrative: WeeklyReviewNarrative,
        val message: String
    ) : WeeklyReviewUiState
}

/**
 * ViewModel for the AI Weekly Review screen (v1.4.0 V6, v1.1.0 AI
 * backend wiring).
 *
 * Flow:
 *   1. Aggregate local Room data for this week + last week.
 *   2. Build the local rule-based narrative (Free users stop here).
 *   3. If Pro: POST to /api/v1/ai/weekly-review with the completed/
 *      slipped per-task lists. The backend enriches with open tasks
 *      from Firestore and returns a Sonnet narrative.
 *   4. On success: emit Success with both local + backend content.
 *   5. On failure: emit Error with local fallback + a user-readable
 *      banner message. Log the cause so logcat shows whether it was
 *      network, 4xx, 5xx, or a parse error.
 */
@HiltViewModel
class WeeklyReviewViewModel
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val weeklyReviewRepository: WeeklyReviewRepository,
    private val api: PrismTaskApi,
    private val proFeatureGate: ProFeatureGate
) : ViewModel() {
    private val aggregator = WeeklyReviewAggregator()
    private val gson = Gson()

    private val _uiState = MutableStateFlow<WeeklyReviewUiState>(WeeklyReviewUiState.Idle)
    val uiState: StateFlow<WeeklyReviewUiState> = _uiState.asStateFlow()

    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = WeeklyReviewUiState.Loading
            val tasks = taskRepository.getAllTasksOnce()
            val thisWeek = aggregator.aggregate(tasks)
            val lastWeek = aggregator.aggregate(tasks, reference = thisWeek.weekStart - 1)
            val localNarrative = buildLocalNarrative(thisWeek, lastWeek)

            // Persist the local review (unchanged from v1 behavior) so
            // the weekly_reviews table keeps a history regardless of
            // backend availability.
            persistLocal(thisWeek, localNarrative)

            // Free users never hit the API — aggregator-only review is
            // genuinely useful, and keeping them off the API line keeps
            // our backend costs scaling with paying users. See the Pro
            // gate discussion in the v1.1.0 AI planning doc.
            if (!proFeatureGate.hasAccess(ProFeatureGate.AI_WEEKLY_REVIEW)) {
                _uiState.value = toSuccessOrEmpty(thisWeek, lastWeek, localNarrative, backendResponse = null)
                return@launch
            }

            val request = buildRequest(thisWeek)
            try {
                val response = api.getWeeklyReview(request)
                val backendIsEmpty = response.wins.isEmpty() &&
                    response.slips.isEmpty() &&
                    response.patterns.isEmpty() &&
                    response.nextWeekFocus.isEmpty() &&
                    response.narrative.isBlank()
                if (backendIsEmpty && thisWeek.completed == 0 && thisWeek.slipped == 0) {
                    _uiState.value = WeeklyReviewUiState.Empty
                } else {
                    _uiState.value = WeeklyReviewUiState.Success(
                        thisWeek = thisWeek,
                        lastWeek = lastWeek,
                        localNarrative = localNarrative,
                        backendResponse = response
                    )
                }
            } catch (e: Exception) {
                Log.w("WeeklyReviewVM", "AI weekly review fell back to local narrative", e)
                _uiState.value = WeeklyReviewUiState.Error(
                    thisWeek = thisWeek,
                    lastWeek = lastWeek,
                    localNarrative = localNarrative,
                    message = "AI review unavailable — showing local summary."
                )
            }
        }
    }

    /**
     * If completed + slipped + carryForward are all zero and there's no
     * local narrative, there's genuinely nothing to review; otherwise
     * return Success with the local narrative (Free-tier path).
     */
    private fun toSuccessOrEmpty(
        thisWeek: WeeklyReviewStats,
        lastWeek: WeeklyReviewStats,
        localNarrative: WeeklyReviewNarrative,
        backendResponse: WeeklyReviewResponse?
    ): WeeklyReviewUiState {
        if (thisWeek.completed == 0 &&
            thisWeek.slipped == 0 &&
            thisWeek.completedTasks.isEmpty() &&
            thisWeek.slippedTasks.isEmpty()
        ) {
            return WeeklyReviewUiState.Empty
        }
        return WeeklyReviewUiState.Success(
            thisWeek = thisWeek,
            lastWeek = lastWeek,
            localNarrative = localNarrative,
            backendResponse = backendResponse
        )
    }

    private fun buildRequest(thisWeek: WeeklyReviewStats): WeeklyReviewRequest {
        val zone = ZoneId.systemDefault()
        val weekStartLocal = Instant.ofEpochMilli(thisWeek.weekStart).atZone(zone).toLocalDate()
        // `weekEnd` on the aggregator is the *exclusive* end (start + 7
        // days). The backend wants inclusive Sunday → subtract 1.
        val weekEndLocal = Instant.ofEpochMilli(thisWeek.weekEnd - 1).atZone(zone).toLocalDate()

        return WeeklyReviewRequest(
            weekStart = weekStartLocal.format(DateTimeFormatter.ISO_LOCAL_DATE),
            weekEnd = weekEndLocal.format(DateTimeFormatter.ISO_LOCAL_DATE),
            completedTasks = thisWeek.completedTasks.map { it.toSummary(completed = true) },
            slippedTasks = thisWeek.slippedTasks.map { it.toSummary(completed = false) },
            // No habit / pomodoro aggregator shipped yet; leave the
            // opaque maps null so the backend treats them as "not
            // provided" rather than empty dicts.
            habitSummary = null,
            pomodoroSummary = null,
            notes = null
        )
    }

    private fun TaskEntity.toSummary(completed: Boolean): WeeklyTaskSummary {
        val completedAtLocal = completedAt
        val completedAtIso = if (completed && completedAtLocal != null) {
            Instant.ofEpochMilli(completedAtLocal).atOffset(ZoneOffset.UTC).toString()
        } else {
            null
        }
        return WeeklyTaskSummary(
            taskId = cloudId ?: id.toString(),
            title = title,
            completedAt = completedAtIso,
            priority = priority,
            eisenhowerQuadrant = eisenhowerQuadrant,
            lifeCategory = lifeCategory,
            projectId = projectId?.toString()
        )
    }

    private fun persistLocal(thisWeek: WeeklyReviewStats, narrative: WeeklyReviewNarrative) {
        val metricsJson = gson.toJson(
            SerializedMetrics(
                weekStart = thisWeek.weekStart,
                weekEnd = thisWeek.weekEnd,
                completed = thisWeek.completed,
                slipped = thisWeek.slipped,
                rescheduled = thisWeek.rescheduled,
                byCategory = thisWeek.byCategory.mapKeys { it.key.name }
            )
        )
        val narrativeJson = gson.toJson(narrative)
        viewModelScope.launch {
            weeklyReviewRepository.save(
                weekStart = thisWeek.weekStart,
                metricsJson = metricsJson,
                aiInsightsJson = narrativeJson
            )
        }
    }

    private data class SerializedMetrics(
        val weekStart: Long,
        val weekEnd: Long,
        val completed: Int,
        val slipped: Int,
        val rescheduled: Int,
        val byCategory: Map<String, Int>
    )

    /**
     * Rule-based local narrative. Preserved from v1 as the fallback for
     * Free users and for Pro-tier network failures. Pro-tier success
     * replaces this with the Sonnet-backed [WeeklyReviewResponse].
     */
    private fun buildLocalNarrative(
        thisWeek: WeeklyReviewStats,
        lastWeek: WeeklyReviewStats
    ): WeeklyReviewNarrative {
        val wins = mutableListOf<String>()
        val misses = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        if (thisWeek.completed > lastWeek.completed) {
            val delta = thisWeek.completed - lastWeek.completed
            val suffix = if (delta == 1) "" else "s"
            wins.add("Completed $delta more task$suffix than last week.")
        }
        if (thisWeek.completionRate >= 0.75f) {
            wins.add("Strong completion rate of ${(thisWeek.completionRate * 100).toInt()}%.")
        }
        val selfCareThisWeek = thisWeek.byCategory[LifeCategory.SELF_CARE] ?: 0
        if (selfCareThisWeek > 0) {
            wins.add("You made time for $selfCareThisWeek self-care task${if (selfCareThisWeek == 1) "" else "s"}.")
        }

        if (thisWeek.slipped > 0) {
            misses.add("${thisWeek.slipped} task${if (thisWeek.slipped == 1) "" else "s"} slipped — they're ready to carry forward.")
        }
        val workThisWeek = thisWeek.byCategory[LifeCategory.WORK] ?: 0
        val totalCat = thisWeek.byCategory.values.sum()
        if (totalCat > 0 && workThisWeek > totalCat / 2) {
            misses.add("Work made up the majority of your completed tasks this week.")
        }

        if (selfCareThisWeek == 0 && thisWeek.completed > 0) {
            suggestions.add("Try scheduling one self-care task for the week ahead.")
        }
        if (thisWeek.slipped > thisWeek.completed) {
            suggestions.add("You have more slipped than completed tasks — consider a lighter plan next week.")
        }
        if (wins.isEmpty()) {
            wins.add("You showed up this week — that counts.")
        }

        return WeeklyReviewNarrative(wins = wins, misses = misses, suggestions = suggestions)
    }

    @Suppress("UNUSED_PARAMETER")
    fun dismissErrorBanner() {
        // Per-session dismissal: drop Error context to a local-only
        // Success so the banner stops rendering but the local content
        // stays on screen.
        val current = _uiState.value
        if (current is WeeklyReviewUiState.Error) {
            _uiState.value = WeeklyReviewUiState.Success(
                thisWeek = current.thisWeek,
                lastWeek = current.lastWeek,
                localNarrative = current.localNarrative,
                backendResponse = null
            )
        }
    }
}

/**
 * Legacy rule-based narrative shape. Kept because the local aggregator
 * path and the persistence layer both read it; the Sonnet-backed
 * response uses [WeeklyReviewResponse] (from the API module) instead.
 */
data class WeeklyReviewNarrative(
    val wins: List<String> = emptyList(),
    val misses: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)
