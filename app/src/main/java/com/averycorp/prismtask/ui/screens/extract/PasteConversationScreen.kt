package com.averycorp.prismtask.ui.screens.extract

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteConversationScreen(
    navController: NavController,
    sharedText: String? = null,
    viewModel: PasteConversationViewModel = hiltViewModel()
) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val createdCount by viewModel.createdCount.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val targetProjectId by viewModel.targetProjectId.collectAsStateWithLifecycle()
    val targetProjectIsNone by viewModel.targetProjectIsNone.collectAsStateWithLifecycle()

    var showCreateProjectDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            viewModel.onInputChange(sharedText)
            viewModel.extract()
        }
    }

    LaunchedEffect(createdCount) {
        if (createdCount != null) {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extract Tasks", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Paste conversation text (Claude, ChatGPT, email, meeting notes).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onInputChange,
                label = { Text("Conversation Text") },
                minLines = 6,
                maxLines = 16,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.extract() },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Extract Tasks")
            }

            if (candidates.isEmpty()) {
                Text(
                    if (input.isBlank()) {
                        "Paste some text above and tap Extract Tasks."
                    } else {
                        "Tap Extract Tasks to find action items."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "${candidates.size} candidate${if (candidates.size == 1) "" else "s"} found",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                candidates.forEachIndexed { index, candidate ->
                    CandidateRow(
                        candidate = candidate,
                        onToggle = { viewModel.toggle(index) },
                        onTitleChange = { viewModel.editTitle(index, it) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Save to project",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                ProjectDropdown(
                    selectedProjectId = targetProjectId,
                    isNone = targetProjectIsNone,
                    projects = projects,
                    onSelectAuto = { viewModel.onTargetProjectChange(null, isNone = false) },
                    onSelectNone = { viewModel.onTargetProjectChange(null, isNone = true) },
                    onSelectProject = { viewModel.onTargetProjectChange(it, isNone = false) },
                    onCreateNew = { showCreateProjectDialog = true }
                )

                Button(
                    onClick = { viewModel.createSelected() },
                    enabled = candidates.any { it.selected },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val selectedCount = candidates.count { it.selected }
                    Text("Create $selectedCount Task${if (selectedCount == 1) "" else "s"}")
                }
            }
        }
    }

    if (showCreateProjectDialog) {
        var newProjectName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreateProjectDialog = false },
            title = { Text("New Project") },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (newProjectName.isNotBlank()) {
                            viewModel.onTargetProjectNew(newProjectName.trim())
                            showCreateProjectDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showCreateProjectDialog = false }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CandidateRow(
    candidate: EditableCandidate,
    onToggle: () -> Unit,
    onTitleChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = candidate.selected, onCheckedChange = { onToggle() })
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = candidate.title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Confidence ${(candidate.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDropdown(
    selectedProjectId: Long?,
    isNone: Boolean,
    projects: List<com.averycorp.prismtask.data.local.entity.ProjectEntity>,
    onSelectAuto: () -> Unit,
    onSelectNone: () -> Unit,
    onSelectProject: (Long) -> Unit,
    onCreateNew: () -> Unit
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val selectedProject = projects.find { it.id == selectedProjectId }
    
    val displayText = when {
        isNone -> "No project"
        selectedProject != null -> "${selectedProject.icon} ${selectedProject.name}"
        else -> "Auto-detect from text"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        androidx.compose.material3.OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto-detect from text") },
                onClick = {
                    onSelectAuto()
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("No project") },
                onClick = {
                    onSelectNone()
                    expanded = false
                }
            )
            androidx.compose.material3.HorizontalDivider()
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text("${project.icon} ${project.name}") },
                    onClick = {
                        onSelectProject(project.id)
                        expanded = false
                    }
                )
            }
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(
                text = { Text("+ Create new project...") },
                onClick = {
                    onCreateNew()
                    expanded = false
                }
            )
        }
    }
}
