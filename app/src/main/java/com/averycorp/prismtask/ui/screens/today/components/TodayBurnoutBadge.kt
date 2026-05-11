package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.usecase.BurnoutBand
import com.averycorp.prismtask.domain.usecase.BurnoutResult
import com.averycorp.prismtask.ui.theme.LocalPrismShapes

/**
 * Styling bundle for [BurnoutBadge] / [BurnoutDetailSheet]. Pulled from
 * [MaterialTheme.colorScheme] so the chip respects the user's theme, accent,
 * and high-contrast overrides instead of hardcoded hex.
 */
private data class BurnoutBandStyle(val background: Color, val foreground: Color, val icon: ImageVector, val label: String)

@Composable
private fun burnoutBandStyle(band: BurnoutBand): BurnoutBandStyle {
    val scheme = MaterialTheme.colorScheme
    return when (band) {
        BurnoutBand.BALANCED -> BurnoutBandStyle(
            background = scheme.tertiaryContainer,
            foreground = scheme.onTertiaryContainer,
            icon = Icons.Filled.CheckCircle,
            label = "Balanced"
        )
        BurnoutBand.MONITOR -> BurnoutBandStyle(
            background = scheme.secondaryContainer,
            foreground = scheme.onSecondaryContainer,
            icon = Icons.Filled.Visibility,
            label = "Monitor"
        )
        BurnoutBand.CAUTION -> BurnoutBandStyle(
            background = scheme.errorContainer,
            foreground = scheme.onErrorContainer,
            icon = Icons.Filled.Warning,
            label = "Caution"
        )
        BurnoutBand.HIGH_RISK -> BurnoutBandStyle(
            background = scheme.error,
            foreground = scheme.onError,
            icon = Icons.Filled.Error,
            label = "High Risk"
        )
    }
}

@Composable
internal fun BurnoutBadge(
    result: BurnoutResult,
    onClick: () -> Unit
) {
    val style = burnoutBandStyle(result.band)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(style.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null,
            tint = style.foreground,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = style.foreground
        )
    }
}

/**
 * Describes one row in the [BurnoutDetailSheet] breakdown: the human label,
 * the points this factor contributed to the overall score, and the factor's
 * individual cap (so we can render a proportional progress bar).
 */
private data class BurnoutContribution(val name: String, val points: Int, val max: Int, val suggestion: String)

private fun BurnoutResult.contributions(): List<BurnoutContribution> = listOf(
    BurnoutContribution(
        name = "Work Overshoot",
        points = workOvershootPoints,
        max = 25,
        suggestion = "Block off a personal or self-care window and push one work task to tomorrow."
    ),
    BurnoutContribution(
        name = "Overdue Tasks",
        points = overduePoints,
        max = 20,
        suggestion = "Reschedule or clear the oldest overdue items so they stop weighing on you."
    ),
    BurnoutContribution(
        name = "Skipped Self-Care",
        points = skippedSelfCarePoints,
        max = 20,
        suggestion = "Add a short self-care task today \u2014 even 10 minutes counts."
    ),
    BurnoutContribution(
        name = "Medication Gaps",
        points = medicationPoints,
        max = 15,
        suggestion = "Turn on medication reminders so doses don't slip past you."
    ),
    BurnoutContribution(
        name = "Streak Breaks",
        points = streakBreakPoints,
        max = 10,
        suggestion = "Pick one habit to restart today and keep the bar intentionally low."
    ),
    BurnoutContribution(
        name = "Rest Deficit",
        points = restDeficitPoints,
        max = 10,
        suggestion = "Schedule a rest break this afternoon \u2014 a walk, nap, or quiet time."
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BurnoutDetailSheet(
    result: BurnoutResult,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val style = burnoutBandStyle(result.band)
    val contributions = remember(result) { result.contributions() }
    // Only surface factors that actually contribute to the score; when the
    // user is fully BALANCED we fall back to a reassuring "all clear" state.
    val activeContributions = contributions.filter { it.points > 0 }
    val topFactors = activeContributions.sortedByDescending { it.points }.take(3)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.foreground,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(style.background)
                        .padding(6.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Your Burnout Score Is ${result.score}/100",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = style.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = style.foreground
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "What's Driving It",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (activeContributions.isEmpty()) {
                Text(
                    text = "Nothing is pulling your score up right now. Keep it up!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                activeContributions.forEach { contribution ->
                    BurnoutContributionRow(contribution = contribution)
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            if (topFactors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "What Can I Do?",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                topFactors.forEach { factor ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "\u2022 ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = factor.suggestion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BurnoutContributionRow(contribution: BurnoutContribution) {
    val progress = if (contribution.max == 0) {
        0f
    } else {
        (contribution.points.toFloat() / contribution.max.toFloat()).coerceIn(0f, 1f)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = contribution.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "+${contribution.points}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}
