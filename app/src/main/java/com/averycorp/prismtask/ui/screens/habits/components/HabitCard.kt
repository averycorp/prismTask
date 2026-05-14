package com.averycorp.prismtask.ui.screens.habits.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.ui.components.StreakBadge
import com.averycorp.prismtask.ui.theme.ChipShape
import com.averycorp.prismtask.ui.theme.LocalHabitBorderBrightness
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.TerminalLabel

/**
 * The main habit list card. Shows the habit's icon, name, weekly-progress
 * dots, streak badge, booking/previous-period status pills (for recurring
 * habits that opt in), and a tap-to-complete / long-press-to-decrement
 * circular counter on the right. Edit and delete overflow buttons are
 * inlined for quick access.
 *
 * Per-theme flourishes:
 * - CYBERPUNK: 3dp colored left border strip + square icon box with glow.
 * - MATRIX:    `▸` terminal marker before the icon + square icon box.
 * - SYNTHWAVE: Circular icon box + glow ring.
 * - VOID:      Circular icon box, no glow, generous padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HabitItem(
    habitWithStatus: HabitWithStatus,
    onToggle: () -> Unit,
    onDecrement: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val habit = habitWithStatus.habit
    val colors = LocalPrismColors.current
    val fonts = LocalPrismFonts.current.body
    val attrs = LocalPrismAttrs.current
    val displayFont = LocalPrismFonts.current.display

    val habitColor = try {
        Color(android.graphics.Color.parseColor(habit.color))
    } catch (_: Exception) {
        colors.primary
    }
    val isComplete = habitWithStatus.isCompletedToday

    val scale by animateFloatAsState(
        targetValue = 1.0f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 300f),
        label = "checkScale"
    )

    val cardShape = RoundedCornerShape(attrs.cardRadius.dp)

    val borderAlpha = LocalHabitBorderBrightness.current
    // Cyberpunk: left border strip colored by habit color
    val cardModifier = modifier
        .fillMaxWidth()
        .clickable { onClick() }
        .border(
            width = 1.dp,
            color = colors.primary.copy(alpha = borderAlpha),
            shape = cardShape
        )

    Card(
        modifier = cardModifier,
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isComplete) colors.surfaceVariant else colors.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // Cyberpunk: accent left border drawn inside the card
                    if (attrs.brackets) {
                        Modifier.border(
                            androidx.compose.foundation.BorderStroke(
                                width = 3.dp,
                                color = habitColor
                            ),
                            shape = cardShape
                        ).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
                    } else {
                        Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Matrix: terminal bullet marker before the icon
            if (attrs.terminal) {
                Text(
                    text = "▸",
                    color = habitColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Icon container — shape driven by chipShape token
            val iconShape = when {
                attrs.chipShape == ChipShape.SHARP -> RoundedCornerShape(if (attrs.terminal) 0.dp else 6.dp)
                else -> CircleShape
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(iconShape)
                    .background(habitColor.copy(alpha = 0.15f))
                    .then(
                        // Cyberpunk: subtle glow border on icon box
                        if (attrs.brackets) {
                            Modifier.border(1.dp, habitColor.copy(alpha = 0.4f), iconShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = habit.icon,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name + streak info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = habit.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = if (attrs.editorial) displayFont else fonts,
                        fontWeight = if (attrs.editorial) FontWeight.Medium else FontWeight.SemiBold,
                        color = if (isComplete) colors.primary else colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val periodLabel = when (habit.frequencyPeriod) {
                        "weekly" -> "this week"
                        "fortnightly" -> "this fortnight"
                        "monthly" -> "this month"
                        "bimonthly" -> "this period"
                        "quarterly" -> "this quarter"
                        else -> "this week"
                    }
                    if (habit.frequencyPeriod == "daily" && habitWithStatus.dailyTarget > 1) {
                        TerminalLabel(
                            text = "${habitWithStatus.completionsToday}/${habitWithStatus.dailyTarget} today",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted
                        )
                        if (habit.showStreak && habitWithStatus.currentStreak > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            StreakBadge(streak = habitWithStatus.currentStreak)
                        }
                    } else if (habit.showStreak && habitWithStatus.currentStreak > 0) {
                        StreakBadge(streak = habitWithStatus.currentStreak)
                        Spacer(modifier = Modifier.width(4.dp))
                        TerminalLabel(
                            text = "${habitWithStatus.completionsThisWeek} days this week",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted
                        )
                    } else {
                        TerminalLabel(
                            text = "${habitWithStatus.completionsThisWeek} done $periodLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                val dotsTarget = when (habit.frequencyPeriod) {
                    "daily" -> 7
                    else -> habit.targetFrequency.coerceAtMost(7)
                }
                WeeklyDots(
                    completionsThisWeek = habitWithStatus.completionsThisWeek,
                    target = dotsTarget,
                    color = habitColor
                )

                if (habit.frequencyPeriod != "daily" &&
                    (habit.trackBooking || habit.trackPreviousPeriod)
                ) {
                    val periodNoun = when (habit.frequencyPeriod) {
                        "weekly" -> "week"
                        "fortnightly" -> "fortnight"
                        "monthly" -> "month"
                        "bimonthly" -> "period"
                        "quarterly" -> "quarter"
                        else -> "period"
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (habit.trackBooking) {
                            StatusPill(
                                label = if (habitWithStatus.isBookedThisPeriod) {
                                    if (habitWithStatus.bookedTasksThisPeriod > 1) {
                                        "Booked (${habitWithStatus.bookedTasksThisPeriod})"
                                    } else {
                                        "Booked"
                                    }
                                } else {
                                    "Not Booked"
                                },
                                active = habitWithStatus.isBookedThisPeriod,
                                activeColor = habitColor
                            )
                        }
                        if (habit.trackPreviousPeriod) {
                            val periodTitle = periodNoun.replaceFirstChar { it.uppercase() }
                            StatusPill(
                                label = if (habitWithStatus.previousPeriodMet) {
                                    "Last $periodTitle Done"
                                } else {
                                    "Last $periodTitle Missed"
                                },
                                active = habitWithStatus.previousPeriodMet,
                                activeColor = habitColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            if (habit.hasLogging) {
                IconButton(onClick = onLog, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.EditNote,
                        contentDescription = "Log activity",
                        modifier = Modifier.size(20.dp),
                        tint = habitColor
                    )
                }
            }

            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit habit",
                    modifier = Modifier.size(18.dp),
                    tint = colors.muted.copy(alpha = 0.6f)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete habit",
                    modifier = Modifier.size(18.dp),
                    tint = colors.muted.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Circular checkbox / counter; shape respects chipShape token
            val checkShape = if (attrs.chipShape == ChipShape.SHARP && attrs.terminal) {
                RoundedCornerShape(0.dp)
            } else {
                CircleShape
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .scale(scale)
                    .clip(checkShape)
                    .then(
                        if (isComplete) {
                            Modifier.background(habitColor)
                        } else if (habitWithStatus.completionsToday > 0) {
                            Modifier.background(habitColor.copy(alpha = 0.3f))
                        } else {
                            Modifier.border(2.dp, habitColor, checkShape)
                        }
                    )
                    .pointerInput(isComplete, habitWithStatus.completionsToday) {
                        detectTapGestures(
                            onTap = { onToggle() },
                            onLongPress = { onDecrement() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isComplete) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (habitWithStatus.dailyTarget > 1 && habitWithStatus.completionsToday > 0) {
                    Text(
                        text = "${habitWithStatus.completionsToday}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
