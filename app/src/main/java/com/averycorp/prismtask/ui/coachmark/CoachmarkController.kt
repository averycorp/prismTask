package com.averycorp.prismtask.ui.coachmark

import com.averycorp.prismtask.data.preferences.TourCardPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Visible state for the coachmark overlay.
 *
 * - [Idle]: nothing showing.
 * - [ShowingStep]: overlay rendered against the step at [stepIndex] of [tour].
 * - [Navigating]: transient — overlay hidden while host issues a navigation.
 *                 Re-resolves to [ShowingStep] when the destination remounts.
 * - [Completed]: terminal absorbing state. Tour finished naturally.
 * - [Dismissed]: terminal absorbing state. User opted out.
 */
sealed class CoachmarkState {
    data object Idle : CoachmarkState()
    data class ShowingStep(val stepIndex: Int, val step: CoachmarkStep) : CoachmarkState()
    data class Navigating(val pendingStepIndex: Int, val routeKey: String) : CoachmarkState()
    data object Completed : CoachmarkState()
    data object Dismissed : CoachmarkState()
}

/**
 * Pending host action surfaced to a single observer (the host composable).
 * We use a SharedFlow-like pattern — host consumes via `consumePendingNav()`
 * after performing the navigation.
 */
sealed class CoachmarkHostEvent {
    data class Navigate(val routeKey: String, val stepIndex: Int) : CoachmarkHostEvent()
}

/**
 * Drives the post-onboarding coachmark tour.
 *
 * Lifecycle:
 *  1. Eligibility computed from [TourCardPreferences]: tour starts when
 *     `tour_card_eligible && !coachmark_tour_completed && !coachmark_tour_dismissed`.
 *  2. Host calls [tryStart] on first Today open after onboarding completes.
 *  3. State machine transitions per `CoachmarkState`.
 *  4. Navigation actions are surfaced via [hostEvent]; host issues nav and
 *     calls [onArrived] when the target screen mounts (with the next step's
 *     anchor ready).
 *
 * Tests: see `CoachmarkControllerTest`.
 */
/**
 * Construct via the [com.averycorp.prismtask.di.CoachmarkModule] Hilt
 * binding in production; tests construct directly with a custom tour
 * and scope.
 */
