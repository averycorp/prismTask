package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.averycorp.prismtask.ui.coachmark.CoachmarkAnchors
import com.averycorp.prismtask.ui.coachmark.coachmarkAnchor
import com.averycorp.prismtask.ui.components.QuickAddBar
import com.averycorp.prismtask.ui.components.QuickAddViewModel
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.TerminalCursor
import com.averycorp.prismtask.ui.theme.TerminalPrompt

/**
 * Floating variant of [QuickAddBar] used on the Today screen — sits in the
 * Scaffold's bottomBar slot with theme-specific styling:
 *
 * - CYBERPUNK: dashed primary-colored border, no fill background.
 * - SYNTHWAVE: horizontal gradient tint (primary→secondary at low alpha).
 * - MATRIX:    solid 1dp primary border, transparent background.
 * - VOID:      standard frosted surface (no decoration).
 */
@Composable
internal fun FloatingQuickAddBar(
    autoStartVoice: Boolean = false,
    onVoiceAutoStartConsumed: () -> Unit = {},
    onBatchCommand: (String) -> Unit = {},
    onMultiCreate: (String) -> Unit = {}
) {
    // Distinct VM scope from sibling QuickAddBars composed by the
    // MainTabs HorizontalPager. Today (idx 0) and TaskList (idx 1) live
    // under the same `MainTabs` NavBackStackEntry, and the pager's
    // `beyondViewportPageCount = 1` keeps the adjacent page composed
    // off-screen. Without an explicit key, both bars resolve
    // `hiltViewModel<QuickAddViewModel>()` to the same instance, so
    // every `_batchIntents` / `_multiCreateIntents` emit fans out to
    // both bars' `LaunchedEffect` collectors and the host navigates
    // twice — the user sees BatchPreview run, accept, then run again
    // off the second navigation. The key isolates each bar's VM so the
    // emit lands on exactly one collector.
    val viewModel: QuickAddViewModel = hiltViewModel(key = "today_floating_quickadd")
    val colors = LocalPrismAttrs.current.let { _ -> LocalPrismColors.current }
    val attrs = LocalPrismAttrs.current

    val barShape = RoundedCornerShape(
        topStart = attrs.cardRadius.dp,
        topEnd = attrs.cardRadius.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
    )

    val anchor = Modifier.coachmarkAnchor(CoachmarkAnchors.TODAY_QUICK_ADD)
    when {
        // Cyberpunk — dashed neon border, transparent fill
        attrs.brackets -> {
            Box(
                modifier = anchor
                    .fillMaxWidth()
                    .background(colors.background)
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(colors.primary.copy(alpha = 0.6f), colors.secondary.copy(alpha = 0.6f))
                        ),
                        shape = barShape
                    )
                    .padding(vertical = 6.dp)
            ) {
                QuickAddBar(
                    viewModel = viewModel,
                    autoStartVoice = autoStartVoice,
                    onVoiceMessage = {},
                    onBatchCommand = onBatchCommand,
                    onMultiCreate = onMultiCreate
                )
            }
        }

        // Synthwave — subtle gradient tint behind the bar (alpha=24/255≈0.094)
        attrs.sunset -> {
            Box(
                modifier = anchor
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(
                                colors.primary.copy(alpha = 0.094f),
                                colors.secondary.copy(alpha = 0.094f)
                            )
                        )
                    )
                    .padding(vertical = 6.dp)
            ) {
                QuickAddBar(
                    viewModel = viewModel,
                    autoStartVoice = autoStartVoice,
                    onVoiceMessage = {},
                    onBatchCommand = onBatchCommand,
                    onMultiCreate = onMultiCreate
                )
            }
        }

        // Matrix — solid thin primary border + "$  _" prompt prefix
        attrs.terminal -> {
            Row(
                modifier = anchor
                    .fillMaxWidth()
                    .background(colors.background)
                    .border(1.dp, colors.primary.copy(alpha = 0.5f), barShape)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TerminalPrompt()
                Spacer(modifier = Modifier.width(4.dp))
                TerminalCursor()
                Spacer(modifier = Modifier.width(4.dp))
                QuickAddBar(
                    viewModel = viewModel,
                    autoStartVoice = autoStartVoice,
                    onVoiceMessage = {},
                    modifier = Modifier.weight(1f),
                    onBatchCommand = onBatchCommand,
                    onMultiCreate = onMultiCreate
                )
            }
        }

        // Void / default — frosted translucent surface
        else -> {
            Surface(
                modifier = anchor,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    QuickAddBar(
                        viewModel = viewModel,
                        autoStartVoice = autoStartVoice,
                        onVoiceMessage = {},
                        onBatchCommand = onBatchCommand,
                        onMultiCreate = onMultiCreate
                    )
                }
            }
        }
    }

    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice) onVoiceAutoStartConsumed()
    }
}
