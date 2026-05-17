package com.averycorp.prismtask.ui.screens.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.MoveToProjectSheet
import com.averycorp.prismtask.ui.components.QuickReschedulePopup
import com.averycorp.prismtask.ui.components.UpgradePrompt
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskSheetHost
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

private val HOUR_HEIGHT = 60.dp
private const val START_HOUR = 6
private const val END_HOUR = 23

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    navController: NavController,
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val currentDate by viewModel.currentDate.collectAsStateWithLifecycle()
    val scheduledBlocks by viewModel.scheduledBlocks.collectAsStateWithLifecycle()
    val unscheduledTasks by viewModel.unscheduledTasks.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val today = LocalDate.now()
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

    var scheduleDialogTask by remember { mutableStateOf<TaskEntity?>(null) }
    var editorSheetTaskId by remember { mutableStateOf<Long?>(null) }
    var showEditorSheet by remember { mutableStateOf(false) }
    var reschedulePopupTask by remember { mutableStateOf<TaskEntity?>(null) }
    var moveToProjectSheetTask by remember { mutableStateOf<TaskEntity?>(null) }
    val aiSchedule by viewModel.aiSchedule.collectAsStateWithLifecycle()
    val isGeneratingSchedule by viewModel.isGeneratingSchedule.collectAsStateWithLifecycle()
    val scheduleUiState by viewModel.scheduleUiState.collectAsStateWithLifecycle()
    val showUpgradePrompt by viewModel.showUpgradePrompt.collectAsStateWithLifecycle()
    var cascadeConfirmState by remember { mutableStateOf<Pair<TaskEntity, Long?>?>(null) }
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val taskCountByProject by viewModel.taskCountByProject.collectAsStateWithLifecycle()

    // Scroll to current hour on first load
    LaunchedEffect(Unit) {
        val nowHour = LocalTime.now().hour
        val scrollTarget = ((nowHour - START_HOUR).coerceAtLeast(0) * HOUR_HEIGHT.value).toInt()
        scrollState.scrollTo((scrollTarget * 2.5f).toInt()) // density approximation
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.onPreviousDay() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous day", Modifier.size(20.dp))
                        }
                        Text(
                            text = currentDate.format(dateFormatter),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { viewModel.onNextDay() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next day", Modifier.size(20.dp))
                        }
                    }
                },
                actions = {
                    if (isGeneratingSchedule) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.showAutoBlockMyDaySheet() }) {
                            Icon(Icons.Default.AutoAwesome, "Auto-Block My Day")
                        }
                    }
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
            // Summary
            val scheduledMinutes = scheduledBlocks.sumOf { ((it.endTime - it.startTime) / 60000).toInt() }
            val scheduledHours = scheduledMinutes / 60f
            Text(
                text = "${scheduledBlocks.size} scheduled \u00B7 ${String.format("%.1f", scheduledHours)} hrs",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // AI schedule state row. Success renders the stats + Apply/Reset
            // buttons; Empty and Error render a dismissible banner in the
            // same slot so the user always sees an explicit outcome from
            // the Auto-Schedule action. Idle/Loading render nothing here
            // (Loading spinner is in the top bar).
            when (val s = scheduleUiState) {
                is AiScheduleUiState.Success -> {
                    val stats = s.schedule.stats
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${stats.tasksScheduled} tasks \u00B7 " +
                                "${stats.totalWorkMinutes / 60}h work \u00B7 " +
                                "${stats.totalBreakMinutes}m breaks \u00B7 " +
                                "${stats.totalFreeMinutes}m free",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { viewModel.applyAiSchedule() },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Apply", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { viewModel.resetAiSchedule() },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Reset", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                is AiScheduleUiState.Empty -> ScheduleBannerRow(
                    body = s.reason,
                    isError = false,
                    onDismiss = { viewModel.clearScheduleError() }
                )
                is AiScheduleUiState.Error -> ScheduleBannerRow(
                    body = s.message,
                    isError = true,
                    onDismiss = { viewModel.clearScheduleError() }
                )
                else -> Unit
            }

            // Timeline area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                val totalHours = END_HOUR - START_HOUR
                val gridColor = MaterialTheme.colorScheme.outlineVariant

                // Hour grid
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (hour in START_HOUR..END_HOUR) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(HOUR_HEIGHT)
                                .drawBehind {
                                    drawLine(gridColor, Offset(60.dp.toPx(), 0f), Offset(size.width, 0f), strokeWidth = 0.5f)
                                },
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = if (hour <= 12) "$hour AM" else "${hour - 12} PM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .width(56.dp)
                                    .padding(end = 4.dp, top = 2.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Current time indicator
                if (currentDate == today) {
                    val now = LocalTime.now()
                    val minutesSinceStart = (now.hour - START_HOUR) * 60 + now.minute
                    if (minutesSinceStart >= 0) {
                        val yOffset = (minutesSinceStart.toFloat() / 60f * HOUR_HEIGHT.value).dp
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = yOffset)
                                .padding(start = 52.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(Color.Red.copy(alpha = 0.7f))
                            )
                            Text(
                                "NOW",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Red,
                                fontSize = 8.sp,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
                            )
                        }
                    }
                }

                // Scheduled blocks
                val zone = ZoneId.systemDefault()
                val dayStart = currentDate
                    .atTime(START_HOUR, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()

                scheduledBlocks.forEach { block ->
                    val minutesFromStart = ((block.startTime - dayStart) / 60000f).coerceAtLeast(0f)
                    val durationMinutes = ((block.endTime - block.startTime) / 60000f).coerceAtLeast(15f)
                    val yOffset = (minutesFromStart / 60f * HOUR_HEIGHT.value).dp
                    val blockHeight = (durationMinutes / 60f * HOUR_HEIGHT.value).dp
                    var showBlockMenu by remember(block.taskId) { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .padding(start = 60.dp, end = 8.dp)
                            .offset(y = yOffset)
                            .fillMaxWidth()
                            .height(blockHeight)
                            .combinedClickable(
                                onClick = {
                                    block.taskId?.let {
                                        editorSheetTaskId = it
                                        showEditorSheet = true
                                    }
                                },
                                onLongClick = {
                                    if (block.taskId != null) {
                                        showBlockMenu = true
                                    }
                                }
                            ),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = LocalPriorityColors.current.forLevel(block.priority).copy(alpha = 0.2f)
                        )
                    ) {
                        Box {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(blockHeight - 8.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(LocalPriorityColors.current.forLevel(block.priority))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = block.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(
                                expanded = showBlockMenu,
                                onDismissRequest = { showBlockMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCC5  Reschedule") },
                                    onClick = {
                                        showBlockMenu = false
                                        block.taskId?.let { id ->
                                            viewModel.loadTaskForPopup(id) { task ->
                                                reschedulePopupTask = task
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCC1  Move To Project") },
                                    onClick = {
                                        showBlockMenu = false
                                        block.taskId?.let { id ->
                                            viewModel.loadTaskForPopup(id) { task ->
                                                moveToProjectSheetTask = task
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCCB  Duplicate") },
                                    onClick = {
                                        showBlockMenu = false
                                        block.taskId?.let { viewModel.onDuplicateTask(it) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDDD1\uFE0F  Delete") },
                                    onClick = {
                                        showBlockMenu = false
                                        block.taskId?.let { viewModel.onDeleteTaskWithUndo(it) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Unscheduled section
            if (unscheduledTasks.isNotEmpty()) {
                Text(
                    text = "Unscheduled (${unscheduledTasks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    unscheduledTasks.take(5).forEach { task ->
                        var showTaskMenu by remember(task.id) { mutableStateOf(false) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { scheduleDialogTask = task },
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (task.priority > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(LocalPriorityColors.current.forLevel(task.priority))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = "Schedule",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box {
                                    IconButton(
                                        onClick = { showTaskMenu = true },
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
                                        expanded = showTaskMenu,
                                        onDismissRequest = { showTaskMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("\uD83D\uDCC5  Reschedule") },
                                            onClick = {
                                                showTaskMenu = false
                                                reschedulePopupTask = task
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("\uD83D\uDCC1  Move To Project") },
                                            onClick = {
                                                showTaskMenu = false
                                                moveToProjectSheetTask = task
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("\uD83D\uDCCB  Duplicate") },
                                            onClick = {
                                                showTaskMenu = false
                                                viewModel.onDuplicateTask(task.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("\uD83D\uDDD1\uFE0F  Delete") },
                                            onClick = {
                                                showTaskMenu = false
                                                viewModel.onDeleteTaskWithUndo(task.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // Schedule dialog
    scheduleDialogTask?.let { task ->
        val clockState = rememberAnalogClockState(
            initialHour = LocalTime.now().hour,
            initialMinute = (LocalTime.now().minute / 15) * 15,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { scheduleDialogTask = null },
            confirmButton = {
                TextButton(onClick = {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = currentDate
                        .atTime(clockState.hour, clockState.minute)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    viewModel.onScheduleTask(task.id, cal.timeInMillis)
                    scheduleDialogTask = null
                }) { Text("Schedule") }
            },
            dismissButton = {
                TextButton(onClick = { scheduleDialogTask = null }) { Text("Cancel") }
            },
            title = { Text("Schedule: ${task.title}") },
            text = { AnalogClockPicker(state = clockState) }
        )
    }

    if (showEditorSheet) {
        val initialDateForCreate = remember(currentDate) {
            currentDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        AddEditTaskSheetHost(
            taskId = editorSheetTaskId,
            projectId = null,
            initialDate = if (editorSheetTaskId == null) initialDateForCreate else null,
            onDismiss = { showEditorSheet = false },
            onManageTemplates = {
                showEditorSheet = false
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

    val showTimeBlockSheet by viewModel.showTimeBlockSheet.collectAsStateWithLifecycle()
    if (showTimeBlockSheet) {
        TimeBlockConfigSheet(
            config = viewModel.timeBlockConfig.collectAsStateWithLifecycle().value,
            onConfigChanged = { viewModel.updateTimeBlockConfig(it) },
            onGenerate = { viewModel.generateTimeBlocks() },
            onDismiss = { viewModel.dismissTimeBlockSheet() }
        )
    }

    // v1.4.40: Auto-Block My Day horizon picker + preview flow.
    val showHorizonSheet by viewModel.showHorizonSheet.collectAsStateWithLifecycle()
    if (showHorizonSheet) {
        AutoBlockHorizonSheet(
            selectedHorizon = viewModel.selectedHorizon.collectAsStateWithLifecycle().value,
            onSelect = { viewModel.selectHorizon(it) },
            onGenerate = { viewModel.runAutoBlockMyDay() },
            onDismiss = { viewModel.dismissHorizonSheet() }
        )
    }
    val showPreviewSheet by viewModel.showPreviewSheet.collectAsStateWithLifecycle()
    val scheduleForPreview = (scheduleUiState as? AiScheduleUiState.Success)?.schedule
    if (showPreviewSheet && scheduleForPreview != null) {
        AutoBlockPreviewSheet(
            schedule = scheduleForPreview,
            onApprove = { viewModel.commitProposedSchedule() },
            onCancel = { viewModel.cancelProposedSchedule() }
        )
    }

    if (showUpgradePrompt) {
        val userTier by viewModel.userTier.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpgradePrompt() },
            confirmButton = {},
            text = {
                UpgradePrompt(
                    currentTier = userTier,
                    feature = "AI Time Blocking",
                    description = "Let AI auto-schedule your tasks into focus blocks across your day or week.",
                    onUpgrade = { _ ->
                        viewModel.dismissUpgradePrompt()
                        navController.navigate("settings/subscription")
                    },
                    onRestorePurchase = { viewModel.restorePurchases() },
                    onDismiss = { viewModel.dismissUpgradePrompt() }
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeBlockConfigSheet(
    config: TimeBlockConfig,
    onConfigChanged: (TimeBlockConfig) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Auto-Schedule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Day start/end
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Day Start", style = MaterialTheme.typography.labelMedium)
                    Text(config.dayStart, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Day End", style = MaterialTheme.typography.labelMedium)
                    Text(config.dayEnd, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Block size
            Text("Block Size", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60).forEach { size ->
                    androidx.compose.material3.FilterChip(
                        selected = config.blockSizeMinutes == size,
                        onClick = { onConfigChanged(config.copy(blockSizeMinutes = size)) },
                        label = { Text("${size}m") }
                    )
                }
            }

            // Breaks toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Include Breaks", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Switch(
                    checked = config.includeBreaks,
                    onCheckedChange = { onConfigChanged(config.copy(includeBreaks = it)) }
                )
            }

            if (config.includeBreaks) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Break Every", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(60, 90, 120).forEach { freq ->
                                androidx.compose.material3.FilterChip(
                                    selected = config.breakFrequencyMinutes == freq,
                                    onClick = { onConfigChanged(config.copy(breakFrequencyMinutes = freq)) },
                                    label = { Text("${freq}m", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                    Column {
                        Text("Break Length", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(5, 10, 15).forEach { dur ->
                                androidx.compose.material3.FilterChip(
                                    selected = config.breakDurationMinutes == dur,
                                    onClick = { onConfigChanged(config.copy(breakDurationMinutes = dur)) },
                                    label = { Text("${dur}m", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            androidx.compose.material3.Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generate Schedule")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScheduleBannerRow(
    body: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.height(28.dp)
        ) {
            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------------------
// v1.4.40 — Auto-Block My Day flow (horizon picker + mandatory preview).
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoBlockHorizonSheet(
    selectedHorizon: com.averycorp.prismtask.domain.usecase.TimeBlockHorizon,
    onSelect: (com.averycorp.prismtask.domain.usecase.TimeBlockHorizon) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Auto-Block My Day",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Let AI rank your tasks and fit them into a schedule. Pick how far to plan:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizonOptionRow(
                    label = "Today",
                    sublabel = "Schedule just today's work",
                    selected = selectedHorizon == com.averycorp.prismtask.domain.usecase.TimeBlockHorizon.TODAY,
                    onClick = { onSelect(com.averycorp.prismtask.domain.usecase.TimeBlockHorizon.TODAY) }
                )
                HorizonOptionRow(
                    label = "Today + Tomorrow",
                    sublabel = "Spread across two days",
                    selected = selectedHorizon == com.averycorp.prismtask.domain.usecase.TimeBlockHorizon.TODAY_PLUS_ONE,
                    onClick = { onSelect(com.averycorp.prismtask.domain.usecase.TimeBlockHorizon.TODAY_PLUS_ONE) }
                )
                HorizonOptionRow(
                    label = "Next 7 Days",
                    sublabel = "Plan the whole week",
                    selected = selectedHorizon == com.averycorp.prismtask.domain.usecase.TimeBlockHorizon.WEEK,
                    onClick = { onSelect(com.averycorp.prismtask.domain.usecase.TimeBlockHorizon.WEEK) }
                )
            }

            Spacer(Modifier.height(8.dp))

            androidx.compose.material3.Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generate Schedule")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HorizonOptionRow(
    label: String,
    sublabel: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoBlockPreviewSheet(
    schedule: AiSchedule,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Proposed Schedule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            val stats = schedule.stats
            Text(
                text = "${stats.tasksScheduled} tasks · " +
                    "${stats.totalWorkMinutes / 60}h ${stats.totalWorkMinutes % 60}m work · " +
                    "${stats.totalBreakMinutes}m breaks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (schedule.blocks.isEmpty()) {
                Text(
                    text = "No blocks proposed.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val grouped = schedule.blocks.groupBy { it.date }.toSortedMap()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    grouped.forEach { (date, dayBlocks) ->
                        Text(
                            text = formatPreviewDay(date),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        dayBlocks.sortedBy { it.start }.forEach { block ->
                            PreviewBlockCard(block)
                        }
                    }
                }
            }

            if (schedule.unscheduledTasks.isNotEmpty()) {
                Text(
                    text = "Deferred: ${schedule.unscheduledTasks.joinToString { it.second }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                androidx.compose.material3.Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f)
                ) { Text("Approve") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PreviewBlockCard(block: AiScheduleBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${block.start}-${block.end}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (block.reason.isNotBlank()) {
                    Text(
                        text = "Why: ${block.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (block.type != "task") {
                androidx.compose.material3.AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = block.type.replaceFirstChar { it.titlecase() },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
    }
}

private fun formatPreviewDay(isoDate: String): String {
    return try {
        val parsed = LocalDate.parse(isoDate)
        parsed.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    } catch (_: Exception) {
        isoDate
    }
}
