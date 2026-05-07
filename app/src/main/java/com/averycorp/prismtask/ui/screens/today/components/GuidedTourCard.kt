package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class GuidedTourStep(
    val emoji: String,
    val title: String,
    val body: String
)

/**
 * Step content for the post-onboarding Guided Tour card on Today.
 *
 * Voice: warm, non-judgmental, no productivity-bro language. ADHD-aware
 * framing where it lands naturally (e.g. "a missed day won't punish you")
 * rather than tokenised. English only — i18n is out of scope for this PR.
 */
val GUIDED_TOUR_STEPS: List<GuidedTourStep> = listOf(
    GuidedTourStep(
        emoji = "✨",
        title = "Quick add anything",
        body = "Type or speak a task. Try \"Buy milk tomorrow at 4pm #shopping\" — dates and tags parse on their own."
    ),
    GuidedTourStep(
        emoji = "🗂️",
        title = "Tasks, habits, and meds",
        body = "Tabs along the bottom hold each surface. Streaks here forgive a missed day — they won't punish you."
    ),
    GuidedTourStep(
        emoji = "⏱️",
        title = "Focus when you need it",
        body = "Open Timer for Pomodoro sessions tuned to your energy. Short or long, your call."
    ),
    GuidedTourStep(
        emoji = "🤖",
        title = "AI assistant — optional",
        body = "Settings → AI to turn it on. Off by default. It lives where you are and never pushes."
    ),
    GuidedTourStep(
        emoji = "🎨",
        title = "Make it yours",
        body = "Themes, widgets, integrations, exports — all live in Settings, ready when you want them."
    )
)

/**
 * Dismissible card on Today that walks new users through a few breadth
 * highlights post-onboarding. Step content lives in [GUIDED_TOUR_STEPS];
 * persistence and gating live in `TourCardPreferences` /
 * `TodayViewModel.tourCard`.
 *
 * The card models the existing `MorningCheckInBanner` shape: gradient
 * background, dismiss X in the top-right, primary CTA at the bottom.
 * Returning-user exclusion is handled by the eligibility flag, not by
 * this composable.
 */
@Composable
fun GuidedTourCard(
    step: GuidedTourStep,
    stepNumber: Int,
    totalSteps: Int,
    onAdvance: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(
        colors = listOf(
            accent.copy(alpha = 0.10f),
            accent.copy(alpha = 0.04f)
        )
    )
    val isLastStep = stepNumber >= totalSteps
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = "Guided tour, step $stepNumber of $totalSteps. ${step.title}"
            },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .semantics { contentDescription = "Dismiss guided tour" }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 48.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = step.emoji,
                        fontSize = 22.sp
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Step $stepNumber of $totalSteps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = step.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onAdvance,
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = if (isLastStep) "Done" else "Got It",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        if (!isLastStep) {
                            TextButton(onClick = onDismiss) {
                                Text(
                                    text = "Don't Show Again",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
