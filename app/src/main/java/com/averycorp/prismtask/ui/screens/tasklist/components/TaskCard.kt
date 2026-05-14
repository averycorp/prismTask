package com.averycorp.prismtask.ui.screens.tasklist.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * The main task card composable shown in every task list: renders the
 * title, checkbox, due-date label, indicator icons (reminder, recurrence,
 * notes, attachments), project and tag chips, a subtask-add action,
 * and an overflow menu with reschedule / move / duplicate / delete.
 *
 * Can be rendered inside a drag-reorder wrapper (via [showDragHandle]
 * + [dragHandleModifier]) or as a plain card. Multi-select mode swaps
 * the checkbox for a selection indicator and disables overflow actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TaskItem(
    task: TaskEntity,
    project: ProjectEntity?,
    subtasks: List<TaskEntity>,
    tags: List<TagEntity> = emptyList(),
    attachmentCount: Int = 0,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onToggleComplete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onAddSubtaskClick: () -> Unit,
    onReschedule: (() -> Unit)? = null,
    onMoveToProject: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val hasOverflowActions =
        !isMultiSelectMode && (onReschedule != null || onMoveToProject != null || onDuplicate != null || onDelete != null)
    val prismColors = LocalPrismColors.current
    val prismAttrs = LocalPrismAttrs.current
    val cardShape = RoundedCornerShape(prismAttrs.cardRadius.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> prismColors.primary.copy(alpha = 0.12f)
                else -> prismColors.surface
            }
        )
    ) {
        // Left accent stripe: project color when the task has a project, so
        // project membership reads as a strong visual signal on every theme.
        // On Cyberpunk-bracket themes urgent/high priority overrides — urgency
        // is the higher-stakes signal there.
        val projectStripColor = project?.let {
            try {
                Color(android.graphics.Color.parseColor(it.color))
            } catch (_: Exception) {
                null
            }
        }
        val stripColor = when {
            prismAttrs.brackets && task.priority == 4 -> prismColors.urgentAccent
            prismAttrs.brackets && task.priority == 3 -> prismColors.primary
            else -> projectStripColor
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (stripColor != null) {
                        val c = stripColor
                        Modifier.drawBehind {
                            drawRect(c, size = Size(3.dp.toPx(), size.height))
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(
                    start = if (stripColor != null) 7.dp else 4.dp,
                    end = 4.dp,
                    top = 8.dp,
                    bottom = 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDragHandle) {
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = "Drag To Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = dragHandleModifier.size(24.dp)
                )
            }
            CircularCheckbox(
                checked = if (isMultiSelectMode) isSelected else task.isCompleted,
                onCheckedChange = { onToggleComplete() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                    color = if (task.isCompleted) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    task.dueDate?.let { millis ->
                        val label = formatDueDate(millis)
                        Text(
                            text = label.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = label.color
                        )
                    }

                    if (task.reminderOffset != null) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Reminder set",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (task.recurrenceRule != null) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = "Recurring task",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!task.notes.isNullOrBlank()) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Has notes",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (attachmentCount > 0) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Has attachments",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (subtasks.isNotEmpty()) {
                        val completed = subtasks.count { it.isCompleted }
                        Text(
                            text = "$completed/${subtasks.size} subtasks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (project != null) {
                        ProjectChip(project)
                    }
                }

                if (tags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        val visibleTags = tags.take(3)
                        visibleTags.forEach { tag ->
                            TagChip(tag)
                        }
                        if (tags.size > 3) {
                            Text(
                                text = "+${tags.size - 3} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onAddSubtaskClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.AddTask,
                    contentDescription = "Add subtask",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            if (hasOverflowActions) {
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More Actions",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        if (onReschedule != null) {
                            DropdownMenuItem(
                                text = { Text("\uD83D\uDCC5  Reschedule") },
                                onClick = {
                                    showOverflowMenu = false
                                    onReschedule()
                                }
                            )
                        }
                        if (onMoveToProject != null) {
                            DropdownMenuItem(
                                text = { Text("\uD83D\uDCC1  Move To Project") },
                                onClick = {
                                    showOverflowMenu = false
                                    onMoveToProject()
                                }
                            )
                        }
                        if (onDuplicate != null) {
                            DropdownMenuItem(
                                text = { Text("\uD83D\uDCCB  Duplicate") },
                                onClick = {
                                    showOverflowMenu = false
                                    onDuplicate()
                                }
                            )
                        }
                        if (onDelete != null) {
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

            PriorityDot(task.priority)
            if (task.eisenhowerQuadrant != null) {
                Spacer(modifier = Modifier.width(3.dp))
                EisenhowerBadge(task.eisenhowerQuadrant)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
