package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.averycorp.prismtask.ui.coachmark.coachmarkAnchor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.averycorp.prismtask.domain.model.CognitiveLoad
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.BalanceState
import com.averycorp.prismtask.domain.usecase.BurnoutResult
import com.averycorp.prismtask.domain.usecase.CognitiveLoadBalanceState
import com.averycorp.prismtask.ui.theme.CognitiveLoadColor
import com.averycorp.prismtask.ui.theme.LifeCategoryColor

/**
 * Compact Work-Life Balance bar shown beneath the Today progress header.
 *
 * Renders the four tracked categories (Work / Personal / Self-Care / Health)
 * as a horizontal stacked bar. Each segment's width is proportional to the
 * category's share of the user's last 7 days of tracked tasks. A small
 * warning icon appears when the balance is overloaded toward work.
 *
 * When no tasks have been categorized yet, the bar shows an "Add categories
 * to see your balance" hint instead of an empty bar.
 */
@Composable
internal fun TodayBalanceSection(
    state: BalanceState,
    burnout: BurnoutResult = BurnoutResult.EMPTY,
    onClick: () -> Unit = {}
) {
    var showBurnoutDetail by remember { mutableStateOf(false) }
    // Edge case: when BalanceTracker has no categorized tasks yet, we have no
    // basis for scoring burnout — suppress the badge entirely rather than
    // showing a misleading "Balanced" chip.
    val hasBalanceData = state.totalTracked > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .coachmarkAnchor(com.averycorp.prismtask.ui.coachmark.CoachmarkAnchors.TODAY_OVERLOAD_BANNER)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Balance",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            // Always show the burnout chip once we have data — including the
            // BALANCED state, so users get positive reinforcement rather than
            // only seeing the chip when something is wrong.
            if (hasBalanceData) {
                BurnoutBadge(
                    result = burnout,
                    onClick = { showBurnoutDetail = true }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (state.isOverloaded) {
                Text(
                    text = "\u26A0 Work high",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = LifeCategoryColor.HEALTH
                )
            } else if (hasBalanceData) {
                val dominantLabel = LifeCategory.label(state.dominantCategory)
                Text(
                    text = dominantLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (!hasBalanceData) {
            Text(
                text = "Add categories to see your balance",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            BalanceStackedBar(ratios = state.currentRatios)
        }
    }

    if (showBurnoutDetail) {
        BurnoutDetailSheet(
            result = burnout,
            onDismiss = { showBurnoutDetail = false }
        )
    }
}

@Composable
private fun BalanceStackedBar(ratios: Map<LifeCategory, Float>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        LifeCategory.TRACKED.forEach { category ->
            val ratio = (ratios[category] ?: 0f).coerceIn(0f, 1f)
            if (ratio > 0f) {
                Box(
                    modifier = Modifier
                        .weight(ratio)
                        .fillMaxSize()
                        .background(LifeCategoryColor.forCategory(category))
                )
            }
        }
    }
}

/**
 * Compact cognitive-load balance section. Mirrors [TodayBalanceSection] but
 * for the Easy / Medium / Hard start-friction dimension — see
 * `docs/COGNITIVE_LOAD.md`. Renders the three tracked tiers as a horizontal
 * stacked bar; segment widths are proportional to the load's share of the
 * user's last 7 days of tracked tasks.
 *
 * Hidden until the user has tagged at least one task with a load (mirrors
 * the LifeCategory bar's `totalTracked > 0` gate).
 */
@Composable
internal fun TodayCognitiveLoadSection(
    state: CognitiveLoadBalanceState,
    onClick: () -> Unit = {}
) {
    val hasLoadData = state.totalTracked > 0
    if (!hasLoadData) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cognitive Load",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = CognitiveLoad.label(state.dominantLoad),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        CognitiveLoadStackedBar(ratios = state.currentRatios)
    }
}

@Composable
private fun CognitiveLoadStackedBar(ratios: Map<CognitiveLoad, Float>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        CognitiveLoad.TRACKED.forEach { load ->
            val ratio = (ratios[load] ?: 0f).coerceIn(0f, 1f)
            if (ratio > 0f) {
                Box(
                    modifier = Modifier
                        .weight(ratio)
                        .fillMaxSize()
                        .background(CognitiveLoadColor.forLoad(load))
                )
            }
        }
    }
}

/**
 * Full-width banner shown on the Today screen when the user's work ratio
 * blows past their configured target by more than the overload threshold.
 * Tapping the "Dismiss" button hides the banner for the rest of the day
 * (state held by the caller). v1.4.0 V2.
 */
@Composable
internal fun OverloadBanner(
    workPct: Int,
    targetPct: Int,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = LifeCategoryColor.HEALTH.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u26A0",
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Work is $workPct% of your week",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "That's above your $targetPct% target. Consider blocking time for self-care.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
