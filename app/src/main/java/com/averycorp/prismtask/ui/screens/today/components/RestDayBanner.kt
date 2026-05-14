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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Rest Day banner (Mental-Health-First audit § G3).
 *
 * Replaces the dense Today task list with a soft, non-shaming header
 * when the user has marked today as a rest day. Tasks scheduled for
 * today stay in Room — they are not deleted and not auto-rescheduled.
 *
 * Voice exemplar: [com.averycorp.prismtask.data.preferences.ProductiveStreakPreferences]
 * ("Take care of yourself today — start fresh tomorrow."). The copy is
 * descriptive and non-clinical: it states what is happening
 * (notifications pause, streaks stay safe), never "you should rest" or
 * "you need to rest". See `docs/REST_DAY.md` for the philosophy.
 */
@Composable
fun RestDayBanner(
    onEndRestDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(
        colors = listOf(
            accent.copy(alpha = 0.10f),
            accent.copy(alpha = 0.04f)
        )
    )
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription =
                    "Resting today. Habit streaks stay safe and " +
                    "non-medication notifications are paused until tomorrow."
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Resting Today — See You Tomorrow",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Habit streaks stay safe. Non-medication " +
                            "notifications are paused until tomorrow. " +
                            "Medications still work as usual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onEndRestDay,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "End Rest Day",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog used by the Today-screen Rest Day toggle. Renders
 * a non-shaming question and two buttons. Copy is descriptive: it
 * states the consequences (streaks safe, notifications pause) without
 * prescribing behavior ("you should rest").
 *
 * Mirror this in any future surface that mints a rest day — keep the
 * voice consistent (see `docs/REST_DAY.md` § Copy guidelines).
 */
@Composable
fun RestDayConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark Today as a Rest Day?") },
        text = {
            Text(
                text = "Habit streaks won't break and non-medication " +
                    "notifications pause until tomorrow. Medications " +
                    "still fire as usual."
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Yes, Rest Today")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Not Yet")
            }
        }
    )
}
