package com.averycorp.prismtask.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.averycorp.prismtask.data.preferences.CelebrationIntensity
import com.averycorp.prismtask.domain.usecase.ShipItCelebration
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import kotlinx.coroutines.delay

// region Good Enough Timer Indicator

/**
 * Small circular progress indicator for the task edit toolbar.
 * Color shifts from green -> yellow -> orange as time progresses.
 */
@Composable
fun GoodEnoughTimerIndicator(
    progress: Float,
    remainingText: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val prismColors = LocalPrismColors.current
    val color = when {
        progress < 0.5f -> prismColors.successColor
        progress < 0.8f -> prismColors.warningColor
        else -> prismColors.urgentAccent
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "timerProgress"
    )

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Timer: $remainingText remaining" },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.size(28.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.dp
        )
        Icon(
            Icons.Default.Timer,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color
        )
    }
}

// endregion

// region Good Enough Timer Dialog

private val dialogMessages = listOf(
    "You\u2019ve spent %d minutes on \u2018%s\u2019. This is probably good enough \u2014 perfectionism is the enemy of done.",
    "%d minutes in. \u2018%s\u2019 is looking solid \u2014 sometimes done is better than perfect.",
    "Time check: %d minutes on \u2018%s\u2019. Your future self will thank you for shipping it now.",
    "%d minutes invested in \u2018%s\u2019. The best tasks are finished tasks."
)

@Composable
fun GoodEnoughTimerDialog(
    editingMinutes: Int,
    taskTitle: String,
    onShipIt: () -> Unit,
    onTenMoreMinutes: () -> Unit,
    onKeepWorking: () -> Unit
) {
    val message = remember {
        dialogMessages.random().format(editingMinutes, taskTitle)
    }

    AlertDialog(
        onDismissRequest = onKeepWorking,
        title = { Text("Time Check") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onShipIt) {
                Text("Ship It")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onTenMoreMinutes) {
                    Text("10 More Minutes")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onKeepWorking) {
                    Text("Keep Working")
                }
            }
        }
    )
}

// endregion

// region Good Enough Lock Overlay

@Composable
fun GoodEnoughLockOverlay(
    editingMinutes: Int,
    onMarkDone: () -> Unit,
    onOverride: () -> Unit,
    onAdjustTimer: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Editing Paused",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "You\u2019ve hit your time cap ($editingMinutes minutes).",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onMarkDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Mark as Done")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOverride,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Override \u2014 I Need More Time")
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onAdjustTimer) {
                    Text("Adjust My Timer")
                }
            }
        }
    }
}

// endregion

// region Anti-Rework Soft Warning Bottom Sheet

@Composable
fun AntiReworkSoftWarningSheet(
    taskTitle: String,
    revisionCount: Int,
    adhdModeActive: Boolean,
    onReopen: () -> Unit,
    onLeaveIt: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "This One\u2019s Already Done",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "You finished \u2018$taskTitle\u2019 \u2014 nice work! Are you sure you want to re-open it for editing?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (revisionCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "You\u2019ve completed this task $revisionCount time(s) before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (adhdModeActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Starting fresh? Consider creating a new sub-task instead of re-editing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReopen,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Yes, Re-Open")
                }
                Button(
                    onClick = onLeaveIt,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("You\u2019re Right, Leave It")
                }
            }
        }
    }
}

// endregion

// region Cooling-Off Dialog

@Composable
fun CoolingOffDialog(
    minutesAgo: Int,
    remainingMinutes: Int,
    onRemindLater: () -> Unit,
    onOverride: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onRemindLater,
        title = { Text("Cooling Off") },
        text = {
            Text(
                "You finished this $minutesAgo minutes ago. Give it some space \u2014 " +
                    "you can edit again in $remainingMinutes minutes."
            )
        },
        confirmButton = {
            TextButton(onClick = onRemindLater) {
                Text("Remind Me Later")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onOverride,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text("Override \u2014 I Found a Real Issue", style = MaterialTheme.typography.labelSmall)
            }
        }
    )
}

// endregion

// region Max Revisions Dialog

