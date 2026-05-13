package com.averycorp.prismtask.ui.screens.habits.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.ui.components.StreakBadge
import com.averycorp.prismtask.ui.screens.habits.BuiltInHabitProgress
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * Card variant for built-in life-mode habits (schoolwork, leisure, etc.).
 * Surfaces a fixed icon + fixed color based on [type] and shows a streak
 * badge when the habit has one. Tapping dives into the corresponding
 * mode screen.
 */
@Composable
internal fun BuiltInHabitCard(
    type: String,
    habitWithStatus: HabitWithStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: BuiltInHabitProgress? = null
) {
    val title = when (type) {
        "school" -> "Schoolwork"
        "leisure" -> "Leisure"
        else -> type.replaceFirstChar { it.uppercase() }
    }
    val icon = when (type) {
        "school" -> "\uD83C\uDF93"
        "leisure" -> "\uD83C\uDFB5"
        else -> "\u2B50"
    }
    val palette = LocalPrismColors.current.dataVisualizationPalette
    val prismColors = LocalPrismColors.current
    val color = when (type) {
        "school" -> palette.getOrElse(0) { prismColors.primary }
        "leisure" -> palette.getOrElse(1) { prismColors.primary }
        else -> prismColors.primary
    }
    val cardShape = MaterialTheme.shapes.medium
    val attrs = LocalPrismAttrs.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = prismColors.primary.copy(alpha = 0.4f),
                shape = cardShape
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (habitWithStatus.isCompletedToday) {
                color.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (attrs.brackets) {
                        Modifier.border(
                            androidx.compose.foundation.BorderStroke(width = 3.dp, color = color),
                            shape = cardShape
                        ).padding(16.dp)
                    } else {
                        Modifier.padding(12.dp)
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (habitWithStatus.habit.showStreak && habitWithStatus.currentStreak > 0) {
                    StreakBadge(streak = habitWithStatus.currentStreak)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (habitWithStatus.isCompletedToday) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(LocalPrismColors.current.successColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Done",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                val isLeisure = type == "leisure"
                val leisurePct: Int? = if (isLeisure && progress != null && progress.total > 0) {
                    ((progress.done.toFloat() / progress.total.toFloat()) * 100f)
                        .toInt()
                        .coerceAtLeast(0)
                } else {
                    null
                }
                val circleColor = when {
                    leisurePct == null -> color
                    leisurePct >= 100 -> prismColors.successColor
                    else -> MaterialTheme.colorScheme.error
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(2.dp, circleColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        leisurePct != null -> Text(
                            text = "$leisurePct%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = circleColor
                        )
                        progress != null && progress.total > 0 -> Text(
                            text = "${progress.done}/${progress.total}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                }
            }
        }
    }
}
