package com.averycorp.prismtask.ui.screens.projects.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.model.ProjectStatus
import com.averycorp.prismtask.domain.model.ProjectWithProgress
import com.averycorp.prismtask.ui.components.StreakBadge
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * List-row card for a [ProjectWithProgress] in the Projects pane.
 *
 * Theme color shows as a subtle left-edge accent stripe (not a full fill)
 * so it reads as a project identifier rather than stealing focus from the
 * text. Everything else flows through [LocalPrismColors] so the card
 * reskins cleanly across Cyberpunk / Synthwave / Matrix / Void.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectCard(
    data: ProjectWithProgress,
    onClick: () -> Unit,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onArchive: (() -> Unit)? = null,
    onReopen: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null
) {
    val prismColors = LocalPrismColors.current
    val accent = parseAccentColor(data.project.themeColorKey ?: data.project.color, prismColors.primary)
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val supportsContextMenu =
        onArchive != null || onReopen != null || onComplete != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = if (supportsContextMenu) {
                    { menuOpen = true }
                } else {
                    null
                }
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = prismColors.surface,
            contentColor = prismColors.onSurface
        )
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Theme-color accent stripe
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(accent)
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header row: icon + name + status chip (if non-active) + streak
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = data.project.icon,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = data.project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = prismColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (data.status != ProjectStatus.ACTIVE) {
                        StatusChip(status = data.status)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    StreakBadge(streak = data.streak.resilientStreak)
                }

                // Progress bar — shows 0% when there are no milestones.
                LinearProgressIndicator(
                    progress = { data.milestoneProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = accent,
                    trackColor = prismColors.surfaceVariant
                )

                // Upcoming milestone line
                Text(
                    text = data.upcomingMilestoneTitle ?: "No Upcoming Milestones",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (data.upcomingMilestoneTitle == null) prismColors.muted else prismColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Footer row: task count + days-since-activity badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = taskCountLabel(open = data.openTasks, total = data.totalTasks),
                        style = MaterialTheme.typography.labelMedium,
                        color = prismColors.muted
                    )
                    val daysSince = data.daysSinceActivity(nowMillis)
                    if (daysSince != null && daysSince > 3) {
                        Spacer(Modifier.weight(1f))
                        DaysSinceBadge(days = daysSince)
                    }
                }

                // Long-press context menu anchored under the card content.
                // Active projects: Complete + Archive; non-active: Reopen.
                if (supportsContextMenu) {
                    Box {
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            when (data.status) {
                                ProjectStatus.ACTIVE -> {
                                    if (onComplete != null) {
                                        DropdownMenuItem(
                                            text = { Text("Mark Completed") },
                                            leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                                            onClick = {
                                                menuOpen = false
                                                onComplete()
                                            }
                                        )
                                    }
                                    if (onArchive != null) {
                                        DropdownMenuItem(
                                            text = { Text("Archive") },
                                            leadingIcon = { Icon(Icons.Default.Archive, null) },
                                            onClick = {
                                                menuOpen = false
                                                onArchive()
                                            }
                                        )
                                    }
                                }
                                ProjectStatus.COMPLETED, ProjectStatus.ARCHIVED -> {
                                    if (onReopen != null) {
                                        DropdownMenuItem(
                                            text = { Text("Reopen") },
                                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                            onClick = {
                                                menuOpen = false
                                                onReopen()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ProjectStatus) {
    val prismColors = LocalPrismColors.current
    val (label, bg, fg) = when (status) {
        ProjectStatus.COMPLETED -> Triple("Completed", prismColors.tagSurface, prismColors.primary)
        ProjectStatus.ARCHIVED -> Triple("Archived", prismColors.surfaceVariant, prismColors.muted)
        ProjectStatus.ACTIVE -> Triple("Active", prismColors.tagSurface, prismColors.primary)
    }
    Surface(
        shape = CircleShape,
        color = bg,
        contentColor = fg
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DaysSinceBadge(days: Int) {
    val prismColors = LocalPrismColors.current
    Surface(
        shape = CircleShape,
        color = prismColors.urgentSurface,
        contentColor = prismColors.urgentAccent
    ) {
        Text(
            text = "$days Days Since Activity",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun taskCountLabel(open: Int, total: Int): String {
    if (total == 0) return "No Tasks"
    val done = total - open
    return "$done Of $total Tasks"
}

/**
 * Best-effort hex → Compose Color. Falls back to [fallback] when the input
 * can't be parsed (malformed legacy hex, orphaned token keys, etc.).
 */
private fun parseAccentColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: IllegalArgumentException) {
        fallback
    }
}