@Composable
fun MaxRevisionsDialog(
    taskTitle: String,
    revisionCount: Int,
    onLockIt: () -> Unit,
    onOneMore: () -> Unit,
    onResetCounter: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* intentionally non-dismissable */ },
        title = { Text("Max Revisions Reached") },
        text = {
            Text(
                "You\u2019ve edited \u2018$taskTitle\u2019 $revisionCount times. At some point, done is done. This is that point."
            )
        },
        confirmButton = {
            Button(onClick = onLockIt) {
                Text("It\u2019s Done \u2014 Lock It")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onOneMore) {
                    Text("One More Revision")
                }
                TextButton(onClick = onResetCounter) {
                    Text("Reset Counter")
                }
            }
        }
    )
}

// endregion

// region Revision-Locked Badge

@Composable
fun RevisionLockedBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                LocalPrismShapes.current.chip
            ).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = "Revision locked",
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            "Final Version",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

// endregion

// region Ship-It Celebration Overlay

@Composable
fun ShipItCelebrationOverlay(
    celebration: ShipItCelebration,
    onDismiss: () -> Unit
) {
    when (celebration.intensity) {
        CelebrationIntensity.LOW -> SubtleCelebration(celebration.message, onDismiss)
        CelebrationIntensity.MEDIUM -> ConfettiCelebration(celebration.message, onDismiss)
        CelebrationIntensity.HIGH -> FullSendCelebration(
            celebration.message,
            celebration.isStreakMilestone,
            celebration.streakDays,
            onDismiss
        )
    }
}

@Composable
private fun SubtleCelebration(message: String, onDismiss: () -> Unit) {
    val scale = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        delay(1000)
        visible = false
        onDismiss()
    }

    AnimatedVisibility(visible = visible, exit = fadeOut(tween(300))) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 64.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .scale(scale.value)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        LocalPrismShapes.current.chip
                    ).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ConfettiCelebration(message: String, onDismiss: () -> Unit) {
    val scale = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        delay(2000)
        visible = false
        onDismiss()
    }

    AnimatedVisibility(visible = visible, exit = fadeOut(tween(300))) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Confetti particles (lightweight Canvas implementation)
            ConfettiCanvas(modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier.scale(scale.value),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("\u2705", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FullSendCelebration(
    message: String,
    isStreakMilestone: Boolean,
    streakDays: Int,
    onDismiss: () -> Unit
) {
    val scale = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        delay(3000)
        visible = false
        onDismiss()
    }

    AnimatedVisibility(visible = visible, exit = fadeOut(tween(300))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                .clickable {
                    visible = false
                    onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            ConfettiCanvas(modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier.scale(scale.value),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("\uD83C\uDF89", fontSize = 72.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                if (isStreakMilestone && streakDays > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "$streakDays-day release streak!",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

/**
 * Lightweight confetti particle effect using Canvas.
 */
@Composable
private fun ConfettiCanvas(modifier: Modifier = Modifier) {
    val palette = LocalPrismColors.current.dataVisualizationPalette
    val particles = remember {
        List(30) {
            ConfettiParticle(
                x = (Math.random() * 1000).toFloat(),
                y = (-Math.random() * 500).toFloat(),
                size = (4 + Math.random() * 8).toFloat(),
                color = palette.random(),
                speed = (2 + Math.random() * 4).toFloat()
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "confetti")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "confettiOffset"
    )

    Canvas(modifier = modifier.alpha(0.7f)) {
        particles.forEach { p ->
            val y = p.y + offset * p.speed
            if (y < size.height) {
                drawCircle(
                    color = p.color,
                    radius = p.size,
                    center = androidx.compose.ui.geometry.Offset(
                        x = p.x % size.width,
                        y = y % size.height
                    )
                )
            }
        }
    }
}

private data class ConfettiParticle(val x: Float, val y: Float, val size: Float, val color: Color, val speed: Float)

// endregion

// region Stuck Detection Suggestion Card

@Composable
fun StuckSuggestionCard(
    suggestedTaskTitle: String,
    hasTodayTasks: Boolean,
    onStartSuggested: () -> Unit,
    onPickForMe: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Not Sure Where to Start?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (hasTodayTasks) {
                Text(
                    "Here\u2019s a good one to start with:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            } else {
                Text(
                    "Nothing urgent \u2014 here\u2019s something you could knock out:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                suggestedTaskTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartSuggested,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start This One")
                }
                OutlinedButton(
                    onClick = onPickForMe,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pick for Me")
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("I\u2019m Just Browsing")
            }
        }
    }
}

// endregion
