package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import com.averycorp.prismtask.ui.theme.TerminalLabel
import com.averycorp.prismtask.ui.theme.cornerBrackets
import com.averycorp.prismtask.ui.theme.prismCardBackground
import com.averycorp.prismtask.ui.theme.prismGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Today-screen task row with swipe-to-complete (right) and swipe-to-
 * defer-to-tomorrow (left) gestures, plus an overflow menu with the
 * usual task actions. Rendered in the "Overdue", "Up Next", and
 * "Planned" sections of the Today screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SwipeableTaskItem(
    task: TaskEntity,
    tags: List<TagEntity>,
    isOverdue: Boolean = false,
    isPlanned: Boolean = false,
    onComplete: () -> Unit,
    onClick: () -> Unit,
    onReschedule: () -> Unit = {},
    onMoveToProject: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onDelete: () -> Unit = {},
    onMoveToTomorrow: () -> Unit = {}
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val colors = LocalPrismColors.current
    val attrs = LocalPrismAttrs.current
    val fonts = LocalPrismFonts.current.body
    val tomorrowBlue = colors.secondary
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    try {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    } catch (
                        _: Exception
                    ) {
                    }
                    onComplete()
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    try {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    } catch (
                        _: Exception
                    ) {
                    }
                    onMoveToTomorrow()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    val isSwiping = dismissState.dismissDirection != SwipeToDismissBoxValue.Settled
    val iconScale by animateFloatAsState(
        targetValue = if (isSwiping) 1.2f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "swipe_icon_scale"
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val backgroundColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> colors.primary
                SwipeToDismissBoxValue.EndToStart -> tomorrowBlue
                else -> Color.Transparent
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Check
                SwipeToDismissBoxValue.EndToStart -> Icons.AutoMirrored.Filled.ArrowForward
                else -> Icons.Default.Check
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backgroundColor != Color.Transparent) Modifier.prismGlow(backgroundColor, attrs.glow) else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .background(backgroundColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (direction == SwipeToDismissBoxValue.EndToStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Tomorrow",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.scale(iconScale)
                        )
                    }
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.scale(iconScale)
                    )
                }
            }
        }
    ) {
        val isUrgent = task.priority >= 4
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .border(
                    width = 1.dp,
                    color = colors.border,
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
                    checked = false,
                    onCheckedChange = { onComplete() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = fonts,
                        fontWeight = FontWeight.Medium,
                        color = colors.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (task.priority > 0) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isUrgent) colors.urgentAccent else colors.primary
                                    )
                            )
                        }
                        if (isOverdue && task.dueDate != null) {
                            val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
                            TerminalLabel(
                                text = fmt.format(Date(task.dueDate)),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted
                            )
                        }
                        if (isPlanned && task.dueDate != null) {
                            val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
                            TerminalLabel(
                                text = "Due: ${fmt.format(Date(task.dueDate))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted
                            )
                        }
                        if (isPlanned) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Planned",
                                modifier = Modifier.size(12.dp),
                                tint = colors.muted
                            )
                        }
                        tags.take(3).forEach { tag ->
                            val tagIsUrgent = tag.name.equals("urgent", ignoreCase = true)
                            val pillBg = if (tagIsUrgent) colors.urgentSurface else colors.tagSurface
                            val pillFg = if (tagIsUrgent) colors.urgentAccent else colors.tagText
                            Box(
                                modifier = Modifier
                                    .clip(LocalPrismShapes.current.chip)
                                    .background(pillBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "#${tag.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = fonts,
                                    color = pillFg
                                )
                            }
                        }
                    }
                }
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More Actions",
                            modifier = Modifier.size(18.dp),
                            tint = colors.muted
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("\uD83D\uDCC5  Reschedule") },
                            onClick = {
                                showOverflowMenu = false
                                onReschedule()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("\uD83D\uDCC1  Move To Project") },
                            onClick = {
                                showOverflowMenu = false
                                onMoveToProject()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("\uD83D\uDCCB  Duplicate") },
                            onClick = {
                                showOverflowMenu = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("\uD83D\uDDD1\uFE0F  Delete") },
                            onClick = {
                                showOverflowMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimal task row shown inside the "Completed" section on the Today
 * screen — title with line-through and a filled checkbox. For
 * recurring, non-habit-backed tasks the row taps into a small choice
 * dialog so the user can either log another completion or roll the
 * task back to incomplete; for one-off tasks the tap goes straight to
 * uncomplete.
 */
@Composable
internal fun CompletedTaskItem(
    task: TaskEntity,
    onUncomplete: () -> Unit,
    canLogAgain: Boolean = false,
    onLogAgain: () -> Unit = {}
) {
    val colors = LocalPrismColors.current
    val fonts = LocalPrismFonts.current.body
    var showChoiceDialog by remember { mutableStateOf(false) }
    val handleTap: () -> Unit = {
        if (canLogAgain) showChoiceDialog = true else onUncomplete()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { handleTap() }
            .border(
                width = 1.dp,
                color = colors.border,
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = colors.surface.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularCheckbox(checked = true, onCheckedChange = { handleTap() })
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = task.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = fonts,
                textDecoration = TextDecoration.LineThrough,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (canLogAgain) {
                IconButton(
                    onClick = onLogAgain,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = "Log Again",
                        modifier = Modifier.size(18.dp),
                        tint = colors.primary
                    )
                }
            }
        }
    }
    if (showChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showChoiceDialog = false },
            title = { Text("Already Completed") },
            text = {
                Text("\"${task.title}\" is already checked off for today. Log another completion or mark it incomplete?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showChoiceDialog = false
                    onLogAgain()
                }) {
                    Text("Log Again")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showChoiceDialog = false
                    onUncomplete()
                }) {
                    Text("Mark Incomplete")
                }
            }
        )
    }
}
