package com.averycorp.prismtask.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App-wide CompositionLocal for [WindowSizeClass]. Provides a default
 * Compact width when no provider is in scope (e.g. previews, unit tests),
 * so consumers can read the local without crashing.
 *
 * Plumbed in `MainActivity` via [androidx.compose.material3.windowsizeclass.calculateWindowSizeClass]
 * and consumed at the screen level (currently TodayScreen, SettingsScreen,
 * ChatScreen) to apply max-content-width on Expanded-width tablets without
 * affecting phone (Compact) layouts.
 *
 * Foldable-aware behavior is intentionally NOT layered here — see the D11
 * finish bundle audit (`docs/audits/D11_FINISH_BUNDLE_AUDIT.md`), which
 * defers `WindowInfoTracker` / `FoldingFeature` to F-FOLDABLE-001.
 */
@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass> {
    WindowSizeClass.calculateFromSize(androidx.compose.ui.unit.DpSize(360.dp, 800.dp))
}

/**
 * Apply a horizontal centered max-content-width on Expanded width only.
 * On Compact / Medium widths the modifier is a no-op so phone layouts are
 * unaffected. Default 840dp matches Material 3 list-page guidance; override
 * via [maxWidth] for chat-style content (600dp keeps line-length readable).
 */
@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun Modifier.expandedWidthCap(maxWidth: Dp = 840.dp): Modifier {
    val widthClass = LocalWindowSizeClass.current.widthSizeClass
    return if (widthClass == WindowWidthSizeClass.Expanded) {
        this.then(Modifier.widthIn(max = maxWidth))
    } else {
        this
    }
}

/**
 * Wrap content in a centered Row that applies [expandedWidthCap]. Use at
 * the root of a screen body so the entire content column is constrained on
 * tablets while keeping phone layouts edge-to-edge.
 */
@Composable
fun ExpandedWidthCap(
    maxWidth: Dp = 840.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .expandedWidthCap(maxWidth)
        ) {
            content()
        }
    }
}
