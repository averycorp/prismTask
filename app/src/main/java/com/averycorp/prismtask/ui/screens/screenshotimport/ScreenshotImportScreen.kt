package com.averycorp.prismtask.ui.screens.screenshotimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

/**
 * Smart Screenshot Import (G).
 *
 * On open: launches the system Photo Picker. The picker requires no
 * runtime permission on Android 13+ (and works back to API 26 via the
 * androidx.activity backport that uses GET_CONTENT under the hood).
 *
 * After a pick: encodes the image, calls the Vision endpoint, then
 * renders the same Checkbox+TextField row UI the paste-extract flow
 * uses so users can edit titles before bulk-creating tasks.
 *
 * Cancelling the initial picker pops back so the user is returned to
 * the AI hub immediately rather than landing on a permanently-empty
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotImportScreen(
    navController: NavController,
    viewModel: ScreenshotImportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val createdCount by viewModel.createdCount.collectAsStateWithLifecycle()

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            // User cancelled the picker without selecting anything. If we
            // haven't already loaded candidates, pop back so the user
            // returns to the AI hub instead of a stuck empty screen.
            if (uiState is ScreenshotImportUiState.Idle && candidates.isEmpty()) {
                navController.popBackStack()
            }
        } else {
            viewModel.onImagePicked(context, uri)
        }
    }

    // Auto-launch the picker on first open. The state guard prevents
    // re-launching on configuration change.
    LaunchedEffect(Unit) {
        if (uiState is ScreenshotImportUiState.Idle && candidates.isEmpty()) {
            pickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
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
                title = { Text("Extract From Screenshot", fontWeight = FontWeight.Bold) },
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
            when (val state = uiState) {
                ScreenshotImportUiState.Idle -> {
                    Text(
                        "Pick a screenshot to extract tasks from.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            pickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose Image")
                    }
                }

                ScreenshotImportUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Analyzing image…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                ScreenshotImportUiState.Empty -> {
                    Text(
                        "Couldn't find any tasks in this image. Try a clearer screenshot.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.onUserCancelled()
                            pickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try Another Image")
                    }
                }

                is ScreenshotImportUiState.Error -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.onUserCancelled()
                            pickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try Another Image")
                    }
                }

                ScreenshotImportUiState.Loaded -> {
                    Text(
                        "${candidates.size} task${if (candidates.size == 1) "" else "s"} found. " +
                            "Edit titles or uncheck rows you don't want, then create.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    candidates.forEachIndexed { index, candidate ->
                        ScreenshotCandidateRow(
                            candidate = candidate,
                            onToggle = { viewModel.toggle(index) },
                            onTitleChange = { viewModel.editTitle(index, it) }
                        )
                    }
                    val selectedCount = candidates.count { it.selected }
                    Button(
                        onClick = { viewModel.createSelected(context) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create $selectedCount Task${if (selectedCount == 1) "" else "s"}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotCandidateRow(
    candidate: EditableScreenshotCandidate,
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
            Column(modifier = Modifier.padding(start = 8.dp)) {
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
