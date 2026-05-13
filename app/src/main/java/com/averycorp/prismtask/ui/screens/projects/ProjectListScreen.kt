package com.averycorp.prismtask.ui.screens.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.dao.ProjectWithCount
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.components.RichEmptyState
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.theme.LocalPrismColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    navController: NavController,
    viewModel: ProjectListViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    var projectToDelete by remember { mutableStateOf<ProjectWithCount?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteContent by remember { mutableStateOf("") }
    // Defaults to ON because the Projects screen is the natural home for
    // creating projects. The TasksScreen mirror defaults to OFF.
    var pasteAsProject by remember { mutableStateOf(true) }
    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
    var fileAsProject by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Stage the URI and open the confirm dialog so the user picks
            // the "Import as new project?" toggle before we read the file.
            fileAsProject = true
            pendingFileUri = uri
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarHostState.currentSnackbarData?.dismiss()
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasteDialog = false
                pasteContent = ""
            },
            title = { Text("Paste To-Do List") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it },
                        placeholder = { Text("Paste JSX / markdown list here\u2026") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 50
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pasteAsProject = !pasteAsProject }
                    ) {
                        Checkbox(
                            checked = pasteAsProject,
                            onCheckedChange = { pasteAsProject = it }
                        )
                        Text(
                            "Import As New Project",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pasteContent.isNotBlank()) {
                            viewModel.stagePastedContent(pasteContent)
                            navController.navigate(
                                PrismTaskRoute.ProjectImportPreview.createRoute(
                                    uri = null,
                                    asProject = pasteAsProject
                                )
                            )
                        }
                        showPasteDialog = false
                        pasteContent = ""
                    },
                    enabled = pasteContent.isNotBlank()
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasteDialog = false
                    pasteContent = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pendingFileUri != null) {
        AlertDialog(
            onDismissRequest = { pendingFileUri = null },
            title = { Text("Import File") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fileAsProject = !fileAsProject }
                ) {
                    Checkbox(
                        checked = fileAsProject,
                        onCheckedChange = { fileAsProject = it }
                    )
                    Text(
                        "Import As New Project",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingFileUri?.let {
                        navController.navigate(
                            PrismTaskRoute.ProjectImportPreview.createRoute(
                                uri = it.toString(),
                                asProject = fileAsProject
                            )
                        )
                    }
                    pendingFileUri = null
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFileUri = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Projects", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { showPasteDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste To-Do List", modifier = Modifier.size(20.dp))
                }
                SmallFloatingActionButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Import File", modifier = Modifier.size(20.dp))
                }
                FloatingActionButton(
                    onClick = { navController.navigate(PrismTaskRoute.AddEditProject.createRoute()) },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add Project",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { padding ->
        if (projects.isEmpty()) {
            RichEmptyState(
                icon = "\uD83D\uDCC1",
                title = "No Projects Yet",
                description = "Projects help you organize related tasks together.",
                actionLabel = "Create Project",
                onAction = { navController.navigate(PrismTaskRoute.AddEditProject.createRoute()) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(projects, key = { it.id }) { project ->
                    ProjectItem(
                        project = project,
                        onClick = {
                            navController.navigate(PrismTaskRoute.AddEditProject.createRoute(project.id))
                        },
                        onEdit = {
                            navController.navigate(PrismTaskRoute.AddEditProject.createRoute(project.id))
                        },
                        onDelete = { projectToDelete = project },
                        onAnalytics = {
                            navController.navigate(PrismTaskRoute.TaskAnalytics.createRoute(project.id))
                        },
                        onArchive = { viewModel.onArchiveProject(project.id) },
                        onReopen = { viewModel.onReopenProject(project.id) }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    projectToDelete?.let { project ->
        var deleteTasksToo by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Delete Project") },
            text = {
                Column {
                    Text("Delete \"${project.name}\"?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteTasksToo = !deleteTasksToo }
                    ) {
                        CircularCheckbox(
                            checked = deleteTasksToo,
                            onCheckedChange = { deleteTasksToo = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Also delete all ${project.taskCount} task${if (project.taskCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (!deleteTasksToo) {
                        Text(
                            text = "Tasks will be kept but unassigned.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 48.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDeleteProject(
                        ProjectEntity(
                            id = project.id,
                            name = project.name,
                            color = project.color,
                            icon = project.icon,
                            createdAt = project.createdAt,
                            updatedAt = project.updatedAt
                        ),
                        deleteTasks = deleteTasksToo
                    )
                    projectToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProjectItem(
    project: ProjectWithCount,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAnalytics: () -> Unit = {},
    onArchive: () -> Unit = {},
    onReopen: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isArchived = project.status == "ARCHIVED"

    val projectColor = try {
        Color(android.graphics.Color.parseColor(project.color))
    } catch (_: Exception) {
        LocalPrismColors.current.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(projectColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = project.icon,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${project.taskCount} task${if (project.taskCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Analytics") },
                        onClick = {
                            menuExpanded = false
                            onAnalytics()
                        }
                    )
                    if (isArchived) {
                        DropdownMenuItem(
                            text = { Text("Unarchive") },
                            onClick = {
                                menuExpanded = false
                                onReopen()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            onClick = {
                                menuExpanded = false
                                onArchive()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
