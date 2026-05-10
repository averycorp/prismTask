package com.averycorp.prismtask.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

/**
 * App-wide CompositionLocal for the active [FoldingFeature], or `null` on
 * non-foldable devices / under preview tooling / in unit tests.
 *
 * Plumbed in `MainActivity` via [androidx.window.layout.WindowInfoTracker]
 * and consumed at the screen level (TodayScreen, SettingsScreen, ChatScreen,
 * TaskListScreen, OnboardingPageLayout) to apply hinge-avoidance padding
 * when a `HALF_OPENED` vertical fold is present.
 *
 * Deliberately orthogonal to [LocalWindowSizeClass]: a folded foldable can
 * read `Compact` width while still exposing a hinge, and a phone unfolded
 * to a larger surface may read `Expanded` width with no `FoldingFeature`.
 * Consumers may read both, but neither is derived from the other.
 */
val LocalFoldingFeature = staticCompositionLocalOf<FoldingFeature?> { null }

/**
 * Resolve hinge-avoidance horizontal padding from the active foldable state.
 *
 * Returns `defaultHorizontal` on non-foldable devices and on `FLAT` folds.
 * On a `HALF_OPENED` (book posture) vertical fold, widens the side closer
 * to the hinge so content does not bleed across the crease. On a horizontal
 * `HALF_OPENED` fold (tabletop posture), returns `defaultHorizontal` —
 * vertical splits do not benefit from horizontal padding shifts.
 *
 * Use at the LazyColumn / Column root of a screen body. Caller is
 * responsible for combining with `expandedWidthCap()` if the screen is
 * also a tablet-width consumer.
 */
@Composable
@ReadOnlyComposable
fun hingeAwareHorizontalPadding(
    defaultHorizontal: Dp = 16.dp,
    hingeGutter: Dp = 24.dp
): PaddingValues {
    val fold = LocalFoldingFeature.current ?: return PaddingValues(horizontal = defaultHorizontal)
    if (fold.state != FoldingFeature.State.HALF_OPENED) {
        return PaddingValues(horizontal = defaultHorizontal)
    }
    if (fold.orientation != FoldingFeature.Orientation.VERTICAL) {
        return PaddingValues(horizontal = defaultHorizontal)
    }
    return PaddingValues(
        start = defaultHorizontal + hingeGutter,
        end = defaultHorizontal + hingeGutter
    )
}
