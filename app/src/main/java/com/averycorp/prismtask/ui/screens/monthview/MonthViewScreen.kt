package com.averycorp.prismtask.ui.screens.monthview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.ui.components.MoveToProjectSheet
import com.averycorp.prismtask.ui.components.QuickReschedulePopup
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskSheetHost
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val NeutralGray = Color(0xFF9E9E9E)

private data class MonthTaskEditorState(val taskId: Long? = null, val initialDate: Long? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthViewScreen(
    navController: NavController,
    viewModel: MonthViewModel = hiltViewModel()
) {
    val currentMonth by viewModel.currentMonth.collectAsStateWithLifecycle()
    val dayInfos by viewModel.monthDayInfos.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val selectedDateTasks by viewModel.selectedDateTasks.collectAsStateWithLifecycle()
    val firstDayOfWeek by viewModel.firstDayOfWeek.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    var editorState by remember { mutableStateOf<MonthTaskEditorState?>(null) }
    var reschedulePopupTask by remember { mutableStateOf<TaskEntity?>(null) }
    var moveToProjectSheetTask by remember { mutableStateOf<TaskEntity?>(null) }
    var cascadeConfirmState by remember { mutableStateOf<Pair<TaskEntity, Long?>?>(null) }
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val taskCountByProject by viewModel.taskCountByProject.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.onPreviousMonth() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous month", modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = "${currentMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${currentMonth.year}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { viewModel.onNextMonth() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next month", modifier = Modifier.size(20.dp))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onGoToToday() }) {
                        Icon(Icons.Default.Today, "Go to today")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Day of week headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                val daysOfWeek = (0..6).map { offset ->
                    DayOfWeek.of(((firstDayOfWeek.value - 1 + offset) % 7) + 1)
                }
                daysOfWeek.forEach { dow ->
                    Text(
                        text = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (dow.value >= 6) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Calendar grid
            val firstDayOfMonth = currentMonth.atDay(1)
            val daysInMonth = currentMonth.lengthOfMonth()
            val startDayOffset = ((firstDayOfMonth.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7

            // Build cell list: offset blanks + actual days
            val cells = buildList {
                repeat(startDayOffset) { add(null) }
                for (d in 1..daysInMonth) {
                    add(currentMonth.atDay(d))
                }
                // Pad to fill last row
                while (size % 7 != 0) add(null)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(cells) { date ->
                    if (date == null) {
                        Box(modifier = Modifier.aspectRatio(1f))
                    } else {
                        val info = dayInfos[date]
                        val isToday = date == today
                        val isSelected = date == selectedDate
                        val isWeekend = date.dayOfWeek.value >= 6

                        DayCell(
                            date = date,
                            info = info,
                            isToday = isToday,
                            isSelected = isSelected,
                            isWeekend = isWeekend,
                            onClick = { viewModel.onSelectDate(date) }
                        )
                    }
                }
            }

            // Selected day detail
            AnimatedVisibility(
                visible = selectedDate != null,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                selectedDate?.let { date ->
                    DayDetail(
                        date = date,
                        tasks = selectedDateTasks,
                        onTaskClick = { taskId ->
                            editorState = MonthTaskEditorState(taskId = taskId)
                        },
                        onReschedule = { task -> reschedulePopupTask = task },
                        onMoveToProject = { task -> moveToProjectSheetTask = task },
                        onDuplicate = { taskId -> viewModel.onDuplicateTask(taskId) },
                        onDelete = { taskId -> viewModel.onDeleteTaskWithUndo(taskId) },
                        onAddTask = {
                            val dayStartMillis = date
                                .atStartOfDay(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                            editorState = MonthTaskEditorState(initialDate = dayStartMillis)
                        }
                    )
                }
            }
        }
    }

    editorState?.let { state ->
        AddEditTaskSheetHost(
            taskId = state.taskId,
            projectId = null,
            initialDate = state.initialDate,
            onDismiss = { editorState = null },
            onManageTemplates = {
                editorState = null
                navController.navigate(PrismTaskRoute.TemplateList.route)
            }
        )
    }

    reschedulePopupTask?.let { task ->
        QuickReschedulePopup(
            hasDueDate = task.dueDate != null,
            onDismiss = { reschedulePopupTask = null },
            onReschedule = { newDate -> viewModel.onRescheduleTask(task.id, newDate) },
            onPlanForToday = { viewModel.onPlanTaskForToday(task.id) }
        )
    }

    moveToProjectSheetTask?.let { task ->
        var subtaskCount by remember(task.id) { mutableStateOf(0) }
        LaunchedEffect(task.id) { subtaskCount = viewModel.getSubtaskCount(task.id) }
        MoveToProjectSheet(
            projects = projects,
            taskCountByProject = taskCountByProject,
            currentProjectId = task.projectId,
            onDismiss = { moveToProjectSheetTask = null },
            onMove = { newProjectId ->
                moveToProjectSheetTask = null
                if (subtaskCount > 0) {
                    cascadeConfirmState = task to newProjectId
                } else {
                    viewModel.onMoveToProject(task.id, newProjectId)
                }
            },
            onCreateAndMove = { name ->
                moveToProjectSheetTask = null
                viewModel.onCreateProjectAndMoveTask(task.id, name, cascadeSubtasks = subtaskCount > 0)
            }
        )
    }

    cascadeConfirmState?.let { (task, newProjectId) ->
        AlertDialog(
            onDismissRequest = { cascadeConfirmState = null },
            title = { Text("Move Subtasks Too?") },
            text = { Text("'${task.title}' has subtasks. Should they move to the same project?") },
            confirmButton = {
                TextButton(onClick = {
                    cascadeConfirmState = null
                    viewModel.onMoveToProject(task.id, newProjectId, cascadeSubtasks = true)
                }) { Text("Yes, Move All") }
            },
            dismissButton = {
                TextButton(onClick = {
                    cascadeConfirmState = null
                    viewModel.onMoveToProject(task.id, newProjectId, cascadeSubtasks = false)
                }) { Text("No, Just This") }
            }
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    info: DayInfo?,
    isToday: Boolean,
    isSelected: Boolean,
    isWeekend: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .then(
                when {
                    isSelected -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    info?.hasOverdue == true -> Modifier.background(NeutralGray.copy(alpha = 0.06f))
                    else -> Modifier
                }
            ).clickable { onClick() }
            .padding(2.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Date number
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .then(
                        if (isToday) {
                            Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${date.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        isWeekend -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            // Task density dots
            if (info != null && info.taskCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    val dotColor = if (info.topPriority > 0) {
                        LocalPriorityColors.current.forLevel(info.topPriority)
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    val dots = when {
                        info.taskCount >= 5 -> 3
                        info.taskCount >= 3 -> 2
                        else -> 1
                    }
                    repeat(dots) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }
            }

            // Overdue indicator
            if (info?.hasOverdue == true) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(NeutralGray)
                )
            }
        }
    }
}

@Composable
private fun DayDetail(
    date: LocalDate,
    tasks: List<TaskEntity>,
    onTaskClick: (Long) -> Unit,
    onReschedule: (TaskEntity) -> Unit,
    onMoveToProject: (TaskEntity) -> Unit,
    onDuplicate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAddTask: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${date.dayOfWeek.getDisplayName(
                        TextStyle.FULL,
                        Locale.getDefault()
                    )}, ${date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.dayOfMonth}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${tasks.size} task${if (tasks.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (tasks.isEmpty()) {
                Text(
                    text = "No tasks on this day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height((tasks.size.coerceAtMost(5) * 44).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        var showOverflowMenu by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTaskClick(task.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (task.priority > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(LocalPriorityColors.current.forLevel(task.priority))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                                color = if (task.isCompleted) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (task.isCompleted) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "More Actions",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                                            onReschedule(task)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("\uD83D\uDCC1  Move To Project") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onMoveToProject(task)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("\uD83D\uDCCB  Duplicate") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onDuplicate(task.id)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("\uD83D\uDDD1\uFE0F  Delete") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onDelete(task.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onAddTask) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Task")
            }
        }
    }
}
