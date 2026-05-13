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
import com.averycorp.prismtask.ui.components.StreakBadge
import com.averycorp.prismtask.ui.screens.habits.SelfCareCardData
import com.averycorp.prismtask.ui.theme.LocalHabitBorderBrightness
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * Composite "card" for a self-care routine surfaced on the habit list
 * (morning, bedtime, medication, housework). Shows the routine title,
 * icon, tier badge, and a completion indicator — either a solid green
 * check once all steps are done, or a progress ratio ring until then.
 */
@Composable
internal fun SelfCareRoutineCard(
    routineType: String,
    cardData: SelfCareCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (routineType) {
        "morning" -> "Morning Routine"
        "medication" -> "Medication"
        "housework" -> "Housework"
        else -> "Bedtime Routine"
    }
    val icon = when (routineType) {
        "morning" -> "\u2600\uFE0F"
        "medication" -> "\uD83D\uDC8A"
        "housework" -> "\uD83C\uDFE0"
        else -> "\uD83C\uDF19"
    }
    val c = LocalPrismColors.current
    val color = when (routineType) {
        "morning" -> c.warningColor
        "medication" -> c.destructiveColor
        "housework" -> c.successColor
        else -> c.primary
    }
    val cardShape = MaterialTheme.shapes.medium
    val attrs = LocalPrismAttrs.current
    val borderAlpha = LocalHabitBorderBrightness.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = c.primary.copy(alpha = borderAlpha),
                shape = cardShape
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (cardData.isComplete) {
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
                if (routineType != "medication") {
                    Text(
                        text = cardData.tierLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold
                    )
                }
                if (cardData.showStreak && cardData.currentStreak > 0) {
                    StreakBadge(streak = cardData.currentStreak)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (cardData.isComplete) {
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(2.dp, color, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${cardData.completedCount}/${cardData.totalCount}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
        }
    }
}
