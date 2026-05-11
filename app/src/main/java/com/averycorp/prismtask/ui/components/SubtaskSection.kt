package com.averycorp.prismtask.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import sh.calvin.reorderable.ReorderableColumn

/**
 * Result of the minimal subtask NLP parsing. We intentionally keep the parsed
 * tokens in the displayed title so that the user can still see them after
 * creation - full subtask scheduling is not supported yet.
 */
data class ParsedSubtaskInput(val title: String, val priority: Int)

/**
 * Parses minimal NLP out of a raw subtask input string.
 *
 * - A leading run of `!` characters sets the priority (1..4 capped). The marker
 *   is stripped from the resulting title.
 * - Date references like "by Friday" or "by tomorrow" are left in the title as
 *   plain text for now (full subtask scheduling will come later).
 */
fun parseSubtaskInput(raw: String): ParsedSubtaskInput {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ParsedSubtaskInput(title = "", priority = 0)

    var priority = 0
    var title = trimmed
    if (title.startsWith("!")) {
        val bangCount = title.takeWhile { it == '!' }.length
        priority = bangCount.coerceAtMost(4)
        title = title.drop(bangCount).trimStart()
    }

    if (title.isEmpty()) title = trimmed
    return ParsedSubtaskInput(title = title, priority = priority)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtaskSection(
    parentTaskId: Long,
    subtasks: List<TaskEntity>,
    onToggleComplete: (subtaskId: Long, isCompleted: Boolean) -> Unit,
    onAddSubtask: (title: String, parentTaskId: Long, priority: Int) -> Unit,
    onDeleteSubtask: (subtaskId: Long) -> Unit,
    onReorderSubtasks: (parentTaskId: Long, orderedIds: List<Long>) -> Unit,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    requestFocus: Boolean = false,
    onFocusHandled: () -> Unit = {}
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "arrow_rotation"
    )

    // Local ordering so the drag animation can update immediately; we push the
    // final order to the parent on settle and then sync back whenever the
    // upstream list changes.
    var orderedSubtasks by remember { mutableStateOf(subtasks) }
    LaunchedEffect(subtasks) { orderedSubtasks = subtasks }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .animateContentSize()
    ) {
        if (subtasks.isNotEmpty()) {
            val completed = subtasks.count { it.isCompleted }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "\uD83D\uDCCB $completed/${subtasks.size} Subtask${if (subtasks.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                ReorderableColumn(
                    list = orderedSubtasks,
                    onSettle = { fromIndex, toIndex ->
                        val mutable = orderedSubtasks.toMutableList()
                        val moved = mutable.removeAt(fromIndex)
                        mutable.add(toIndex, moved)
                        orderedSubtasks = mutable
                        onReorderSubtasks(parentTaskId, mutable.map { it.id })
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { _, subtask, isDragging ->
                    val elevation = if (isDragging) 6.dp else 0.dp
                    val dragHandle = Modifier.longPressDraggableHandle()
                    SwipeableSubtaskRow(
                        subtask = subtask,
                        onToggleComplete = { onToggleComplete(subtask.id, subtask.isCompleted) },
                        onDelete = { onDeleteSubtask(subtask.id) },
                        dragHandleModifier = dragHandle,
                        modifier = Modifier.shadow(elevation, MaterialTheme.shapes.medium)
                    )
                }
                AddSubtaskRow(
                    onAdd = { parsed ->
                        onAddSubtask(parsed.title, parentTaskId, parsed.priority)
                    },
                    requestFocus = requestFocus,
                    onFocusHandled = onFocusHandled
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSubtaskRow(
    subtask: TaskEntity,
    onToggleComplete: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (!subtask.isCompleted) onToggleComplete()
                    // Don't actually dismiss - we just want to toggle complete
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val prismColors = LocalPrismColors.current
            val backgroundColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> prismColors.swipeComplete
                SwipeToDismissBoxValue.EndToStart -> prismColors.swipeDelete
                else -> Color.Transparent
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Check
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                else -> Icons.Default.Check
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(backgroundColor)
                    .padding(horizontal = 16.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    ) {
        SubtaskRow(
            subtask = subtask,
            onToggleComplete = onToggleComplete,
            dragHandleModifier = dragHandleModifier
        )
    }
}

@Composable
private fun SubtaskRow(
    subtask: TaskEntity,
    onToggleComplete: () -> Unit,
    dragHandleModifier: Modifier
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = "Drag To Reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = dragHandleModifier
                .size(24.dp)
        )
        CircularCheckbox(
            checked = subtask.isCompleted,
            onCheckedChange = { onToggleComplete() },
            size = 22.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = subtask.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (subtask.isCompleted) TextDecoration.LineThrough else null,
            color = if (subtask.isCompleted) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        PriorityDot(subtask.priority)
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun PriorityDot(priority: Int) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(LocalPriorityColors.current.forLevel(priority))
    )
}

@Composable
private fun AddSubtaskRow(
    onAdd: (ParsedSubtaskInput) -> Unit,
    requestFocus: Boolean = false,
    onFocusHandled: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            onFocusHandled()
        }
    }

    val submit = {
        val parsed = parseSubtaskInput(text)
        if (parsed.title.isNotBlank()) {
            onAdd(parsed)
            text = ""
            // Keep focus so the user can rapidly type additional subtasks
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text(
                    "Add Subtask...",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
        )
        IconButton(
            onClick = submit,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add Subtask",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
