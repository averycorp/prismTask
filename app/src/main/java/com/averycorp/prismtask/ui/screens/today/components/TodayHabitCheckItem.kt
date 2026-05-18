package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.components.StreakBadge
import com.averycorp.prismtask.ui.theme.LocalHabitBorderBrightness
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.TerminalLabel
import com.averycorp.prismtask.ui.theme.cornerBrackets
import com.averycorp.prismtask.ui.theme.prismCardBackground

/**
 * Today-screen habit row in a task-like checkbox style. Tapping the
 * checkbox toggles today's completion; tapping the card body opens the
 * habit detail. Mirrors the visual language of [SwipeableTaskItem] so
 * habits and tasks sit side-by-side without a style mismatch.
 */
@Composable
internal fun TodayHabitCheckItem(
    habitWithStatus: HabitWithStatus,
    onToggle: () -> Unit,
    onSkipToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val habit = habitWithStatus.habit
    val colors = LocalPrismColors.current
    val attrs = LocalPrismAttrs.current
    val fonts = LocalPrismFonts.current.body
    val haptics = LocalHapticFeedback.current
    val isSkipped = habitWithStatus.isSkippedToday
    val isComplete = habitWithStatus.isCompletedToday && !isSkipped

    val habitColor = remember(habit.color, colors.primary) {
        try {
            Color(android.graphics.Color.parseColor(habit.color))
        } catch (_: Exception) {
            colors.primary
        }
    }
    val borderAlpha = LocalHabitBorderBrightness.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = colors.primary.copy(alpha = borderAlpha),
                shape = MaterialTheme.shapes.medium
            )
            .prismCardBackground()
            .cornerBrackets(colors.primary, attrs),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (attrs.sunset) Color.Transparent else colors.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularCheckbox(
                checked = isComplete,
                skipped = isSkipped,
                onCheckedChange = {
                    if (isSkipped) onSkipToggle() else onToggle()
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSkipToggle()
                },
                checkedColor = habitColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(habitColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = habit.icon, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = fonts,
                    fontWeight = FontWeight.Medium,
                    color = if (isComplete || isSkipped) colors.muted else colors.onBackground,
                    textDecoration = if (isComplete) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val frequencyLabel = frequencyLabel(
                        period = habit.frequencyPeriod,
                        dailyTarget = habitWithStatus.dailyTarget,
                        completionsToday = habitWithStatus.completionsToday,
                        completionsThisWeek = habitWithStatus.completionsThisWeek,
                        target = habit.targetFrequency
                    )
                    TerminalLabel(
                        text = frequencyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted
                    )
                    if (habit.showStreak && habitWithStatus.currentStreak > 0) {
                        StreakBadge(streak = habitWithStatus.currentStreak)
                    }
                }
            }
        }
    }
}

private fun frequencyLabel(
    period: String,
    dailyTarget: Int,
    completionsToday: Int,
    completionsThisWeek: Int,
    target: Int
): String = when (period) {
    "daily" -> if (dailyTarget > 1) {
        "$completionsToday/$dailyTarget today"
    } else {
        "Daily"
    }
    "weekly" -> "$completionsThisWeek/$target this week"
    "fortnightly" -> "$completionsThisWeek/$target this fortnight"
    "monthly" -> "$completionsThisWeek/$target this month"
    "bimonthly" -> "$completionsThisWeek/$target this period"
    "quarterly" -> "$completionsThisWeek/$target this quarter"
    else -> "$completionsThisWeek done"
}
