package com.averycorp.prismtask.ui.screens.tasklist.components

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.SwipePrefs
import com.averycorp.prismtask.ui.components.SubtaskSection
import com.averycorp.prismtask.ui.screens.tasklist.TaskListViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

/**
 * Information needed to show the "Duplicate task?" dialog. Surfaced
 * as a parameter-less callback so the screen composable can keep its
 * own state for which task is currently being confirmed.
 */
internal data class DuplicateDialogState(
    val taskId: Long,
    val dueDate: Long?,
    val subtaskCount: Int
)

/**
 * LazyListScope extension that emits a task card + its subtask
 * section inside a drag-reorder wrapper ([ReorderableItem]). The card
 * picks up a drag shadow, scales slightly, and fades during drag.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
internal fun LazyListScope.reorderableTaskItemWithSubtasks(
    task: TaskEntity,
    projects: List<ProjectEntity>,
    subtasksMap: Map<Long, List<TaskEntity>>,
    taskTagsMap: Map<Long, List<TagEntity>>,
    attachmentCountMap: Map<Long, Int>,
    expandedTaskIds: Set<Long>,
    focusSubtaskForId: Long?,
    onTaskClick: (Long) -> Unit,
    onReschedule: (TaskEntity) -> Unit,
    onMoveToProject: (TaskEntity) -> Unit,
    viewModel: TaskListViewModel,
    isMultiSelectMode: Boolean,
    selectedTaskIds: Set<Long>,
    onExpandChange: (Set<Long>) -> Unit,
    onFocusChange: (Long?) -> Unit,
    reorderState: ReorderableLazyListState,
    onDragEnd: () -> Unit,
    onDuplicate: (DuplicateDialogState) -> Unit
) {
    val subtasks = subtasksMap[task.id].orEmpty()
    val tags = taskTagsMap[task.id].orEmpty()
    val attachmentCount = attachmentCountMap[task.id] ?: 0
    val project = projects.find { it.id == task.projectId }

    item(key = task.id) {
        ReorderableItem(reorderState, key = task.id) { isDragging ->
            val elevation = if (isDragging) 8.dp else 0.dp
            val scale = if (isDragging) 1.02f else 1f
            val alpha = if (isDragging) 0.85f else 1f

            if (isMultiSelectMode) {
                TaskItem(
                    task = task,
                    project = project,
                    subtasks = subtasks,
                    tags = tags,
                    attachmentCount = attachmentCount,
                    isSelected = task.id in selectedTaskIds,
                    isMultiSelectMode = true,
                    onToggleComplete = { viewModel.onToggleTaskSelection(task.id) },
                    onClick = { viewModel.onToggleTaskSelection(task.id) },
                    onLongClick = { viewModel.onToggleTaskSelection(task.id) },
                    onAddSubtaskClick = {}
                )
            } else {
                TaskItem(
                    task = task,
                    project = project,
                    subtasks = subtasks,
                    tags = tags,
                    attachmentCount = attachmentCount,
                    onToggleComplete = {
                        if (task.isCompleted) {
                            viewModel.onToggleComplete(task.id, true)
                        } else {
                            viewModel.onCompleteTaskWithUndo(task.id)
                        }
                    },
                    onClick = { onTaskClick(task.id) },
                    onAddSubtaskClick = {
                        onExpandChange(expandedTaskIds + task.id)
                        onFocusChange(task.id)
                    },
                    onDuplicate = {
                        onDuplicate(
                            DuplicateDialogState(
                                taskId = task.id,
                                dueDate = task.dueDate,
                                subtaskCount = subtasks.size
                            )
                        )
                    },
                    showDragHandle = true,
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStopped = { onDragEnd() }
                    ),
                    modifier = Modifier
                        .shadow(elevation, MaterialTheme.shapes.medium)
                        .scale(scale)
                        .alpha(alpha)
                )
            }
        }
    }
    if (subtasks.isNotEmpty() || expandedTaskIds.contains(task.id)) {
        item(key = "subtasks_${task.id}") {
            SubtaskSection(
                parentTaskId = task.id,
                subtasks = subtasks,
                onToggleComplete = viewModel::onToggleSubtaskComplete,
                onAddSubtask = { title, parentId, priority ->
                    viewModel.onAddSubtask(title, parentId, priority)
                },
                onDeleteSubtask = viewModel::onDeleteSubtaskWithUndo,
                onReorderSubtasks = viewModel::onReorderSubtasks,
                expanded = expandedTaskIds.contains(task.id),
                onToggleExpand = {
                    onExpandChange(
                        if (expandedTaskIds.contains(task.id)) {
                            expandedTaskIds - task.id
                        } else {
                            expandedTaskIds + task.id
                        }
                    )
                },
                requestFocus = focusSubtaskForId == task.id,
                onFocusHandled = { onFocusChange(null) }
            )
        }
    }
}

/**
 * LazyListScope extension that emits a task card + its subtask
 * section with a [SwipeToDismissBox] wrapper. The left/right swipe
 * actions are driven by the user's [SwipePrefs] and dispatch into
 * complete / delete / reschedule / archive / flag through [viewModel].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
internal fun LazyListScope.taskItemWithSubtasks(
    task: TaskEntity,
    projects: List<ProjectEntity>,
    subtasksMap: Map<Long, List<TaskEntity>>,
    taskTagsMap: Map<Long, List<TagEntity>>,
    attachmentCountMap: Map<Long, Int>,
    expandedTaskIds: Set<Long>,
    focusSubtaskForId: Long?,
    onTaskClick: (Long) -> Unit,
    onReschedule: (TaskEntity) -> Unit,
    onMoveToProject: (TaskEntity) -> Unit,
    viewModel: TaskListViewModel,
    isMultiSelectMode: Boolean,
    selectedTaskIds: Set<Long>,
    onExpandChange: (Set<Long>) -> Unit,
    onFocusChange: (Long?) -> Unit,
    onDuplicate: (DuplicateDialogState) -> Unit,
    swipePrefs: SwipePrefs
) {
    val subtasks = subtasksMap[task.id].orEmpty()
    val tags = taskTagsMap[task.id].orEmpty()
    val attachmentCount = attachmentCountMap[task.id] ?: 0
    item(key = task.id) {
        val project = projects.find { it.id == task.projectId }

        if (isMultiSelectMode) {
            TaskItem(
                task = task,
                project = project,
                subtasks = subtasks,
                tags = tags,
                attachmentCount = attachmentCount,
                isSelected = task.id in selectedTaskIds,
                isMultiSelectMode = true,
                onToggleComplete = { viewModel.onToggleTaskSelection(task.id) },
                onClick = { viewModel.onToggleTaskSelection(task.id) },
                onLongClick = { viewModel.onToggleTaskSelection(task.id) },
                onAddSubtaskClick = {}
            )
        } else {
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    val action = when (value) {
                        SwipeToDismissBoxValue.StartToEnd -> swipePrefs.right
                        SwipeToDismissBoxValue.EndToStart -> swipePrefs.left
                        SwipeToDismissBoxValue.Settled -> com.averycorp.prismtask.domain.model.SwipeAction.NONE
                    }
                    if (action == com.averycorp.prismtask.domain.model.SwipeAction.NONE) return@rememberSwipeToDismissBoxState false
                    try {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    } catch (
                        _: Exception
                    ) {
                    }
                    com.averycorp.prismtask.ui.components.dispatchSwipeAction(
                        action = action,
                        taskId = task.id,
                        onComplete = { viewModel.onCompleteTaskWithUndo(it) },
                        onDelete = { viewModel.onDeleteTaskWithUndo(it) },
                        onReschedule = { viewModel.onMoveToTomorrow(it) },
                        onArchive = { viewModel.onArchiveTask(it) },
                        onMoveToProject = {
                            // project picker is a larger lift — fall through to move-to-tomorrow for now
                            viewModel.onMoveToTomorrow(it)
                        },
                        onToggleFlag = { viewModel.onToggleFlag(it) }
                    )
                }
            )

            val swipeIconScale by animateFloatAsState(
                targetValue = if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) 1.2f else 0.8f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
                ),
                label = "swipe_icon_scale"
            )

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val direction = dismissState.dismissDirection
                    val action = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> swipePrefs.right
                        SwipeToDismissBoxValue.EndToStart -> swipePrefs.left
                        else -> com.averycorp.prismtask.domain.model.SwipeAction.NONE
                    }
                    val style = com.averycorp.prismtask.ui.components
                        .swipeActionStyle(action)
                    val backgroundColor = style.backgroundColor
                    val icon = style.icon ?: Icons.Default.Check
                    val alignment = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                        else -> Alignment.CenterEnd
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(backgroundColor)
                            .padding(horizontal = 20.dp),
                        contentAlignment = alignment
                    ) {
                        if (direction == SwipeToDismissBoxValue.EndToStart && style.label.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = style.label,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.scale(swipeIconScale)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.scale(swipeIconScale)
                            )
                        }
                    }
                }
            ) {
                TaskItem(
                    task = task,
                    project = project,
                    subtasks = subtasks,
                    tags = tags,
                    attachmentCount = attachmentCount,
                    onToggleComplete = {
                        if (task.isCompleted) {
                            viewModel.onToggleComplete(task.id, true)
                        } else {
                            viewModel.onCompleteTaskWithUndo(task.id)
                        }
                    },
                    onClick = { onTaskClick(task.id) },
                    onLongClick = { viewModel.onEnterMultiSelect(task.id) },
                    onAddSubtaskClick = {
                        onExpandChange(expandedTaskIds + task.id)
                        onFocusChange(task.id)
                    },
                    onDuplicate = {
                        onDuplicate(
                            DuplicateDialogState(
                                taskId = task.id,
                                dueDate = task.dueDate,
                                subtaskCount = subtasks.size
                            )
                        )
                    }
                )
            }
        }
    }
    if (subtasks.isNotEmpty() || expandedTaskIds.contains(task.id)) {
        item(key = "subtasks_${task.id}") {
            SubtaskSection(
                parentTaskId = task.id,
                subtasks = subtasks,
                onToggleComplete = viewModel::onToggleSubtaskComplete,
                onAddSubtask = { title, parentId, priority ->
                    viewModel.onAddSubtask(title, parentId, priority)
                },
                onDeleteSubtask = viewModel::onDeleteSubtaskWithUndo,
                onReorderSubtasks = viewModel::onReorderSubtasks,
                expanded = expandedTaskIds.contains(task.id),
                onToggleExpand = {
                    onExpandChange(
                        if (expandedTaskIds.contains(task.id)) {
                            expandedTaskIds - task.id
                        } else {
                            expandedTaskIds + task.id
                        }
                    )
                },
                requestFocus = focusSubtaskForId == task.id,
                onFocusHandled = { onFocusChange(null) }
            )
        }
    }
}

/**
 * Variant of [taskItemWithSubtasks] used inside the By Project view. The
 * card is a drag-and-drop source: long-press starts a native drag whose
 * ClipData carries this task's id, and the card itself acts as a drop
 * target so dropping another task on top of it moves that task into this
 * card's project. Long-press still opens the context menu via the normal
 * combinedClickable path — only the actual drag gesture (long-press +
 * move) triggers [dragAndDropSource].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
internal fun LazyListScope.draggableTaskItemWithSubtasks(
    task: TaskEntity,
    projects: List<ProjectEntity>,
    subtasksMap: Map<Long, List<TaskEntity>>,
    taskTagsMap: Map<Long, List<TagEntity>>,
    attachmentCountMap: Map<Long, Int>,
    expandedTaskIds: Set<Long>,
    focusSubtaskForId: Long?,
    onTaskClick: (Long) -> Unit,
    onReschedule: (TaskEntity) -> Unit,
    onMoveToProject: (TaskEntity) -> Unit,
    onDropTask: (Long) -> Unit,
    viewModel: TaskListViewModel,
    isMultiSelectMode: Boolean,
    selectedTaskIds: Set<Long>,
    onExpandChange: (Set<Long>) -> Unit,
    onFocusChange: (Long?) -> Unit,
    onDuplicate: (DuplicateDialogState) -> Unit
) {
    val subtasks = subtasksMap[task.id].orEmpty()
    val tags = taskTagsMap[task.id].orEmpty()
    val attachmentCount = attachmentCountMap[task.id] ?: 0
    val project = projects.find { it.id == task.projectId }
    item(key = "proj_task_${task.id}") {
        var isHovered by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (isHovered) 1.02f else 1f,
            label = "taskDragScale"
        )

        val dropTarget = remember(task.id, task.projectId) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    isHovered = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    isHovered = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    isHovered = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val clipItem = event
                        .toAndroidDragEvent()
                        .clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                    val droppedId = clipItem?.text?.toString()?.toLongOrNull()
                    isHovered = false
                    if (droppedId == null || droppedId == task.id) return false
                    onDropTask(droppedId)
                    return true
                }
            }
        }

        // Drag is initiated via the explicit drag handle so that the task
        // card's long-press gesture remains free to open the shared
        // context menu. The whole card stays a drop target so users can
        // release a dragged card on top of any task in the destination
        // project — not just on the project header. The drag shadow is
        // a small semi-transparent rounded rect so the user sees that a
        // drag is in progress — native Android handles the actual
        // follow-the-pointer movement on top of that decoration.
        val dragShadowColor = MaterialTheme.colorScheme.primary
        val dragHandleDragModifier = Modifier.dragAndDropSource(
            drawDragDecoration = {
                drawRoundRect(
                    color = dragShadowColor,
                    alpha = 0.5f,
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                )
            },
            block = {
                detectTapGestures(
                    onLongPress = {
                        startTransfer(
                            DragAndDropTransferData(
                                clipData = ClipData.newPlainText(
                                    "task_id",
                                    task.id.toString()
                                )
                            )
                        )
                    }
                )
            }
        )

        val dragModifier = Modifier
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                },
                target = dropTarget
            ).shadow(if (isHovered) 6.dp else 0.dp, MaterialTheme.shapes.medium)
            .scale(scale)

        if (isMultiSelectMode) {
            TaskItem(
                task = task,
                project = project,
                subtasks = subtasks,
                tags = tags,
                attachmentCount = attachmentCount,
                isSelected = task.id in selectedTaskIds,
                isMultiSelectMode = true,
                onToggleComplete = { viewModel.onToggleTaskSelection(task.id) },
                onClick = { viewModel.onToggleTaskSelection(task.id) },
                onLongClick = { viewModel.onToggleTaskSelection(task.id) },
                onAddSubtaskClick = {},
                showDragHandle = true,
                dragHandleModifier = dragHandleDragModifier,
                modifier = dragModifier
            )
        } else {
            TaskItem(
                task = task,
                project = project,
                subtasks = subtasks,
                tags = tags,
                attachmentCount = attachmentCount,
                onToggleComplete = {
                    if (task.isCompleted) {
                        viewModel.onToggleComplete(task.id, true)
                    } else {
                        viewModel.onCompleteTaskWithUndo(task.id)
                    }
                },
                onClick = { onTaskClick(task.id) },
                onLongClick = { viewModel.onEnterMultiSelect(task.id) },
                onAddSubtaskClick = {
                    onExpandChange(expandedTaskIds + task.id)
                    onFocusChange(task.id)
                },
                onDuplicate = {
                    onDuplicate(
                        DuplicateDialogState(
                            taskId = task.id,
                            dueDate = task.dueDate,
                            subtaskCount = subtasks.size
                        )
                    )
                },
                showDragHandle = true,
                dragHandleModifier = dragHandleDragModifier,
                modifier = dragModifier
            )
        }
    }
    if (subtasks.isNotEmpty() || expandedTaskIds.contains(task.id)) {
        item(key = "proj_subtasks_${task.id}") {
            SubtaskSection(
                parentTaskId = task.id,
                subtasks = subtasks,
                onToggleComplete = viewModel::onToggleSubtaskComplete,
                onAddSubtask = { title, parentId, priority ->
                    viewModel.onAddSubtask(title, parentId, priority)
                },
                onDeleteSubtask = viewModel::onDeleteSubtaskWithUndo,
                onReorderSubtasks = viewModel::onReorderSubtasks,
                expanded = expandedTaskIds.contains(task.id),
                onToggleExpand = {
                    onExpandChange(
                        if (expandedTaskIds.contains(task.id)) {
                            expandedTaskIds - task.id
                        } else {
                            expandedTaskIds + task.id
                        }
                    )
                },
                requestFocus = focusSubtaskForId == task.id,
                onFocusHandled = { onFocusChange(null) }
            )
        }
    }
}
