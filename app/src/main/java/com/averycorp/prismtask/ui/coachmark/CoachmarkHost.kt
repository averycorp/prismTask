package com.averycorp.prismtask.ui.coachmark

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Host that renders the coachmark overlay above [content]. Mount once at
 * the app root (inside [PrismTaskTheme] / above the NavHost).
 *
 * Responsibilities:
 *  - Provide [CoachmarkAnchorRegistry] via composition local so anchored
 *    surfaces can register their bounds.
 *  - Subscribe to [CoachmarkController.state] and render
 *    [CoachmarkOverlay] when the state is [CoachmarkState.ShowingStep].
 *  - Translate [CoachmarkController.hostEvent] navigation requests into
 *    [onNavigateRoute] callback invocations.
 *
 * Cross-screen step continuation: the host invokes [CoachmarkController.onArrived]
 * after [onNavigateRoute] runs and the destination has had a chance to
 * mount its anchor. A short [arrivalGraceMs] polls the registry until the
 * anchor resolves.
 */
@Composable
fun CoachmarkHost(
    controller: CoachmarkController,
    onNavigateRoute: (routeKey: String) -> Unit,
    modifier: Modifier = Modifier,
    isProActive: Boolean = true,
    arrivalGraceMs: Long = 300L,
    content: @Composable () -> Unit
) {
    val registry = remember { CoachmarkAnchorRegistry() }
    val state by controller.state.collectAsState()
    val hostEvent by controller.hostEvent.collectAsState()

    LaunchedEffect(hostEvent) {
        val ev = hostEvent ?: return@LaunchedEffect
        when (ev) {
            is CoachmarkHostEvent.Navigate -> {
                onNavigateRoute(ev.routeKey)
                controller.consumePendingNav()
                kotlinx.coroutines.delay(arrivalGraceMs)
                controller.onArrived(isProActive = isProActive)
            }
        }
    }

    ProvideCoachmarkAnchorRegistry(registry = registry) {
        Box(modifier = modifier.fillMaxSize()) {
            content()
            AnimatedVisibility(
                visible = state is CoachmarkState.ShowingStep,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val showing = state as? CoachmarkState.ShowingStep
                if (showing != null) {
                    val bounds = registry.resolve(showing.step.anchorId)?.rectInRoot
                    val totalSteps = currentTourSize(controller)
                    CoachmarkOverlay(
                        anchorBounds = bounds,
                        title = showing.step.title,
                        body = showing.step.body,
                        stepNumber = showing.stepIndex + 1,
                        totalSteps = totalSteps,
                        primaryCtaLabel = primaryCtaLabel(showing.step.action),
                        onPrimary = { controller.next(isProActive = isProActive) },
                        onSkipThis = { controller.skipThis(isProActive = isProActive) },
                        onSkipTour = { controller.dismiss() }
                    )
                }
            }
        }
    }
}

private fun primaryCtaLabel(action: CoachmarkAction): String = when (action) {
    is CoachmarkAction.Next -> "Next"
    is CoachmarkAction.Navigate -> "Try it"
    is CoachmarkAction.Finish -> "Got it"
}

/**
 * Read the tour size by reflecting on the controller. The tour list is
 * private; expose via the default content singleton for now. Multi-tour
 * support would parameterize this.
 */
private fun currentTourSize(@Suppress("UNUSED_PARAMETER") controller: CoachmarkController): Int =
    DEFAULT_COACHMARK_TOUR.size
