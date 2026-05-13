package com.averycorp.prismtask.ui.screens.today.dailyessentials.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.usecase.LeisureBudgetCardState

/**
 * Today-page leisure summary card. Display-only: shows progress toward
 * the daily minimum and the current suggestion. Logging time / completing
 * a slot lives on the Leisure screen — tap the card body to open it.
 */
@Composable
fun LeisureBudgetCard(
    state: LeisureBudgetCardState,
    onTapBody: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = Color(0xFF8B5CF6)
    DailyEssentialCard(
        accent = accent,
        contentDescription = "Leisure minimum: ${state.minutesLogged} of ${state.targetMinutes} minutes",
        onClick = onTapBody,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Leisure Minimum",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(0.dp).weight(1f))
                Text(
                    text = if (state.targetMinutes > 0) {
                        "${state.minutesLogged} / ${state.targetMinutes} min"
                    } else {
                        "${state.minutesLogged} min"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (state.targetMinutes > 0) {
                LinearProgressIndicator(
                    progress = { state.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (state.poolIsEmpty) {
                Text(
                    text = "Add your first leisure activity",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Tap to set up your pool.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.suggestionName != null) {
                Text(
                    text = "Try: ${state.suggestionName}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = state.suggestionCategory.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = if (state.targetHit) "Minimum Hit. Take A Victory Lap." else "Nothing Suggested.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
