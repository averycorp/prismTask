package com.averycorp.prismtask.ui.theme

import android.graphics.Rect
import androidx.window.layout.FoldingFeature

/**
 * In-memory `FoldingFeature` factory for `@Preview` composables and unit
 * tests. NOT a production type — call sites should be limited to
 * `@Preview`-annotated functions and `src/test/` / `src/androidTest/`.
 *
 * Defaults model a Galaxy Z Fold-style vertical hinge in book posture so
 * a single `previewFold()` call exercises the [hingeAwareHorizontalPadding]
 * branch without ceremony. Pass [orientation] = HORIZONTAL or [state] =
 * FLAT to model alternate postures.
 */
fun previewFold(
    state: FoldingFeature.State = FoldingFeature.State.HALF_OPENED,
    orientation: FoldingFeature.Orientation = FoldingFeature.Orientation.VERTICAL,
    bounds: Rect = Rect(540, 0, 580, 1840)
): FoldingFeature {
    val capturedState = state
    val capturedOrientation = orientation
    val capturedBounds = bounds
    return object : FoldingFeature {
        override val bounds: Rect get() = capturedBounds
        override val isSeparating: Boolean
            get() = capturedState == FoldingFeature.State.HALF_OPENED
        override val occlusionType: FoldingFeature.OcclusionType
            get() = FoldingFeature.OcclusionType.NONE
        override val orientation: FoldingFeature.Orientation get() = capturedOrientation
        override val state: FoldingFeature.State get() = capturedState
    }
}
