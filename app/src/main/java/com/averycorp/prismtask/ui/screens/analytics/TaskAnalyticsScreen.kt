package com.averycorp.prismtask.ui.screens.analytics

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.components.ContributionGrid
import com.averycorp.prismtask.ui.components.EmptyState
import com.averycorp.prismtask.ui.components.StreakBadge
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskAnalyticsScreen(
    navController: NavController,
    viewModel: TaskAnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Productivity Dashboard", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isPro && state.stats != null) {
                        IconButton(onClick = {
                            val markdown = viewModel.buildExportMarkdown()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_SUBJECT, "PrismTask Analytics Report")
                                putExtra(Intent.EXTRA_TEXT, markdown)
                            }
                            try {
                                context.startActivity(
                                    Intent.createChooser(intent, "Share Analytics Report")
                                )
                            } catch (_: Exception) {
                                // No share targets available — silent no-op.
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Share Analytics Report"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading Analytics\u2026", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        val stats = state.stats
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Summary tile section \u2014 Pro-gated (web PR #715 port, slice 1)
            state.summary?.let { summary ->
                if (state.isPro) {
                    AnalyticsSummaryTiles(summary = summary, accent = accentColor)
                } else {
                    AnalyticsSummaryProUpsell()
                }
            }

            // Productivity score section \u2014 Pro-gated (web PR #715 port, slice 3)
            if (state.isPro) {
                state.productivity?.let { productivity ->
                    ProductivityScoreSection(
                        response = productivity,
                        selectedRange = state.productivityRange,
                        onRangeSelected = { viewModel.setProductivityRange(it) },
                        accent = accentColor,
                        streak = state.streak
                    )
                    if (productivity.scores.isNotEmpty()) {
                        ProductivityHeatmap(
                            scores = productivity.scores,
                            accent = accentColor
                        )
                    }
                }
                // Time-tracking bar chart \u2014 Pro-gated (P2-C, C4 + C5)
                state.timeTracking?.let { timeTracking ->
                    TimeTrackingSection(
                        response = timeTracking,
                        selectedRange = state.productivityRange,
                        onRangeSelected = { viewModel.setProductivityRange(it) },
                        accent = accentColor
                    )
                }
                // Habit correlations \u2014 Pro-gated, on-demand (server
                // rate-limits to 1 call/day so don't auto-fetch).
                HabitCorrelationsSection(accent = accentColor)
                // Leisure score section (Leisure Budget v2.0 \u2014 Item 6).
                // Standalone score; does not interact with productivity score.
                LeisureScoreSection(accent = accentColor)
            } else {
                // Free-tier replacements so the screen doesn't have a
                // silent gap where the Pro charts would render. Each
                // upsell mirrors the section it stands in for so users
                // see exactly which feature unlocks with Pro.
                AnalyticsSectionProUpsell(
                    title = "Unlock Productivity Score Chart",
                    body = "Daily 0\u2013100 score with trend, best/worst-day callouts, and a 12-week heatmap \u2014 included with Pro."
                )
                AnalyticsSectionProUpsell(
                    title = "Unlock Time Tracking",
                    body = "Daily logged-time bars from manual logs and Pomodoro auto-tracking \u2014 included with Pro."
                )
                AnalyticsSectionProUpsell(
                    title = "Unlock Habit Correlations",
                    body = "AI-powered analysis of which habits boost your productivity \u2014 included with Pro."
                )
            }

            if (stats == null || stats.totalCompleted == 0) {
                EmptyState(
                    icon = Icons.Default.BarChart,
                    title = "Complete Some Tasks To See Analytics Here",
                    subtitle = "Your Task Completion Insights Will Appear Once You Start Checking Off Tasks"
                )
                return@Column
            }

            // Period selector chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AnalyticsPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.selectedPeriod == period,
                        onClick = { viewModel.setPeriod(period) },
                        label = { Text(period.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // Project filter dropdown
            if (state.projects.isNotEmpty()) {
                ProjectFilterDropdown(
                    projects = state.projects,
                    selectedProjectId = state.selectedProjectId,
                    onProjectSelected = { viewModel.setProject(it) }
                )
            }

            // Stat cards row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                StatCard("Total Completed", "${stats.totalCompleted}", accentColor)
                if (stats.currentStreak > 0) {
                    StreakStatCard("Current Streak", stats.currentStreak)
                }
                if (stats.longestStreak > 0) {
                    StatCard("Longest Streak", "\uD83C\uDFC6 ${stats.longestStreak}d", accentColor)
                }
                stats.avgDaysToComplete?.let {
                    StatCard("Avg Days To Complete", "%.1f".format(it), accentColor)
                }
                OnTimeRateCard(stats.overdueRate)
                StatCard(
                    "Completion Rate",
                    "%.1f/day".format(
                        if (state.selectedPeriod.days <= 7) {
                            stats.completionRate7Day
                        } else {
                            stats.completionRate30Day
                        }
                    ),
                    accentColor
                )
            }

            // Contribution grid
            Text("Activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val gridWeeks = when (state.selectedPeriod) {
                AnalyticsPeriod.WEEK -> 2
                AnalyticsPeriod.MONTH -> 5
                AnalyticsPeriod.QUARTER -> 13
                AnalyticsPeriod.YEAR -> 52
            }
            ContributionGrid(
                completionsByDay = stats.completionsByDate,
                targetPerDay = 1,
                habitColor = accentColor,
                weeks = gridWeeks,
                firstDayOfWeek = state.firstDayOfWeek,
                modifier = Modifier.fillMaxWidth()
            )

            // Weekly trend line chart
            if (stats.completionsByDate.size > 1) {
                Text("Daily Trend", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                DailyTrendChart(
                    data = stats.completionsByDate.values.toList(),
                    color = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            }

            // Day of week bar chart
            if (stats.completionsByDayOfWeek.isNotEmpty()) {
                Text("By Day Of Week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                DayOfWeekBarChart(
                    completions = stats.completionsByDayOfWeek,
                    bestDay = stats.bestDay,
                    worstDay = stats.worstDay,
                    color = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
            }

            // Time of day distribution
            if (stats.completionsByHour.isNotEmpty()) {
                Text("Time Of Day Distribution", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TimeOfDayChart(
                    completionsByHour = stats.completionsByHour,
                    peakHour = stats.peakHour,
                    color = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
            }

            // Best/worst analysis card
            InsightsCard(stats)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectFilterDropdown(
    projects: List<com.averycorp.prismtask.data.local.entity.ProjectEntity>,
    selectedProjectId: Long?,
    onProjectSelected: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = if (selectedProjectId == null) {
        "All Projects"
    } else {
        projects.find { it.id == selectedProjectId }?.name ?: "All Projects"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        TextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All Projects") },
                onClick = {
                    onProjectSelected(null)
                    expanded = false
                }
            )
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text("${project.icon} ${project.name}") },
                    onClick = {
                        onProjectSelected(project.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.width(120.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StreakStatCard(label: String, streak: Int) {
    Card(
        modifier = Modifier.width(120.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StreakBadge(streak = streak)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OnTimeRateCard(overdueRate: Double?) {
    val onTimeRate = if (overdueRate != null) (100.0 - overdueRate) else 100.0
    val c = LocalPrismColors.current
    val color = when {
        onTimeRate >= 80.0 -> c.successColor
        onTimeRate >= 60.0 -> c.warningColor
        else -> c.destructiveColor
    }
    Card(
        modifier = Modifier.width(120.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("%.0f%%".format(onTimeRate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "On-Time Rate",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DailyTrendChart(data: List<Int>, color: Color, modifier: Modifier = Modifier) {
    val maxVal = data.maxOrNull()?.coerceAtLeast(1) ?: 1
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            if (data.size < 2) return@Canvas
            val stepX = size.width / (data.size - 1)
            val path = Path()
            data.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - (v.toFloat() / maxVal * size.height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            data.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - (v.toFloat() / maxVal * size.height)
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun DayOfWeekBarChart(
    completions: Map<DayOfWeek, Int>,
    bestDay: DayOfWeek?,
    worstDay: DayOfWeek?,
    color: Color,
    modifier: Modifier = Modifier
) {
    val days = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    )
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    val maxVal = completions.values.maxOrNull()?.coerceAtLeast(1) ?: 1

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEachIndexed { i, day ->
                val count = completions[day] ?: 0
                val heightFraction = (count.toFloat() / maxVal).coerceIn(0.05f, 1f)
                val prismColors = LocalPrismColors.current
                val barColor = when (day) {
                    bestDay -> prismColors.successColor
                    worstDay -> prismColors.warningColor
                    else -> color
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((60 * heightFraction).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(barColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(labels[i], style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun TimeOfDayChart(
    completionsByHour: Map<Int, Int>,
    peakHour: Int?,
    color: Color,
    modifier: Modifier = Modifier
) {
    val maxVal = completionsByHour.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peakHourColor = LocalPrismColors.current.successColor

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val barWidth = size.width / 24f
                for (hour in 0..23) {
                    val count = completionsByHour[hour] ?: 0
                    val heightFraction = count.toFloat() / maxVal
                    val barHeight = heightFraction * size.height
                    val barColor = if (hour == peakHour) peakHourColor else color
                    drawRect(
                        color = barColor,
                        topLeft = Offset(hour * barWidth, size.height - barHeight),
                        size = androidx.compose.ui.geometry
                            .Size(barWidth * 0.8f, barHeight)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("12am", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("6am", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("12pm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("6pm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("12am", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InsightsCard(stats: com.averycorp.prismtask.data.repository.TaskCompletionStats) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Insights", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            stats.bestDay?.let {
                Text(
                    "Most Productive Day: ${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            stats.worstDay?.let {
                Text(
                    "Least Productive Day: ${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            stats.peakHour?.let {
                Text(
                    "Peak Productivity Hour: ${formatHour(it)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun formatHour(hour: Int): String = when {
    hour == 0 -> "12:00 AM"
    hour < 12 -> "$hour:00 AM"
    hour == 12 -> "12:00 PM"
    else -> "${hour - 12}:00 PM"
}
