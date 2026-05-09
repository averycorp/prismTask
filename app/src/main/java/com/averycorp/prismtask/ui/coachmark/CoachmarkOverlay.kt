package com.averycorp.prismtask.ui.coachmark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen overlay that draws a dimmed scrim with a spotlight cutout
 * around an anchor [Rect], plus a tooltip card with title / body / CTAs.
 *
 * Z-order rules:
 *  - Renders inside an existing app-root [Box]; caller is responsible for
 *    ensuring this composable is the *last* child so it sits on top.
 *  - Loses focus when a Material dialog or bottom sheet opens — the overlay
 *    remains rendered visually but TalkBack focus moves to the foreground.
 *
 * Reduced motion: scrim is rendered at full alpha immediately when
 * [reduceMotion] is true; otherwise a single fade-in tween (handled by
 * the host via `AnimatedVisibility`).
 */
@Composable
fun CoachmarkOverlay(
    anchorBounds: Rect?,
    title: String,
    body: String,
    stepNumber: Int,
    totalSteps: Int,
    primaryCtaLabel: String,
    onPrimary: () -> Unit,
    onSkipThis: () -> Unit,
    onSkipTour: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cornerRadiusPx = remember(density) { with(density) { 12.dp.toPx() } }
    val spotlightPadPx = remember(density) { with(density) { 8.dp.toPx() } }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
        ) {
            drawSpotlightScrim(
                anchorBounds = anchorBounds,
                cornerRadiusPx = cornerRadiusPx,
                padPx = spotlightPadPx
            )
        }
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription =
                        "Tour step $stepNumber of $totalSteps. $title. $body"
                },
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Step $stepNumber of $totalSteps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row {
                        TextButton(onClick = onSkipTour) {
                            Text("Skip tour")
                        }
                        Spacer(modifier = Modifier.height(0.dp))
                        TextButton(onClick = onSkipThis) {
                            Text("Skip this")
                        }
                    }
                    Button(
                        onClick = onPrimary,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(primaryCtaLabel)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSpotlightScrim(
    anchorBounds: Rect?,
    cornerRadiusPx: Float,
    padPx: Float
) {
    val scrimColor = Color.Black.copy(alpha = 0.55f)
    if (anchorBounds == null) {
        // No anchor resolved — render a plain scrim. Tooltip still readable.
        drawRect(color = scrimColor, topLeft = Offset.Zero, size = size)
        return
    }
    val padded = Rect(
        left = (anchorBounds.left - padPx).coerceAtLeast(0f),
        top = (anchorBounds.top - padPx).coerceAtLeast(0f),
        right = (anchorBounds.right + padPx).coerceAtMost(size.width),
        bottom = (anchorBounds.bottom + padPx).coerceAtMost(size.height)
    )
    val path = Path().apply {
        addRect(Rect(Offset.Zero, Size(size.width, size.height)))
        addRoundRect(
            RoundRect(
                rect = padded,
                radiusX = cornerRadiusPx,
                radiusY = cornerRadiusPx
            )
        )
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    }
    drawPath(path = path, color = scrimColor, blendMode = BlendMode.SrcOver)
}
