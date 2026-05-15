package com.averycorp.prismtask.ui.screens.pomodoro

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.components.UpgradePrompt
import com.averycorp.prismtask.ui.theme.ThemedSubScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPomodoroScreen(
    navController: NavController,
    viewModel: SmartPomodoroViewModel = hiltViewModel()
) {
    val screenState by viewModel.screenState.collectAsState()
    val config by viewModel.config.collectAsState()
    val energyAwareConfig by viewModel.energyAwareConfig.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val planUiState by viewModel.planUiState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsState()
    val timerSeconds by viewModel.timerSecondsRemaining.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()
    val completedTaskIds by viewModel.completedTaskIds.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val incompleteTaskCount by viewModel.incompleteTaskCount.collectAsState()
    val preSessionCoaching by viewModel.preSessionCoaching.collectAsState()
    val breakSuggestion by viewModel.breakSuggestion.collectAsState()
    val sessionRecap by viewModel.sessionRecap.collectAsState()
    val showUpgradePrompt by viewModel.showUpgradePrompt.collectAsState()
    val userTier by viewModel.userTier.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    if (showUpgradePrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpgradePrompt() },
            confirmButton = {},
            text = {
                UpgradePrompt(
                    currentTier = userTier,
                    feature = "AI Smart Focus",
                    description = "Generate AI-tailored Pomodoro session plans across your incomplete tasks.",
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

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Error state also gets a snackbar as a secondary signal. Primary
    // rendering is the screen-level banner inside PlanningView.
    LaunchedEffect(planUiState) {
        when (val s = planUiState) {
            is PomodoroPlanUiState.Error -> snackbarHostState.showSnackbar(s.message)
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ThemedSubScreenTitle("Smart Focus") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (screenState) {
            PomodoroState.PLANNING -> Column(modifier = Modifier.padding(padding)) {
                energyAwareConfig?.let { energyConfig ->
                    if (energyConfig.rationale != "Using your classic Pomodoro defaults") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("\u26A1", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = energyConfig.rationale,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
                PlanningView(
                    config = config,
                    plan = plan,
                    planUiState = planUiState,
                    isLoading = isLoading,
                    incompleteTaskCount = incompleteTaskCount,
                    onGeneratePlan = { viewModel.generatePlan() },
                    onStartSession = { viewModel.startSession() },
                    onDismissMessage = { viewModel.dismissPlanUiMessage() },
                    modifier = Modifier
                )
            }

            PomodoroState.SESSION_ACTIVE -> ActiveSessionView(
                plan = plan ?: return@Scaffold,
                currentSessionIndex = currentSessionIndex,
                timerSeconds = timerSeconds,
                isTimerRunning = isTimerRunning,
                completedTaskIds = completedTaskIds,
                onPause = { viewModel.pauseTimer() },
                onResume = { viewModel.resumeTimer() },
                onCompleteTask = { viewModel.completeTask(it) },
                onEndEarly = { viewModel.endEarly() },
                modifier = Modifier.padding(padding)
            )

            PomodoroState.ON_BREAK -> BreakView(
                timerSeconds = timerSeconds,
                currentSessionIndex = currentSessionIndex,
                totalSessions = plan?.sessions?.size ?: 0,
                isLongBreak = (currentSessionIndex + 1) % 4 == 0,
                breakSuggestion = breakSuggestion,
                onDismissBreakSuggestion = { viewModel.dismissBreakSuggestion() },
                onSkipBreak = { viewModel.nextSession() },
                modifier = Modifier.padding(padding)
            )

            PomodoroState.COMPLETE -> CompletionView(
                stats = stats,
                completedTaskIds = completedTaskIds,
                plan = plan,
                sessionRecap = sessionRecap,
                onDismissRecap = { viewModel.dismissSessionRecap() },
                onPlanAnother = { viewModel.resetToPlanning() },
                onDone = { navController.popBackStack() },
                modifier = Modifier.padding(padding)
            )
        }

        // A2 Pomodoro+ pre-session coaching modal. Rendered above the scaffold
        // content so it overlays whatever view is active when a work session
        // is about to start.
        PreSessionCoachingModal(
            state = preSessionCoaching,
            onAccept = { viewModel.acceptPreSessionCoaching() },
            onDismiss = { viewModel.dismissPreSessionCoaching() }
        )
    }
}

@Composable
private fun PreSessionCoachingModal(
    state: PreSessionCoachingUiState,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        PreSessionCoachingUiState.Hidden -> Unit
        PreSessionCoachingUiState.Loading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Getting Your Assistant's Take…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Preparing a suggested approach")
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Skip") }
            }
        )
        is PreSessionCoachingUiState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Suggested Approach") },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = onAccept) { Text("Let's Go") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Skip") }
            }
        )
    }
}

