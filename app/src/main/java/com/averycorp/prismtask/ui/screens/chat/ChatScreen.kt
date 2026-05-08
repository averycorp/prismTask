package com.averycorp.prismtask.ui.screens.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.repository.ChatMessage
import com.averycorp.prismtask.ui.components.ProFeature
import com.averycorp.prismtask.ui.components.UpgradePrompt
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val showUpgradePrompt by viewModel.showUpgradePrompt.collectAsStateWithLifecycle()
    val userTier by viewModel.userTier.collectAsStateWithLifecycle()
    val contextTask by viewModel.contextTask.collectAsStateWithLifecycle()
    val showDisclosure by viewModel.showDisclosure.collectAsStateWithLifecycle()
    val showClearConfirm by viewModel.showClearConfirm.collectAsStateWithLifecycle()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Show snackbar (with optional Undo) for executed actions.
    // Phase 2 audit fix #4 (C.2): destructive ops carry an undoAction
    // callback the snackbar surfaces as an action button.
    LaunchedEffect(Unit) {
        viewModel.actionResults.collect { result ->
            val undoLabel = result.undoLabel
            val undoAction = result.undoAction
            if (undoLabel != null && undoAction != null) {
                val outcome = snackbarHostState.showSnackbar(
                    message = result.message,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                    withDismissAction = true
                )
                if (outcome == SnackbarResult.ActionPerformed) {
                    coroutineScope.launch {
                        runCatching { undoAction() }
                    }
                }
            } else {
                snackbarHostState.showSnackbar(result.message)
            }
        }
    }

    // Show error as snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // B.4 (F8 follow-on): handle nav requests emitted by chat actions.
    // start_timer asks the screen to open the Timer screen at the user's
    // configured default duration; the AI-suggested duration is already
    // surfaced in the snackbar text by the ViewModel.
    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is ChatViewModel.ChatNavEvent.OpenTimer ->
                    navController.navigate(PrismTaskRoute.Timer.route)
            }
        }
    }

    if (showUpgradePrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpgradePrompt() },
            confirmButton = {},
            text = {
                UpgradePrompt(
                    currentTier = userTier,
                    feature = ProFeature.AI_CHAT.label,
                    description = ProFeature.AI_CHAT.description,
                    onUpgrade = { _ ->
                        viewModel.dismissUpgradePrompt()
                        navController.navigate("settings/subscription")
                    },
                    onDismiss = { viewModel.dismissUpgradePrompt() }
                )
            }
        )
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDisclosure() },
            title = { Text("AI Chat") },
            text = {
                Text(
                    "Your messages are processed by AI to provide coaching, " +
                        "along with the last few turns of conversation for context. " +
                        "When chat is opened from a task, the AI also sees that " +
                        "task's title, description, due date, priority, project " +
                        "name, and completion state. Conversations are now saved " +
                        "to your PrismTask account so you can pick up the thread " +
                        "on any signed-in device, and stay until you delete them. " +
                        "The AI service itself doesn't keep your messages beyond " +
                        "answering your prompt."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDisclosure() }) {
                    Text("Got It")
                }
            }
        )
    }

    // C.3 (F8 follow-on): confirm before dropping the conversation. The
    // DeleteSweep button used to single-tap-clear with no undo path, so
    // a misplaced thumb wiped active discussions.
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearConfirm() },
            title = { Text("Clear Chat?") },
            text = {
                Text("This will delete all messages in the current conversation. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearConversation() }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearConfirm() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Coach", style = MaterialTheme.typography.titleMedium)
                        val currentContextTask = contextTask
                        val subtitle = if (currentContextTask != null) {
                            "Talking About: ${currentContextTask.title}"
                        } else {
                            "General"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { viewModel.requestClearConversation() }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear Chat"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Welcome message if empty
                if (messages.isEmpty()) {
                    item(key = "welcome") {
                        WelcomeCard(
                            onStarterPrompt = { prompt -> viewModel.sendMessage(prompt) }
                        )
                    }
                }

                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(
                        message = message,
                        onActionClick = { action -> viewModel.executeAction(action) }
                    )
                }

                // Typing indicator
                if (isTyping) {
                    item(key = "typing") {
                        TypingIndicator()
                    }
                }
            }

            // Input bar
            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = !isTyping
            )
        }
    }
}

/**
 * C.5 (F8 follow-on): four starter prompts seed the empty welcome
 * card. Tapping a chip sends the prompt straight to chat (rather than
 * pre-filling the input), so the user gets a useful response without an
 * extra Send tap. Each prompt maps to an existing chat capability —
 * "what should I focus on" routes through the daily-briefing-shaped
 * reasoning, "reschedule overdue" produces a reschedule_batch action,
 * "break down my biggest task" produces a breakdown action, "25-min
 * focus session" produces a start_timer action wired to B.4.
 */
private val STARTER_PROMPTS = listOf(
    "What should I focus on today?",
    "Help me reschedule overdue tasks",
    "Break down my biggest task",
    "Suggest a 25-minute focus session"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeCard(
    onStarterPrompt: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Hey there",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "I can help you break down tasks, plan your day, " +
                    "or just talk through what feels stuck. " +
                    "What's on your mind?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                STARTER_PROMPTS.forEach { prompt ->
                    AssistChip(
                        onClick = { onStarterPrompt(prompt) },
                        label = {
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    onActionClick: (ChatActionResponse) -> Unit
) {
    val isUser = message.role == ChatMessage.Role.USER
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = maxWidth)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Action buttons for assistant messages
        if (!isUser && message.actions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                message.actions.forEach { action ->
                    ActionChip(
                        action = action,
                        onClick = { onActionClick(action) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    action: ChatActionResponse,
    onClick: () -> Unit
) {
    // C.4 (F8 follow-on): enrich the visible label with task/duration
    // context for chips whose payload carries it. TalkBack reads these
    // labels out of bubble order, so a static "Add Task" / "Start a
    // Timer" leaves blind users without context. The fields used here
    // are the same ones already on the action payload — no extra
    // threading needed for context-task chips because their parent
    // bubble carries the task title visually + via TalkBack proximity.
    val label = when (action.type) {
        "complete" -> "Mark Complete"
        "reschedule" -> when (action.to) {
            "today" -> "Move to Today"
            "tomorrow" -> "Move to Tomorrow"
            "next_week" -> "Move to Next Week"
            else -> "Reschedule"
        }
        "reschedule_batch" -> "Reschedule ${action.taskIds?.size ?: ""} Tasks"
        "breakdown" -> "Break It Down"
        "archive" -> "Just Drop It"
        "start_timer" -> {
            val minutes = action.minutes?.takeIf { it in 1..480 }
            if (minutes != null) "Start a $minutes-Min Timer" else "Start a Timer"
        }
        "create_task" -> {
            val title = action.title?.takeIf { it.isNotBlank() }
            if (title != null) "Add Task: $title" else "Add Task"
        }
        else -> action.type.replaceFirstChar { it.uppercase() }
    }

    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing_dots")

    Row(
        modifier = Modifier.padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core
                        .StartOffset(index * 200)
                ),
                label = "dot_$index"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                    )
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "What's on your mind?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 4,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled && value.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                )
            }
        }
    }
}
