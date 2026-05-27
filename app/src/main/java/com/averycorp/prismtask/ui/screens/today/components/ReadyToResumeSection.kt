package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.usecase.DormantTask

/**
 * Dormancy Re-Entry: the "Ready to Resume" section shown above the regular
 * Today task list whenever dormant recurring tasks exist. Each row offers a
 * one-tap 5-minute degraded session ("Resume 5 min") plus a per-day dismiss.
 *
 * Forgiveness-first framing (PHILOSOPHY.md Principle 1): copy is invitational
 * ("Ready to resume"), never shaming. No "X days since you failed" framing —
 * the day count is neutral context, not a guilt counter.
 */
@Composable
fun ReadyToResumeSection(
    items: List<DormantTask>,
    onResume: (taskId: Long) -> Unit,
    onDismiss: (taskId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Ready to Resume",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        items.forEach { item ->
            ReadyToResumeRow(item = item, onResume = onResume, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun ReadyToResumeRow(
    item: DormantTask,
    onResume: (taskId: Long) -> Unit,
    onDismiss: (taskId: Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            item.task.reEntryContext?.takeIf { it.isNotBlank() }?.let { context ->
                Text(
                    text = context,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${item.daysDormant} ${if (item.daysDormant == 1L) "Day" else "Days"} Dormant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = { onResume(item.task.id) }) {
                    Text("Resume 5 Min")
                }
                TextButton(onClick = { onDismiss(item.task.id) }) {
                    Text("Dismiss for Today")
                }
            }
        }
    }
}
