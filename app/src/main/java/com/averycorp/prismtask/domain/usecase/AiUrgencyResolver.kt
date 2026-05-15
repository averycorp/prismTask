package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.UrgencyWeights
import com.averycorp.prismtask.data.preferences.UrgencyWindows
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.UrgencyClassifier
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a per-task urgency score using the AI batch endpoint when
 * the user is Pro and has the per-feature toggle on; otherwise falls
 * back to the deterministic on-device [UrgencyScorer] formula.
 *
 * The resolver itself is stateless — callers (typically a ViewModel)
 * are responsible for caching the returned map across recompositions
 * so a sort comparator can read a score in `O(1)` without re-running
 * the batch on every key extraction.
 *
 * Failure semantics: any error from the AI batch (network down,
 * missing token, 5xx, malformed response, partial response) degrades
 * the affected tasks to the on-device formula. The user never sees a
 * sort that breaks because the AI was unreachable.
 */
@Singleton
class AiUrgencyResolver
@Inject
constructor(
    private val classifier: UrgencyClassifier,
    private val proFeatureGate: ProFeatureGate,
    private val userPreferencesDataStore: UserPreferencesDataStore
) {
    /**
     * Decide whether to use the AI path. The master AI toggle is the
     * privacy gate (mirrors the parent router's
     * `require_ai_features_enabled` dependency); the per-feature
     * toggle is the user's "I want the AI sort or not" choice.
     */
    suspend fun shouldUseAi(): Boolean {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_URGENCY)) return false
        val master = userPreferencesDataStore.aiFeaturePrefsFlow.first().enabled
        if (!master) return false
        return userPreferencesDataStore.perFeatureAiPrefsFlow.first().urgencyEnabled
    }

    /**
     * Resolve scores for the given tasks. The returned map is keyed by
     * `TaskEntity.id` and includes every task in the input — values
     * are AI-determined for tasks the backend successfully scored, and
     * fall back to [UrgencyScorer.calculateScore] otherwise.
     *
     * Passing [subtaskCounts] lets the AI weigh subtask progress the
     * same way the on-device formula does; an empty map means
     * "treat each task as having zero subtasks" which is identical to
     * the [UrgencyScorer] default.
     */
    suspend fun resolveScores(
        tasks: List<TaskEntity>,
        weights: UrgencyWeights = UrgencyWeights(),
        windows: UrgencyWindows = UrgencyWindows(),
        subtaskCounts: Map<Long, Pair<Int, Int>> = emptyMap()
    ): Map<Long, Float> {
        if (tasks.isEmpty()) return emptyMap()

        fun localFor(task: TaskEntity): Float {
            val (count, completed) = subtaskCounts[task.id] ?: (0 to 0)
            return UrgencyScorer.calculateScore(
                task = task,
                subtaskCount = count,
                subtaskCompleted = completed,
                weights = weights,
                windows = windows
            )
        }

        if (!shouldUseAi()) {
            return tasks.associate { it.id to localFor(it) }
        }
        val aiScores = classifier.scoreBatch(tasks, subtaskCounts).getOrNull().orEmpty()
        return tasks.associate { task ->
            task.id to (aiScores[task.id] ?: localFor(task))
        }
    }
}
