package com.averycorp.prismtask.ui.screens.addedittask.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.domain.model.CognitiveLoad
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.model.TaskMode
import com.averycorp.prismtask.ui.screens.addedittask.AddEditTaskViewModel
import com.averycorp.prismtask.ui.screens.addedittask.PROJECT_COLORS
import com.averycorp.prismtask.ui.screens.addedittask.SectionLabel
import com.averycorp.prismtask.ui.screens.addedittask.TAG_COLORS
import com.averycorp.prismtask.ui.screens.addedittask.parseColorOr
import com.averycorp.prismtask.ui.theme.CognitiveLoadColor
import com.averycorp.prismtask.ui.theme.LifeCategoryColor
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import com.averycorp.prismtask.ui.theme.TaskModeColor
import kotlinx.coroutines.launch

@Composable
internal fun OrganizeTabContent(
    viewModel: AddEditTaskViewModel,
    onDeleteTask: ((Long) -> Unit)?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()

    var showProjectPicker by remember { mutableStateOf(false) }
    var showCreateProjectForm by remember { mutableStateOf(false) }
    var tagsExpanded by remember { mutableStateOf(false) }
    var showNewTagForm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Auto-press the classifier-backed chips whenever the user lands on the
    // Organize tab or edits the title/description. Each call short-circuits
    // when the user has already manually picked that selector's chip.
    LaunchedEffect(viewModel.title, viewModel.description) {
        viewModel.autoPickLifeCategory()
        viewModel.autoPickTaskMode()
        viewModel.autoPickCognitiveLoad()
    }

    // ---- Project section ----
    SectionLabel("Project")
    if (projects.isEmpty()) {
        EmptyProjectsCard(onCreate = {
            showProjectPicker = true
            showCreateProjectForm = true
        })
    } else {
        ProjectSelectorCard(
            selectedProject = projects.find { it.id == viewModel.projectId },
            onClick = { showProjectPicker = true }
        )
    }

    // ---- Tags section ----
    SectionLabel("Tags")
    if (allTags.isEmpty() && !showNewTagForm) {
        EmptyTagsCard(onCreate = { showNewTagForm = true })
    } else {
        TagFlowSelector(
            tags = allTags,
            selectedTagIds = viewModel.selectedTagIds,
            expanded = tagsExpanded,
            onToggleExpanded = { tagsExpanded = !tagsExpanded },
            onToggleTag = { tagId ->
                val newSet = if (tagId in viewModel.selectedTagIds) {
                    viewModel.selectedTagIds - tagId
                } else {
                    viewModel.selectedTagIds + tagId
                }
                viewModel.onSelectedTagIdsChange(newSet)
            },
            onAddTag = { showNewTagForm = true },
            showNewTagForm = showNewTagForm,
            onCancelNewTag = { showNewTagForm = false },
            onCreateTag = { name, color ->
                viewModel.createAndAssignTag(name, color)
                showNewTagForm = false
            }
        )
    }

    // ---- Life Category section (Work-Life Balance Engine v1.4.0 V1) ----
    SectionLabel("Life Category")
    OrganizeSectionDescription(
        "Which area of life this task belongs to. Used by the work-life " +
            "balance bar and weekly report."
    )
    LifeCategorySelector(
        selected = viewModel.lifeCategory,
        onSelect = { viewModel.onLifeCategoryChange(it) },
        onAuto = { viewModel.autoPickLifeCategory(force = true) },
        autoLoading = viewModel.lifeCategoryAutoPickInFlight
    )

    // ---- Task Mode section (Work / Play / Relax — see docs/WORK_PLAY_RELAX.md) ----
    SectionLabel("Task Mode")
    OrganizeSectionDescription(
        "Whether this task is heads-down work, something playful, or " +
            "something restful. Helps mode-aware suggestions and Brain Mode."
    )
    TaskModeSelector(
        selected = viewModel.taskMode,
        onSelect = { viewModel.onTaskModeChange(it) },
        onAuto = { viewModel.autoPickTaskMode(force = true) }
    )

    // ---- Cognitive Load section (Easy / Medium / Hard — see docs/COGNITIVE_LOAD.md) ----
    SectionLabel("Cognitive Load")
    OrganizeSectionDescription(
        "How mentally demanding this task is, regardless of how long it " +
            "takes. Drives smart-pomodoro pacing and energy-aware planning."
    )
    CognitiveLoadSelector(
        selected = viewModel.cognitiveLoad,
        onSelect = { viewModel.onCognitiveLoadChange(it) },
        onAuto = { viewModel.autoPickCognitiveLoad(force = true) }
    )

    // ---- Blockers section (per-task dependency management — F.5 follow-on) ----
    SectionLabel("Blockers")
    BlockersSection(viewModel = viewModel)

    // ---- Parent task section ----
    // Future: searchable parent-task picker for nesting subtasks from this tab.
    // For now we show a read-only indicator when a parent is already set.
    if (viewModel.parentTaskId != null) {
        SectionLabel("Parent Task")
        ParentTaskIndicator(
            parentTaskId = viewModel.parentTaskId ?: return,
            onClear = { viewModel.onParentTaskIdChange(null) }
        )
    }

    // ---- Delete task (edit mode only) ----
    if (viewModel.isEditMode) {
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Delete Task",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // ---- Project picker sheet ----
    if (showProjectPicker) {
        ProjectPickerSheet(
            projects = projects,
            selectedProjectId = viewModel.projectId,
            showCreateForm = showCreateProjectForm,
            onShowCreateForm = { showCreateProjectForm = true },
            onHideCreateForm = { showCreateProjectForm = false },
            onSelect = { id ->
                viewModel.onProjectIdChange(id)
                showProjectPicker = false
                showCreateProjectForm = false
            },
            onCreate = { name, color ->
                viewModel.createAndSelectProject(name, color)
                showProjectPicker = false
                showCreateProjectForm = false
            },
            onDismiss = {
                showProjectPicker = false
                showCreateProjectForm = false
            }
        )
    }

    // ---- Delete confirmation ----
    if (showDeleteConfirm) {
        val taskTitle = viewModel.title.trim().ifEmpty { "this task" }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Task") },
            text = { Text("Delete \"$taskTitle\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        val id = viewModel.currentEditingTaskId
                        val callback = onDeleteTask
                        if (id != null && callback != null) {
                            callback(id)
                            onDismiss()
                        } else {
                            scope.launch {
                                viewModel.deleteTask()
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Organize tab: Project selector
// ---------------------------------------------------------------------------

@Composable
internal fun ProjectSelectorCard(
    selectedProject: ProjectEntity?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectedProject != null) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(parseColorOr(selectedProject.color, Color.Gray))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = selectedProject.icon,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = selectedProject.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "No Project",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Change project",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun EmptyProjectsCard(onCreate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ).clickable(onClick = onCreate)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Create Your First Project",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectPickerSheet(
    projects: List<ProjectEntity>,
    selectedProjectId: Long?,
    showCreateForm: Boolean,
    onShowCreateForm: () -> Unit,
    onHideCreateForm: () -> Unit,
    onSelect: (Long?) -> Unit,
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Select Project",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    ProjectPickerRow(
                        leading = {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = "None",
                        trailing = {
                            if (selectedProjectId == null) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        onClick = { onSelect(null) }
                    )
                }
                items(projects, key = { it.id }) { project ->
                    ProjectPickerRow(
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(parseColorOr(project.color, Color.Gray))
                            )
                        },
                        label = "${project.icon} ${project.name}",
                        trailing = {
                            if (selectedProjectId == project.id) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        onClick = { onSelect(project.id) }
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    if (showCreateForm) {
                        InlineCreateProjectForm(
                            onCreate = onCreate,
                            onCancel = onHideCreateForm
                        )
                    } else {
                        ProjectPickerRow(
                            leading = {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = "Create New Project",
                            labelColor = MaterialTheme.colorScheme.primary,
                            onClick = onShowCreateForm
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectPickerRow(
    leading: @Composable () -> Unit,
    label: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            leading()
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineCreateProjectForm(
    onCreate: (name: String, color: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(PROJECT_COLORS.first()) }

    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Project Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PROJECT_COLORS.forEach { color ->
                ColorDot(
                    color = color,
                    selected = color == selectedColor,
                    onClick = { selectedColor = color }
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name, selectedColor) },
                enabled = name.isNotBlank()
            ) {
                Text("Create", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Organize tab: Tag selector
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TagFlowSelector(
    tags: List<TagEntity>,
    selectedTagIds: Set<Long>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleTag: (Long) -> Unit,
    onAddTag: () -> Unit,
    showNewTagForm: Boolean,
    onCancelNewTag: () -> Unit,
    onCreateTag: (String, String) -> Unit
) {
    val showAllThreshold = 12
    val collapsedLimit = 8
    val shouldCollapse = tags.size > showAllThreshold && !expanded
    val visibleTags = if (shouldCollapse) tags.take(collapsedLimit) else tags

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        visibleTags.forEach { tag ->
            TagToggleChip(
                tag = tag,
                selected = tag.id in selectedTagIds,
                onClick = { onToggleTag(tag.id) }
            )
        }
        if (shouldCollapse) {
            ShowMoreChip(
                count = tags.size,
                onClick = onToggleExpanded
            )
        }
        NewTagChip(onClick = onAddTag)
    }

    if (showNewTagForm) {
        Spacer(modifier = Modifier.height(4.dp))
        InlineCreateTagForm(
            onCreate = onCreateTag,
            onCancel = onCancelNewTag
        )
    }
}

@Composable
internal fun TagToggleChip(
    tag: TagEntity,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tagColor = parseColorOr(tag.color, Color.Gray)
    val bg = if (selected) tagColor else Color.Transparent
    val textColor = if (selected) Color.White else tagColor
    val borderColor = tagColor
    Box(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(bg)
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tag.name,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
internal fun NewTagChip(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .border(
                width = 1.5.dp,
                color = color,
                shape = RoundedCornerShape(16.dp)
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "New Tag",
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun ShowMoreChip(count: Int, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Show All ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun EmptyTagsCard(onCreate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ).clickable(onClick = onCreate)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Add Tags to Organize Tasks",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineCreateTagForm(
    onCreate: (name: String, color: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(TAG_COLORS.first()) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Tag Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TAG_COLORS.forEach { color ->
                ColorDot(
                    color = color,
                    selected = color == selectedColor,
                    onClick = { selectedColor = color }
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name, selectedColor) },
                enabled = name.isNotBlank()
            ) {
                Text("Add", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Organize tab: Parent task indicator
// ---------------------------------------------------------------------------

@Composable
internal fun ParentTaskIndicator(
    parentTaskId: Long,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Subtask of task #$parentTaskId",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove parent",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Organize tab: Shared helpers
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Organize tab: Life Category selector (Work-Life Balance Engine)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LifeCategorySelector(
    selected: LifeCategory?,
    onSelect: (LifeCategory?) -> Unit,
    onAuto: () -> Unit,
    autoLoading: Boolean = false
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        AutoPickButton(onClick = onAuto, loading = autoLoading)
        LifeCategoryChip(
            label = LifeCategory.label(LifeCategory.WORK),
            color = LifeCategoryColor.WORK,
            selected = selected == LifeCategory.WORK,
            onClick = { onSelect(LifeCategory.WORK) }
        )
        LifeCategoryChip(
            label = LifeCategory.label(LifeCategory.PERSONAL),
            color = LifeCategoryColor.PERSONAL,
            selected = selected == LifeCategory.PERSONAL,
            onClick = { onSelect(LifeCategory.PERSONAL) }
        )
        LifeCategoryChip(
            label = LifeCategory.label(LifeCategory.SELF_CARE),
            color = LifeCategoryColor.SELF_CARE,
            selected = selected == LifeCategory.SELF_CARE,
            onClick = { onSelect(LifeCategory.SELF_CARE) }
        )
        LifeCategoryChip(
            label = LifeCategory.label(LifeCategory.HEALTH),
            color = LifeCategoryColor.HEALTH,
            selected = selected == LifeCategory.HEALTH,
            onClick = { onSelect(LifeCategory.HEALTH) }
        )
    }
}

// ---------------------------------------------------------------------------
// Organize tab: Task Mode selector (Work / Play / Relax)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskModeSelector(
    selected: TaskMode?,
    onSelect: (TaskMode?) -> Unit,
    onAuto: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        AutoPickButton(onClick = onAuto)
        LifeCategoryChip(
            label = TaskMode.label(TaskMode.WORK),
            color = TaskModeColor.WORK,
            selected = selected == TaskMode.WORK,
            onClick = { onSelect(TaskMode.WORK) }
        )
        LifeCategoryChip(
            label = TaskMode.label(TaskMode.PLAY),
            color = TaskModeColor.PLAY,
            selected = selected == TaskMode.PLAY,
            onClick = { onSelect(TaskMode.PLAY) }
        )
        LifeCategoryChip(
            label = TaskMode.label(TaskMode.RELAX),
            color = TaskModeColor.RELAX,
            selected = selected == TaskMode.RELAX,
            onClick = { onSelect(TaskMode.RELAX) }
        )
    }
}

// ---------------------------------------------------------------------------
// Organize tab: Cognitive Load selector (Easy / Medium / Hard)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CognitiveLoadSelector(
    selected: CognitiveLoad?,
    onSelect: (CognitiveLoad?) -> Unit,
    onAuto: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        AutoPickButton(onClick = onAuto)
        LifeCategoryChip(
            label = CognitiveLoad.label(CognitiveLoad.EASY),
            color = CognitiveLoadColor.EASY,
            selected = selected == CognitiveLoad.EASY,
            onClick = { onSelect(CognitiveLoad.EASY) }
        )
        LifeCategoryChip(
            label = CognitiveLoad.label(CognitiveLoad.MEDIUM),
            color = CognitiveLoadColor.MEDIUM,
            selected = selected == CognitiveLoad.MEDIUM,
            onClick = { onSelect(CognitiveLoad.MEDIUM) }
        )
        LifeCategoryChip(
            label = CognitiveLoad.label(CognitiveLoad.HARD),
            color = CognitiveLoadColor.HARD,
            selected = selected == CognitiveLoad.HARD,
            onClick = { onSelect(CognitiveLoad.HARD) }
        )
    }
}

/**
 * "Auto" button rendered alongside the classifier-backed chips. Tapping it
 * forces a re-pick (clears any prior manual selection and re-runs the
 * keyword classifier on the current title + description).
 *
 * When [loading] is true (a Claude-backed classification is in flight),
 * the icon swaps to a small spinner so the user can tell that an AI call
 * is happening on top of the instant local pick.
 */
@Composable
private fun AutoPickButton(onClick: () -> Unit, loading: Boolean = false) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(color.copy(alpha = 0.12f))
            .border(
                width = 1.5.dp,
                color = color,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = color,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AutoFixHigh,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = "Auto",
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LifeCategoryChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) color else Color.Transparent
    val textColor = if (selected) Color.White else color
    Box(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(bg)
            .border(
                width = 1.5.dp,
                color = color,
                shape = RoundedCornerShape(16.dp)
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
internal fun ColorDot(
    color: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val parsed = parseColorOr(color, Color.Gray)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(parsed)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Organize tab: Blockers selector
// ---------------------------------------------------------------------------

@Composable
internal fun BlockersSection(viewModel: AddEditTaskViewModel) {
    if (!viewModel.isEditMode) {
        EmptyBlockersHint(
            text = "Save the task first to add blockers",
            primary = false
        )
        return
    }
    val blockers by viewModel.blockers.collectAsStateWithLifecycle()
    val allTasks by viewModel.allTasksForPicker.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    val titlesById = remember(allTasks) { allTasks.associateBy({ it.id }, { it.title }) }
    val currentId = viewModel.currentEditingTaskId
    val excludedIds = remember(blockers, currentId) {
        buildSet {
            currentId?.let { add(it) }
            blockers.forEach { add(it.blockerTaskId) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blockers.isEmpty()) {
            EmptyBlockersHint(
                text = "No blockers",
                primary = false
            )
        } else {
            blockers.forEach { edge ->
                BlockerRow(
                    title = titlesById[edge.blockerTaskId] ?: "Task #${edge.blockerTaskId}",
                    onRemove = { viewModel.removeBlocker(edge) }
                )
            }
        }
        AddBlockerButton(onClick = { showPicker = true })
    }

    if (showPicker) {
        BlockerPickerDialog(
            candidates = allTasks.filter { it.id !in excludedIds },
            onDismiss = { showPicker = false },
            onPick = { id ->
                viewModel.addBlocker(id)
                showPicker = false
            }
        )
    }
}

@Composable
internal fun BlockerRow(title: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove blocker",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun AddBlockerButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp)
            ).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Add Blocker",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun EmptyBlockersHint(text: String, primary: Boolean) {
    val tint = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tint
        )
    }
}

@Composable
internal fun BlockerPickerDialog(
    candidates: List<TaskEntity>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Blocker") },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    text = "No other tasks are available to set as a blocker.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(candidates, key = { it.id }) { task ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(task.id) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun OrganizeSectionDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
    )
}
