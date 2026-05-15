package com.averycorp.prismtask.ui.screens.briefing

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.components.ProFeature
import com.averycorp.prismtask.ui.components.ProUpgradePrompt
import com.averycorp.prismtask.ui.components.shimmer
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyBriefingScreen(
    navController: NavController,
    viewModel: DailyBriefingViewModel = hiltViewModel()
) {
    val briefing by viewModel.briefing.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val showUpgradePrompt by viewModel.showUpgradePrompt.collectAsState()
    val userTier by viewModel.userTier.collectAsState()
    val orderApplied by viewModel.orderApplied.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.generateBriefing()
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(orderApplied) {
        if (orderApplied) {
            snackbarHostState.showSnackbar("Today's plan updated!")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Briefing") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (briefing != null) {
                        IconButton(onClick = { viewModel.refreshBriefing() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (showUpgradePrompt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ProUpgradePrompt(
                    feature = ProFeature.AI_BRIEFING,
                    currentTier = userTier,
                    onUpgrade = { _ ->
                        viewModel.dismissUpgradePrompt()
                        navController.navigate("settings/subscription")
                    },
                    onRestorePurchase = { viewModel.restorePurchases() },
                    onDismiss = {
                        viewModel.dismissUpgradePrompt()
                        navController.popBackStack()
                    }
                )
            }
        } else if (isLoading && briefing == null) {
            BriefingShimmer(modifier = Modifier.padding(padding))
        } else if (briefing != null) {
            val currentBriefing = briefing!!
            BriefingContent(
                briefing = currentBriefing,
                orderApplied = orderApplied,
                onApplyOrder = { viewModel.applyOrder() },
                onTaskClick = { taskId ->
                    navController.navigate(PrismTaskRoute.AddEditTask.createRoute(taskId))
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun BriefingContent(
    briefing: DailyBriefing,
    orderApplied: Boolean,
    onApplyOrder: () -> Unit,
    onTaskClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Greeting section
        item(key = "greeting") {
            Spacer(Modifier.height(4.dp))
            Text(
                text = briefing.greeting,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            DayTypeChip(briefing.dayType)
        }

        // Top Priorities
        if (briefing.topPriorities.isNotEmpty()) {
            item(key = "priorities_header") {
                SectionHeader(title = "Top Priorities")
            }
            itemsIndexed(
                items = briefing.topPriorities,
                key = { _, p -> "priority_${p.taskId}" }
            ) { index, priority ->
                PriorityCard(
                    index = index + 1,
                    priority = priority,
                    onClick = { onTaskClick(priority.taskId) }
                )
            }
        }

        // Heads Up
        if (briefing.headsUp.isNotEmpty()) {
            item(key = "headsup_header") {
                SectionHeader(title = "Heads Up")
            }
            items(briefing.headsUp, key = { it }) { warning ->
                HeadsUpRow(warning)
            }
        }

        // Suggested Order
        if (briefing.suggestedOrder.isNotEmpty()) {
            item(key = "order_header") {
                SectionHeader(title = "Suggested Order")
            }
            itemsIndexed(
                items = briefing.suggestedOrder,
                key = { _, t -> "order_${t.taskId}" }
            ) { index, task ->
                SuggestedTaskRow(
                    index = index + 1,
                    task = task,
                    onClick = { onTaskClick(task.taskId) }
                )
            }
            item(key = "apply_order") {
                Button(
                    onClick = onApplyOrder,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !orderApplied
                ) {
                    if (orderApplied) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Order Applied")
                    } else {
                        Text("Apply This Order")
                    }
                }
            }
        }

        // Habit Reminders
        if (briefing.habitReminders.isNotEmpty()) {
            item(key = "habits_header") {
                SectionHeader(title = "Today's Habits")
            }
            item(key = "habit_chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(briefing.habitReminders, key = { it }) { habit ->
                        AssistChip(
                            onClick = { },
                            label = { Text(habit) }
                        )
                    }
                }
            }
        }

        // Pending sync footer — surfaces tasks the AI returned that we
        // can't bind to a local row (typically created on another device,
        // not yet synced down).
        if (briefing.pendingSyncTitles.isNotEmpty()) {
            item(key = "pending_sync_footer") {
                PendingSyncFooter(titles = briefing.pendingSyncTitles)
            }
        }

        item(key = "bottom_spacer") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun DayTypeChip(dayType: String) {
    val c = LocalPrismColors.current
    val (label, color) = when (dayType.lowercase()) {
        "light" -> "Light Day" to c.successColor
        "heavy" -> "Heavy Day" to c.destructiveColor
        else -> "Moderate Day" to c.warningColor
    }
    Box(
        modifier = Modifier
            .clip(LocalPrismShapes.current.chip)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PriorityCard(
    index: Int,
    priority: BriefingPriority,
    onClick: () -> Unit
) {
    val palette = LocalPrismColors.current.dataVisualizationPalette
    val borderColor = palette.getOrElse(index - 1) { palette.last() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Number badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(borderColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = priority.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (priority.reason.isNotBlank()) {
                    Text(
                        text = "Why: ${priority.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingSyncFooter(titles: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${titles.size} pending sync from another device",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        titles.forEach { title ->
            Text(
                text = "• $title",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun HeadsUpRow(warning: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = LocalPrismColors.current.warningColor
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = warning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuggestedTaskRow(
    index: Int,
    task: SuggestedTask,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = task.suggestedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (task.reason.isNotBlank()) {
                    Text(
                        text = "Why: ${task.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BriefingShimmer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Greeting shimmer
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
        )
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .shimmer()
        )

        Spacer(Modifier.height(8.dp))

        // Section header shimmer
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
        )

        // Priority cards shimmer
        repeat(3) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .shimmer()
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmer()
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmer()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Suggested order shimmer
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
        )
        repeat(4) {
            Row(modifier = Modifier.padding(vertical = 6.dp)) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmer()
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmer()
                )
            }
        }
    }
}
