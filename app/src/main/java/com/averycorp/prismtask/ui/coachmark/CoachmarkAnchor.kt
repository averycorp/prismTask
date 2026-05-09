package com.averycorp.prismtask.ui.coachmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Bounds (root-coordinate space) of an anchored UI surface. Computed via
 * [Modifier.coachmarkAnchor] and read by [CoachmarkOverlay] to draw the
 * spotlight cutout + tooltip.
 */
@Stable
data class CoachmarkAnchorBounds(val rectInRoot: Rect)

/**
 * Per-process anchor registry. Surfaces participating in the coachmark
 * tour register their bounds via [Modifier.coachmarkAnchor]; the
 * controller reads via [resolve].
 *
 * Exposed via [LocalCoachmarkAnchorRegistry] so the modifier can find the
 * registry without coupling target Composables to controller state.
 */
@Stable
class CoachmarkAnchorRegistry {
    private val anchors = mutableStateMapOf<String, CoachmarkAnchorBounds>()

    fun register(id: String, bounds: CoachmarkAnchorBounds) {
        anchors[id] = bounds
    }

    fun unregister(id: String) {
        anchors.remove(id)
    }

    fun resolve(id: String): CoachmarkAnchorBounds? = anchors[id]

    fun ids(): Set<String> = anchors.keys.toSet()
}

val LocalCoachmarkAnchorRegistry =
    staticCompositionLocalOf<CoachmarkAnchorRegistry?> { null }

/**
 * Tag a Composable as an anchor for the coachmark tour. One-line invasion;
 * the target Composable doesn't need to know about the controller.
 *
 * If no [CoachmarkAnchorRegistry] is provided via composition local
 * (e.g. in unit tests, in dialogs that don't host a controller), the
 * modifier becomes a no-op.
 */
fun Modifier.coachmarkAnchor(id: String): Modifier = composed {
    val registry = LocalCoachmarkAnchorRegistry.current
        ?: return@composed this
    DisposableEffect(id, registry) {
        onDispose { registry.unregister(id) }
    }
    this.onGloballyPositioned { coords ->
        registry.register(id, CoachmarkAnchorBounds(coords.boundsInRoot()))
    }
}

@Composable
fun ProvideCoachmarkAnchorRegistry(
    registry: CoachmarkAnchorRegistry,
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCoachmarkAnchorRegistry provides registry,
        content = content
    )
}
