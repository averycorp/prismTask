package com.averycorp.prismtask.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.domain.usecase.ParsedTask
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddBar(
    viewModel: QuickAddViewModel = hiltViewModel(),
    onTaskCreated: () -> Unit = {},
    modifier: Modifier = Modifier,
    plannedDateOverride: Long? = null,
    alwaysExpanded: Boolean = false,
    placeholder: String = "Add task... (try: Buy milk tomorrow #groceries !high)",
    autoStartVoice: Boolean = false,
    onVoiceMessage: (String) -> Unit = {},
    onBatchCommand: (String) -> Unit = {},
    onMultiCreate: (String) -> Unit = {}
) {
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val parsedPreview by viewModel.parsedPreview.collectAsStateWithLifecycle()
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val disambiguation by viewModel.templateDisambiguation.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val rmsLevel by viewModel.voiceRmsLevel.collectAsStateWithLifecycle()
    val voiceEnabled by viewModel.voiceInputEnabled.collectAsStateWithLifecycle()
    val continuousActive by viewModel.continuousModeActive.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.voicePartialText.collectAsStateWithLifecycle()
    val quickAddMaxLines by viewModel.quickAddMaxLines.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val voiceAvailable = remember { viewModel.voiceInputManager.isAvailable() }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleVoiceInput()
        } else {
            onVoiceMessage("Microphone permission needed for voice input")
        }
    }

    // Forward VoiceMessages (command confirmations, errors) to the host screen
    // so they can be shown in a snackbar alongside the other feedback.
    LaunchedEffect(viewModel) {
        viewModel.voiceMessages.collect { msg -> onVoiceMessage(msg) }
    }

    // A2 NLP batch ops — when the user submits a batch command, the VM
    // emits to `batchIntents`. The host screen navigates to the preview.
    LaunchedEffect(viewModel) {
        viewModel.batchIntents.collect { commandText -> onBatchCommand(commandText) }
    }

    // Multi-task creation (Phase B / PR-C). When the user submits
    // newline / comma-list input matching MultiCreateDetector, the VM
    // emits the raw text and the host navigates to the bottom sheet.
    LaunchedEffect(viewModel) {
        viewModel.multiCreateIntents.collect { rawText -> onMultiCreate(rawText) }
    }

    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice && voiceAvailable && voiceEnabled) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.toggleVoiceInput()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val showMic = voiceAvailable && voiceEnabled

    // Disambiguation popup — shown when a "/query" shortcut matches more
    // than one template and the user needs to pick.
    disambiguation?.let { candidates ->
        AlertDialog(
            onDismissRequest = { viewModel.onDismissDisambiguation() },
            title = { Text("Pick A Template") },
            text = {
                Column {
                    Text(
                        text = "Multiple Templates Match Your Shortcut. Tap One To Use:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    candidates.forEach { template ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onDisambiguationSelected(
                                        template.id,
                                        plannedDateOverride
                                    )
                                    onTaskCreated()
                                }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = template.icon ?: "\uD83D\uDCCB",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                template.category?.let { category ->
                                    Text(
                                        text = category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onDismissDisambiguation() }) {
                    Text("Cancel")
                }
            }
        )
    }

    val expandedState = alwaysExpanded || isExpanded
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (!expandedState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.onToggleExpand() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Quick Add")
                }
                if (showMic) {
                    VoiceInputButton(
                        listening = isListening,
                        rmsLevel = rmsLevel,
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.toggleVoiceInput()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onLongClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.startContinuousVoiceMode()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
            }
        } else {
            if (!alwaysExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { viewModel.onToggleExpand() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ExpandLess,
                            contentDescription = "Collapse",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { viewModel.onInputChanged(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (isListening) "Listening…" else placeholder
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                viewModel.onSubmit(plannedDateOverride)
                                onTaskCreated()
                            },
                            enabled = inputText.isNotBlank() && !isSubmitting
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Submit task")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        // Mirror the Send IconButton's enabled gate so a
                        // hardware-keyboard Enter or fast soft-keyboard
                        // double-Done can't race the submit slot. The
                        // upstream `_isSubmitting` re-entry guard in
                        // QuickAddViewModel.onSubmit catches a slip-through,
                        // but this is the symmetric belt-and-suspenders
                        // matching the IconButton at line ~275.
                        if (inputText.isNotBlank() && !isSubmitting) {
                            viewModel.onSubmit(plannedDateOverride)
                            onTaskCreated()
                        }
                    }),
                    // Multi-line paste is required for batch add: MultiCreateDetector
                    // rule (a) only fires on actual newlines, so `singleLine = true`
                    // silently dropped multi-task pastes into a single combined title.
                    // `quickAddMaxLines` (default 5, user-tunable via E2) keeps the
                    // field compact while preserving newlines; `imeAction = Done`
                    // still routes Enter to submit on soft keyboards.
                    singleLine = false,
                    maxLines = quickAddMaxLines,
                    shape = RoundedCornerShape(12.dp)
                )
                if (showMic) {
                    Spacer(modifier = Modifier.width(4.dp))
                    VoiceInputButton(
                        listening = isListening,
                        rmsLevel = rmsLevel,
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.toggleVoiceInput()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onLongClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.startContinuousVoiceMode()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = parsedPreview != null && inputText.isNotBlank() && !isListening,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                parsedPreview?.let { parsed ->
                    ParsedPreview(parsed)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    VoiceModeHost(
        showOverlay = continuousActive,
        listening = isListening,
        transcript = partialTranscript,
        rmsLevel = rmsLevel,
        onStopContinuous = { viewModel.stopContinuousVoiceMode() },
        onRestartListening = { viewModel.restartContinuousListening() }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParsedPreview(parsed: ParsedTask) {
    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        parsed.templateQuery?.let { query ->
            PreviewChip(
                label = "Template: $query",
                icon = "\uD83D\uDCCB",
                color = MaterialTheme.colorScheme.primary
            )
        }

        parsed.dueDate?.let { millis ->
            PreviewChip(
                label = dateFormat.format(Date(millis)),
                icon = "\uD83D\uDCC5",
                color = MaterialTheme.colorScheme.primary
            )
        }

        parsed.dueTime?.let { millis ->
            PreviewChip(
                label = timeFormat.format(Date(millis)),
                icon = "\uD83D\uDD50",
                color = MaterialTheme.colorScheme.primary
            )
        }

        parsed.tags.forEach { tag ->
            PreviewChip(
                label = "#$tag",
                icon = null,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        parsed.projectName?.let { name ->
            PreviewChip(
                label = name,
                icon = "\uD83D\uDCC1",
                color = MaterialTheme.colorScheme.secondary
            )
        }

        if (parsed.priority > 0) {
            val priorityLabel = when (parsed.priority) {
                1 -> "Low"
                2 -> "Medium"
                3 -> "High"
                4 -> "Urgent"
                else -> ""
            }
            val priorityColor = LocalPriorityColors.current.forLevel(parsed.priority)
            PreviewChip(label = priorityLabel, icon = null, color = priorityColor)
        }

        parsed.recurrenceHint?.let { hint ->
            PreviewChip(
                label = hint.replaceFirstChar { it.uppercase() },
                icon = "\uD83D\uDD01",
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun PreviewChip(
    label: String,
    icon: String?,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Text(icon, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