@Composable
private fun PlanningView(
    config: PomodoroConfig,
    plan: PomodoroPlan?,
    planUiState: PomodoroPlanUiState,
    isLoading: Boolean,
    incompleteTaskCount: Int,
    onGeneratePlan: () -> Unit,
    onStartSession: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Empty / Error banner — the primary user-facing signal for
        // "no tasks to plan" or "couldn't reach the planner". The
        // summary card below suppresses itself when empty so we never
        // render "0 sessions • 0 min".
        when (val s = planUiState) {
            is PomodoroPlanUiState.Empty -> item {
                PlanUiStateBanner(
                    title = "No Tasks to Plan",
                    body = s.reason,
                    isError = false,
                    onDismiss = onDismissMessage
                )
            }
            is PomodoroPlanUiState.Error -> item {
                PlanUiStateBanner(
                    title = "Couldn't Generate Plan",
                    body = s.message,
                    isError = true,
                    onDismiss = onDismissMessage
                )
            }
            else -> Unit
        }
        item {
            Spacer(Modifier.height(8.dp))

            // Current Pomodoro Configuration (read-only summary — adjust in Settings)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pomodoro Configuration", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val hours = config.availableMinutes / 60
                    val mins = config.availableMinutes % 60
                    val availableText = when {
                        hours == 0 -> "${mins}m"
                        mins == 0 -> "${hours}h"
                        else -> "${hours}h ${mins}m"
                    }
                    ConfigRow(label = "Available Time", value = availableText)
                    ConfigRow(label = "Session Length", value = "${config.sessionLength} min")
                    ConfigRow(label = "Short Break", value = "${config.breakLength} min")
                    ConfigRow(label = "Long Break", value = "${config.longBreakLength} min")
                    val focusLabel = when (config.focusPreference) {
                        "deep_work" -> "Deep Work"
                        "quick_wins" -> "Quick Wins"
                        "balanced" -> "Balanced"
                        "deadline_driven" -> "Deadline Driven"
                        else -> config.focusPreference
                    }
                    ConfigRow(label = "Focus Style", value = focusLabel)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Adjust these in Settings \u203A Pomodoro.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            // Generate/Start button
            //
            // Shown when there's no successfully-planned session on screen,
            // which now covers Idle, Empty ("no tasks"), and Error. Per the
            // empty-state UX refactor, the label stays "Plan My Sessions"
            // across these variants — "Re-Plan" is reserved for the
            // post-success re-do button below.
            if (plan == null) {
                Button(
                    onClick = onGeneratePlan,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Plan My Sessions")
                }
                Text(
                    "Based on your $incompleteTaskCount incomplete tasks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Session plan display — only when we have a real non-empty plan.
        if (plan != null && plan.sessions.isNotEmpty()) {
            itemsIndexed(plan.sessions) { index, session ->
                SessionPlanCard(session = session, index = index)

                // Show break between sessions
                if (index < plan.sessions.size - 1) {
                    val isLongBreak = (index + 1) % 4 == 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            if (isLongBreak) {
                                "  ${config.longBreakLength} Min Long Break  "
                            } else {
                                "  ${config.breakLength} Min Break  "
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }
                }
            }

            item {
                // Summary
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        "${plan.sessions.size} sessions \u2022 ${plan.totalWorkMinutes} min work \u2022 " +
                            "${plan.totalBreakMinutes} min breaks",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Skipped tasks
            if (plan.skippedTasks.isNotEmpty()) {
                item {
                    Text(
                        "Skipped Tasks",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(plan.skippedTasks) { skipped ->
                    Text(
                        "Task #${skipped.taskId}: ${skipped.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Button(
                    onClick = onStartSession,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Focus")
                }
                TextButton(
                    onClick = onGeneratePlan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Re-Plan")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SessionPlanCard(session: PomodoroSession, index: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Session ${session.sessionNumber}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            session.tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${task.allocatedMinutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (session.rationale.isNotBlank()) {
                Text(
                    text = "Why: ${session.rationale}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionView(
    plan: PomodoroPlan,
    currentSessionIndex: Int,
    timerSeconds: Int,
    isTimerRunning: Boolean,
    completedTaskIds: Set<Long>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCompleteTask: (Long) -> Unit,
    onEndEarly: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSession = plan.sessions.getOrNull(currentSessionIndex) ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Session progress dots
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            plan.sessions.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .size(if (index == currentSessionIndex) 12.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                index < currentSessionIndex -> MaterialTheme.colorScheme.primary
                                index == currentSessionIndex -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
            }
        }

        Text(
            "Session ${currentSessionIndex + 1} of ${plan.sessions.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(32.dp))

        // Timer display
        val minutes = timerSeconds / 60
        val seconds = timerSeconds % 60
        Text(
            "%02d:%02d".format(minutes, seconds),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        // Pause/Resume button
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(
                onClick = { if (isTimerRunning) onPause() else onResume() },
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    if (isTimerRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isTimerRunning) "Pause" else "Resume",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(
                onClick = onEndEarly,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "End Early",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Current tasks
        Text(
            "Current Tasks",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        currentSession.tasks.forEach { task ->
            val isCompleted = task.taskId in completedTaskIds
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularCheckbox(
                        checked = isCompleted,
                        onCheckedChange = { if (!isCompleted) onCompleteTask(task.taskId) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                            color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${task.allocatedMinutes} min",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            currentSession.rationale,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BreakView(
    timerSeconds: Int,
    currentSessionIndex: Int,
    totalSessions: Int,
    isLongBreak: Boolean,
    breakSuggestion: BreakSuggestion?,
    onDismissBreakSuggestion: () -> Unit,
    onSkipBreak: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (isLongBreak) "Long Break" else "Break Time",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        val minutes = timerSeconds / 60
        val seconds = timerSeconds % 60
        Text(
            "%02d:%02d".format(minutes, seconds),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Light
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Next: Session ${currentSessionIndex + 2} of $totalSessions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // A2 Pomodoro+ AI break suggestion. Inline card so the user keeps
        // seeing the countdown — the suggestion is supplementary, not modal.
        if (breakSuggestion != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Assistant Suggests",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        breakSuggestion.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismissBreakSuggestion) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onSkipBreak) {
            Text("Skip Break")
        }
    }
}

@Composable
private fun CompletionView(
    stats: FocusStats,
    completedTaskIds: Set<Long>,
    plan: PomodoroPlan?,
    sessionRecap: SessionRecap?,
    onDismissRecap: () -> Unit,
    onPlanAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Focus Session Complete!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        // A2 Pomodoro+ AI session recap. Inline card above the stats so the
        // coach's takeaway is the first thing the user sees, then the
        // numbers. Dismissable without blocking the screen.
        if (sessionRecap != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Assistant's Recap",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sessionRecap.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismissRecap) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        // Stats
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Sessions Completed", "${stats.sessionsCompleted}")
                StatRow("Tasks Completed", "${stats.tasksCompleted}")
                StatRow("Total Focus Time", "${stats.totalFocusSeconds / 60} min")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Completed tasks
        if (plan != null && completedTaskIds.isNotEmpty()) {
            Text(
                "Completed Tasks",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            plan.sessions
                .flatMap { it.tasks }
                .filter { it.taskId in completedTaskIds }
                .forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(task.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onPlanAnother,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Plan Another Session")
        }

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlanUiStateBanner(
    title: String,
    body: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
