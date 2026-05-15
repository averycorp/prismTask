package com.averycorp.prismtask.ui.screens.planner

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.components.ProFeature
import com.averycorp.prismtask.ui.components.ProUpgradePrompt
import com.averycorp.prismtask.ui.components.shimmer
import com.averycorp.prismtask.ui.components.sync.SyncIndicatorHost
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import java.time.format.DateTimeFormatter

private val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyPlannerScreen(
    navController: NavController,
    viewModel: WeeklyPlannerViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val selectedDayIndex by viewModel.selectedDayIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val showUpgradePrompt by viewModel.showUpgradePrompt.collectAsState()
    val userTier by viewModel.userTier.collectAsState()
    val weekStart by viewModel.weekStart.collectAsState()
    val planApplied by viewModel.planApplied.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(planApplied) {
        if (planApplied) {
            snackbarHostState.showSnackbar("Weekly plan applied! Check your Today screen.")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weekly Planner") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    SyncIndicatorHost(modifier = Modifier.padding(end = 12.dp))
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (showUpgradePrompt) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ProUpgradePrompt(
                    feature = ProFeature.AI_WEEKLY_PLAN,
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Week selector
                item(key = "week_selector") {
                    WeekSelector(
                        weekStart = weekStart,
                        onPrev = { viewModel.navigateWeek(-1) },
                        onNext = { viewModel.navigateWeek(1) }
                    )
                }

                // Configuration section
                item(key = "config") {
                    ConfigSection(
                        config = config,
                        onWorkDaysChanged = { viewModel.updateWorkDays(it) },
                        onFocusHoursChanged = { viewModel.updateFocusHours(it) },
                        onToggleFrontLoading = { viewModel.toggleFrontLoading() }
                    )
                }

                // Generate button
                item(key = "generate") {
                    Button(
                        onClick = { viewModel.generatePlan() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Generate Plan")
                    }
                }

                // Loading shimmer
                if (isLoading && plan == null) {
                    item(key = "shimmer") {
                        PlanShimmer()
                    }
                }

                // Plan display
                if (plan != null) {
                    val currentPlan = plan!!

                    // Day tabs
                    item(key = "day_tabs") {
                        if (currentPlan.days.isNotEmpty()) {
                            ScrollableTabRow(
                                selectedTabIndex = selectedDayIndex.coerceIn(0, currentPlan.days.size - 1),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                currentPlan.days.forEachIndexed { index, day ->
                                    Tab(
                                        selected = index == selectedDayIndex,
                                        onClick = { viewModel.selectDay(index) },
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(day.dayName.take(3))
                                                if (day.tasks.isNotEmpty()) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Badge { Text("${day.tasks.size}") }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Selected day content
                    if (currentPlan.days.isNotEmpty()) {
                        val dayIndex = selectedDayIndex.coerceIn(0, currentPlan.days.size - 1)
                        val selectedDay = currentPlan.days[dayIndex]

                        item(key = "day_header") {
                            Column {
                                Text(
                                    text = "${selectedDay.dayName}, ${selectedDay.date}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${selectedDay.tasks.size} tasks \u2022 ${selectedDay.totalHours}h",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Calendar events
                        if (selectedDay.calendarEvents.isNotEmpty()) {
                            items(selectedDay.calendarEvents, key = { "evt_$it" }) { event ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = event,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Tasks
                        itemsIndexed(
                            selectedDay.tasks,
                            key = { _, t -> "task_${t.taskId}" }
                        ) { _, task ->
                            PlanTaskCard(
                                task = task,
                                onClick = {
                                    navController.navigate(PrismTaskRoute.AddEditTask.createRoute(task.taskId))
                                }
                            )
                        }

                        // Habits
                        if (selectedDay.habits.isNotEmpty()) {
                            item(key = "day_habits") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    selectedDay.habits.forEach { habit ->
                                        FilterChip(
                                            selected = false,
                                            onClick = { },
                                            label = { Text(habit, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Unscheduled section
                    if (currentPlan.unscheduled.isNotEmpty()) {
                        item(key = "unscheduled_header") {
                            var expanded by remember { mutableStateOf(false) }
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expanded = !expanded }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Unscheduled (${currentPlan.unscheduled.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                AnimatedVisibility(expanded) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        currentPlan.unscheduled.forEach { task ->
                                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                                Text(
                                                    text = task.title,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = task.reason,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Summary and tips
                    item(key = "summary") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = currentPlan.weekSummary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (currentPlan.tips.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    currentPlan.tips.forEach { tip ->
                                        Text(
                                            text = "\u2022 $tip",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Apply button
                    item(key = "apply") {
                        Button(
                            onClick = { viewModel.applyPlan() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !planApplied
                        ) {
                            if (planApplied) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Plan Applied")
                            } else {
                                Text("Apply Plan")
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun WeekSelector(
    weekStart: java.time.LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val weekEnd = weekStart.plusDays(6)
    val formatter = DateTimeFormatter.ofPattern("MMM d")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Week")
        }
        Text(
            text = "Week of ${weekStart.format(formatter)} - ${weekEnd.format(formatter)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next Week")
        }
    }
}

@Composable
private fun ConfigSection(
    config: WeeklyPlanConfig,
    onWorkDaysChanged: (List<String>) -> Unit,
    onFocusHoursChanged: (Int) -> Unit,
    onToggleFrontLoading: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Configuration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Work days
                Text("Work Days:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_CODES.forEachIndexed { index, code ->
                        FilterChip(
                            selected = code in config.workDays,
                            onClick = {
                                val newDays = if (code in config.workDays) {
                                    config.workDays - code
                                } else {
                                    config.workDays + code
                                }
                                onWorkDaysChanged(newDays)
                            },
                            label = { Text(DAY_LABELS[index], style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // Focus hours
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Focus Hours/Day: ", style = MaterialTheme.typography.labelMedium)
                    IconButton(
                        onClick = { if (config.focusHoursPerDay > 1) onFocusHoursChanged(config.focusHoursPerDay - 1) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("-", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "${config.focusHoursPerDay}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = { if (config.focusHoursPerDay < 12) onFocusHoursChanged(config.focusHoursPerDay + 1) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("+", fontWeight = FontWeight.Bold)
                    }
                }

                // Front-loading toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Front-Load Hard Tasks",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = config.preferFrontLoading,
                        onCheckedChange = { onToggleFrontLoading() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanTaskCard(
    task: PlannedTask,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = task.suggestedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${task.durationMinutes}min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = task.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(100.dp)
            )
        }
    }
}

@Composable
private fun PlanShimmer() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Tab shimmer
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(5) {
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .shimmer()
                )
            }
        }
        // Cards shimmer
        repeat(4) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
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
                                .fillMaxWidth(0.4f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmer()
                        )
                    }
                }
            }
        }
    }
}
