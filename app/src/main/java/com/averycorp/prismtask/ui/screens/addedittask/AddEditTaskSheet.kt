package com.averycorp.prismtask.ui.screens.addedittask

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.screens.addedittask.tabs.DetailsTabContent
import com.averycorp.prismtask.ui.screens.addedittask.tabs.OrganizeTabContent
import com.averycorp.prismtask.ui.screens.addedittask.tabs.PriorityCircleRow
import com.averycorp.prismtask.ui.screens.addedittask.tabs.ScheduleTabContent
import com.averycorp.prismtask.ui.screens.addedittask.tabs.formatShortDate
import com.averycorp.prismtask.ui.screens.coaching.CoachingViewModel
import com.averycorp.prismtask.ui.screens.templates.TemplatePickerSheet
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Entry point for presenting the task editor as a modal bottom sheet from any
 * screen. Creates its own [AddEditTaskViewModel] scoped to the caller's
 * ViewModelStoreOwner (typically the containing NavBackStackEntry) and seeds
 * it with the supplied taskId / create-mode defaults.
 *
 * @param taskId existing task to edit, or null for create mode.
 * @param projectId pre-selected project for create mode (ignored in edit mode).
 * @param initialDate pre-set due date for create mode (ignored in edit mode).
 * @param initialTab tab to open first (0=Details, 1=Schedule, 2=Organize).
 * @param onDismiss invoked after the sheet has finished closing.
 * @param onDeleteTask optional handler invoked when the user confirms deletion
 *   from the Organize tab. When supplied, the parent is responsible for
 *   performing the delete (typically via a delete-with-undo VM call) and the
 *   sheet will dismiss itself. When null, the sheet falls back to calling
 *   [AddEditTaskViewModel.deleteTask] directly and deletion has no undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskSheetHost(
    taskId: Long?,
    projectId: Long?,
    initialDate: Long?,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onDeleteTask: ((Long) -> Unit)? = null,
    onManageTemplates: (() -> Unit)? = null
) {
    val viewModel: AddEditTaskViewModel = hiltViewModel(key = "addedit_task_sheet")
    val coachingViewModel: CoachingViewModel = hiltViewModel()

    LaunchedEffect(taskId, projectId, initialDate) {
        viewModel.initialize(taskId = taskId, projectId = projectId, initialDate = initialDate)
    }

    AddEditTaskSheet(
        viewModel = viewModel,
        coachingViewModel = coachingViewModel,
        initialTab = initialTab,
        onDismiss = onDismiss,
        onDeleteTask = onDeleteTask,
        onManageTemplates = onManageTemplates
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun AddEditTaskSheet(
    viewModel: AddEditTaskViewModel,
    coachingViewModel: CoachingViewModel = hiltViewModel(),
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onDeleteTask: ((Long) -> Unit)? = null,
    onManageTemplates: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var showSaveAsTemplateDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.errorMessages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(coachingViewModel) {
        coachingViewModel.statusMessages.collect { snackbarHostState.showSnackbar(it) }
    }
    val subtaskCount by viewModel.subtaskCount.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, 2),
        pageCount = { 3 }
    )
    val titleFocusRequester = remember { FocusRequester() }

    fun attemptDismiss() {
        if (viewModel.hasUnsavedChanges) {
            showDiscardConfirm = true
        } else {
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { attemptDismiss() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(modifier = Modifier.fillMaxHeight(0.9f).imePadding()) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Sticky header: close / screen title / save
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { attemptDismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                    Text(
                        text = if (viewModel.isEditMode) "Edit Task" else "New Task",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    // "From Template" shortcut — create mode only. Opens a
                    // picker sheet that pre-fills the form with the chosen
                    // template's fields. Hidden in edit mode so users don't
                    // accidentally blow away their task's data.
                    if (!viewModel.isEditMode) {
                        TextButton(
                            onClick = { showTemplatePicker = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "\uD83D\uDCCB Template",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (viewModel.saveTask()) onDismiss()
                            }
                        }
                    ) {
                        Text(
                            text = "Save",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    // Header overflow menu (edit mode only). Hosts Duplicate
                    // and Save-As-Template; add future task-wide actions here.
                    if (viewModel.isEditMode) {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More Actions"
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showDuplicateDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save As Template") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Bookmark,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showSaveAsTemplateDialog = true
                                    }
                                )
                            }
                        }
                    }
                }

                // Title field (large, always visible above tabs)
                OutlinedTextField(
                    value = viewModel.title,
                    onValueChange = viewModel::onTitleChange,
                    placeholder = {
                        Text(
                            text = "Task Title",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    textStyle = MaterialTheme.typography.titleLarge,
                    isError = viewModel.titleError,
                    supportingText = if (viewModel.titleError) {
                        { Text("Title is required") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(titleFocusRequester)
                )

                // Priority circles row (always visible above tabs)
                PriorityCircleRow(
                    selected = viewModel.priority,
                    onSelect = viewModel::onPriorityChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tab bar
                val tabs = listOf("Details", "Schedule", "Organize")
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    indicator = { tabPositions ->
                        if (tabPositions.isNotEmpty()) {
                            val currentPage = pagerState.currentPage.coerceIn(0, tabPositions.lastIndex)
                            val fraction = pagerState.currentPageOffsetFraction
                            val currentTab = tabPositions[currentPage]
                            val targetTab = tabPositions.getOrElse(
                                if (fraction > 0f) currentPage + 1 else currentPage - 1
                            ) { currentTab }
                            val indicatorOffset = lerp(currentTab.left, targetTab.left, abs(fraction))
                            val indicatorWidth = lerp(currentTab.width, targetTab.width, abs(fraction))
                            SecondaryIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentSize(Alignment.BottomStart)
                                    .offset(x = indicatorOffset)
                                    .width(indicatorWidth)
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (pagerState.currentPage == index) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                            }
                        )
                    }
                }
                HorizontalDivider()

                // Swipeable tab content
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (page) {
                            0 -> DetailsTabContent(viewModel, coachingViewModel)
                            1 -> ScheduleTabContent(viewModel)
                            2 -> OrganizeTabContent(
                                viewModel = viewModel,
                                onDeleteTask = onDeleteTask,
                                onDismiss = onDismiss
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
            // Ephemeral snackbar overlay used for in-sheet confirmations (e.g.
            // "Task Duplicated"). Scoped to the sheet so it dismisses cleanly
            // when the sheet closes.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // Auto-focus the title field when creating a new task. In androidTest
    // the LaunchedEffect can fire before the focus target's .focusRequester()
    // modifier has been attached to the composition tree (bottom-sheet
    // animation compresses to zero in the test dispatcher), throwing
    // "FocusRequester is not initialized." Catch it — losing auto-focus
    // during a test isn't a real failure; the user path in production
    // always has the attachment ready by the time the effect runs.
    LaunchedEffect(Unit) {
        if (!viewModel.isEditMode) {
            try {
                titleFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Focus target not composed yet — auto-focus skipped.
            }
        }
    }

    // Back button / gesture: route through attemptDismiss so unsaved changes
    // prompt for confirmation before closing.
    BackHandler { attemptDismiss() }

    // Boundary block dialog (v1.4.0 V3). Shown when saveTask() bounced off
    // an active BLOCK_CATEGORY rule. User can force-create anyway or reschedule
    // the task to tomorrow so it falls outside the current window.
    val boundaryBlock = viewModel.pendingBoundaryBlock
    if (boundaryBlock != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBoundaryBlock() },
            title = { Text("Outside '${boundaryBlock.rule.name}'") },
            text = { Text(boundaryBlock.reason) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissBoundaryBlock()
                    scope.launch {
                        if (viewModel.saveTask(ignoreBoundaries = true)) onDismiss()
                    }
                }) {
                    Text("Create Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissBoundaryBlock()
                    val tomorrow = System.currentTimeMillis() + 24L * 60 * 60 * 1000
                    viewModel.onDueDateChange(tomorrow)
                }) {
                    Text("Reschedule To Tomorrow")
                }
            }
        )
    }

    // Duplicate confirmation dialog. Shown from the header overflow menu in
    // edit mode. When confirmed, the VM creates a copy of the current task
    // and re-seeds the form with the new one, and the sheet surfaces a
    // "Task Duplicated" snackbar.
    if (showDuplicateDialog) {
        val taskDueDate = viewModel.dueDate
        var copyDueDate by remember { mutableStateOf(true) }
        var includeSubtasks by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            title = { Text("Duplicate Task") },
            text = {
                Column {
                    Text(
                        text = "A copy will be created with \"Copy of \" prefixed to the title.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (taskDueDate != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { copyDueDate = !copyDueDate }
                                .padding(vertical = 4.dp)
                        ) {
                            CircularCheckbox(
                                checked = copyDueDate,
                                onCheckedChange = { copyDueDate = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Due Date (${formatShortDate(taskDueDate)})")
                        }
                    }
                    if (subtaskCount > 0) {
                        Spacer(modifier = Modifier.height(if (taskDueDate != null) 4.dp else 12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { includeSubtasks = !includeSubtasks }
                                .padding(vertical = 4.dp)
                        ) {
                            CircularCheckbox(
                                checked = includeSubtasks,
                                onCheckedChange = { includeSubtasks = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Include Subtasks ($subtaskCount)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                        scope.launch {
                            val newId = viewModel.duplicateCurrentTask(
                                includeSubtasks,
                                copyDueDate = taskDueDate != null && copyDueDate
                            )
                            if (newId != null) {
                                snackbarHostState.showSnackbar("Task Duplicated")
                            }
                        }
                    }
                ) { Text("Duplicate") }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Template picker sheet — invoked from the header "📋 Template" button
    // in create mode. On Use, applies the selected template to the form
    // and surfaces a snackbar so the user knows fields were populated.
    if (showTemplatePicker) {
        TemplatePickerSheet(
            onDismiss = { showTemplatePicker = false },
            onUseTemplate = { template ->
                showTemplatePicker = false
                scope.launch {
                    val ok = viewModel.applyTemplate(template.id)
                    if (ok) {
                        snackbarHostState.showSnackbar("Applied '${template.name}'")
                    }
                }
            },
            onManageTemplates = {
                showTemplatePicker = false
                onManageTemplates?.invoke()
            }
        )
    }

    // Save-As-Template dialog — invoked from the edit-mode overflow menu.
    if (showSaveAsTemplateDialog) {
        SaveAsTemplateDialog(
            initialName = viewModel.title.trim().ifEmpty { "Untitled Template" },
            onDismiss = { showSaveAsTemplateDialog = false },
            onSave = { name, icon, category ->
                showSaveAsTemplateDialog = false
                scope.launch {
                    val newId = viewModel.saveAsTemplate(name, icon, category)
                    if (newId != null) {
                        snackbarHostState.showSnackbar("Template saved!")
                    }
                }
            }
        )
    }

    // Discard confirmation dialog
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard Changes?") },
            text = { Text("You have unsaved changes. Are you sure you want to discard them?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    }
                ) {
                    Text(
                        text = "Discard",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }
}

internal fun parseColorOr(hex: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    fallback
}

internal val PROJECT_COLORS = listOf(
    "#4A90D9",
    "#7B61FF",
    "#E8872A",
    "#D93025",
    "#2E7D32",
    "#00897B",
    "#F4B400",
    "#8E24AA"
)

internal val TAG_COLORS = listOf(
    "#6B7280",
    "#4A90D9",
    "#7B61FF",
    "#2E7D32",
    "#E8872A",
    "#D93025",
    "#00897B",
    "#F4B400"
)

// ---------------------------------------------------------------------------
// Save-As-Template dialog
// ---------------------------------------------------------------------------

/** Compact emoji palette offered to the Save-As-Template dialog. */
internal val TEMPLATE_ICON_CHOICES = listOf(
    // 📋
    "\uD83D\uDCCB",
    // 📝
    "\uD83D\uDCDD",
    // ⭐
    "\u2B50",
    // 🔥
    "\uD83D\uDD25",
    // 🎯
    "\uD83C\uDFAF",
    // 📅
    "\uD83D\uDCC5",
    // 💼
    "\uD83D\uDCBC",
    // 🏠
    "\uD83C\uDFE0"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SaveAsTemplateDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String?, category: String?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedIcon by remember { mutableStateOf(TEMPLATE_ICON_CHOICES.first()) }
    var category by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save As Template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Template Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text(
                        text = "Icon",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TEMPLATE_ICON_CHOICES.forEach { emoji ->
                            val selected = emoji == selectedIcon
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        }
                                    ).border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    ).clickable { selectedIcon = emoji },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, selectedIcon, category) },
                enabled = name.isNotBlank()
            ) {
                Text("Save Template", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