class CoachmarkController(
    private val tourCardPreferences: TourCardPreferences,
    private val tour: List<CoachmarkStep>,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<CoachmarkState>(CoachmarkState.Idle)
    val state: StateFlow<CoachmarkState> = _state.asStateFlow()

    private val _hostEvent = MutableStateFlow<CoachmarkHostEvent?>(null)
    val hostEvent: StateFlow<CoachmarkHostEvent?> = _hostEvent.asStateFlow()

    /**
     * Attempt to start the tour. No-op when ineligible or in a non-[Idle]
     * state. Reads persistence to resume from a stored step index if the
     * tour was paused mid-flight.
     *
     * @param isProActive used to skip Pro-gated steps for Free users when
     *                    the audit-decided fallback is "skip" rather than
     *                    "show with upsell copy". Default: don't skip
     *                    Pro-gated steps; let the host render upsell copy.
     */
    suspend fun tryStart(isProActive: Boolean = true) {
        if (_state.value !is CoachmarkState.Idle) return
        if (!eligibleForStart()) return
        val storedIndex = tourCardPreferences.coachmarkStepIndex().first().coerceIn(0, tour.lastIndex)
        showStep(storedIndex, isProActive)
    }

    /**
     * Manual entry point — invoked by a "Resume tour" chip on Today.
     * Forces a step show even if persistence reports no in-progress index.
     */
    suspend fun resume(isProActive: Boolean = true) {
        if (!eligibleForStart()) return
        val storedIndex = tourCardPreferences.coachmarkStepIndex().first().coerceIn(0, tour.lastIndex)
        showStep(storedIndex, isProActive)
    }

    /**
     * Advance from the current step. If the step's action is [CoachmarkAction.Navigate]
     * the controller persists the next step index and emits a host event.
     */
    fun next(isProActive: Boolean = true) {
        val current = _state.value as? CoachmarkState.ShowingStep ?: return
        when (current.step.action) {
            is CoachmarkAction.Finish -> finishCompleted()
            is CoachmarkAction.Navigate -> {
                val next = nextEligibleIndex(current.stepIndex, isProActive)
                if (next == null) {
                    finishCompleted()
                } else {
                    scope.launch { tourCardPreferences.setCoachmarkStepIndex(next) }
                    _state.value = CoachmarkState.Navigating(
                        pendingStepIndex = next,
                        routeKey = (current.step.action as CoachmarkAction.Navigate).routeKey
                    )
                    _hostEvent.value = CoachmarkHostEvent.Navigate(
                        routeKey = (current.step.action as CoachmarkAction.Navigate).routeKey,
                        stepIndex = next
                    )
                }
            }
            is CoachmarkAction.Next -> {
                val next = nextEligibleIndex(current.stepIndex, isProActive)
                if (next == null) {
                    finishCompleted()
                } else {
                    scope.launch { tourCardPreferences.setCoachmarkStepIndex(next) }
                    _state.value = CoachmarkState.ShowingStep(next, tour[next])
                }
            }
        }
    }

    /**
     * Skip to the next *eligible* step without changing terminal flags.
     * "Skip This" semantics — distinct from [dismiss].
     */
    fun skipThis(isProActive: Boolean = true) {
        val current = _state.value as? CoachmarkState.ShowingStep ?: return
        val next = nextEligibleIndex(current.stepIndex, isProActive)
        if (next == null) {
            finishCompleted()
        } else {
            scope.launch { tourCardPreferences.setCoachmarkStepIndex(next) }
            _state.value = CoachmarkState.ShowingStep(next, tour[next])
        }
    }

    /**
     * Terminal — user opted out. Sets [TourCardPreferences.markCoachmarkDismissed].
     */
    fun dismiss() {
        scope.launch { tourCardPreferences.markCoachmarkDismissed() }
        _state.value = CoachmarkState.Dismissed
    }

    /**
     * Called by the host after performing a navigation. Re-shows the
     * overlay at the persisted step index against the now-mounted screen.
     */
    fun onArrived(isProActive: Boolean = true) {
        val pending = _state.value as? CoachmarkState.Navigating ?: return
        showStep(pending.pendingStepIndex, isProActive)
    }

    /**
     * Acknowledge the host event after dispatch — clears the one-shot.
     */
    fun consumePendingNav() {
        _hostEvent.value = null
    }

    private fun finishCompleted() {
        scope.launch { tourCardPreferences.markCoachmarkCompleted() }
        _state.value = CoachmarkState.Completed
    }

    private fun showStep(index: Int, isProActive: Boolean) {
        val resolved = nextEligibleIndexInclusive(index, isProActive)
        if (resolved == null) {
            finishCompleted()
            return
        }
        if (resolved != index) {
            scope.launch { tourCardPreferences.setCoachmarkStepIndex(resolved) }
        }
        _state.value = CoachmarkState.ShowingStep(resolved, tour[resolved])
    }

    private fun nextEligibleIndex(from: Int, isProActive: Boolean): Int? {
        var i = from + 1
        while (i <= tour.lastIndex) {
            if (isStepEligible(tour[i], isProActive)) return i
            i++
        }
        return null
    }

    private fun nextEligibleIndexInclusive(from: Int, isProActive: Boolean): Int? {
        if (from in 0..tour.lastIndex && isStepEligible(tour[from], isProActive)) return from
        return nextEligibleIndex(from, isProActive)
    }

    private fun isStepEligible(step: CoachmarkStep, isProActive: Boolean): Boolean {
        // Pro-gated steps: the audit calls for "show with upsell copy" rather
        // than skip, so eligibility ignores `requiresPro`. Hosts can render
        // upsell variants. This method is kept for future selective skipping.
        @Suppress("UNUSED_PARAMETER") val unused = isProActive
        @Suppress("UNUSED_PARAMETER") val unused2 = step
        return true
    }

    private suspend fun eligibleForStart(): Boolean {
        val eligible = tourCardPreferences.eligible().first()
        val completed = tourCardPreferences.coachmarkCompleted().first()
        val dismissed = tourCardPreferences.coachmarkDismissed().first()
        return eligible && !completed && !dismissed
    }
}
