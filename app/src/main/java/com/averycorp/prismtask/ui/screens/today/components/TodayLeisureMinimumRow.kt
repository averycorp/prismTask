package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.usecase.LeisureBudgetCardState
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * Compact leisure-minimum progress row for the Today Habits section.
 * Surfaces the daily leisure target as an explicit percentage so users
 * see at-a-glance how far they are toward their minimum, plus the raw
 * minutes ratio for context. Tapping opens the Leisure pool screen.
 */
@Composable
internal fun TodayLeisureMinimumRow(
    state: LeisureBudgetCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.targetMinutes <= 0) return
    val colors = LocalPrismColors.current
    val percent = (state.progressFraction * 100f).toInt().coerceIn(0, 100)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = colors.border,
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Leisure Minimum",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.targetHit) colors.primary else colors.onBackground
                )
            }
            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
            Text(
                text = if (state.targetHit) {
                    "${state.minutesLogged} / ${state.targetMinutes} min · Minimum Hit"
                } else {
                    "${state.minutesLogged} / ${state.targetMinutes} min"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted
            )
        }
    }
}
