package com.averycorp.prismtask.ui.screens.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Leisure Budget v2.0 — Item 6. Dashboard section that lives inside the
 * existing [TaskAnalyticsScreen] (NOT a new screen — anti-pattern #3
 * forbids creating `AnalyticsDashboardScreen`).
 *
 * Surfaces:
 *   • 7-day leisure-minutes sparkline
 *   • Category variety bar chart (last 7 days)
 *   • Current leisure streak badge
 *   • 30-day budget hit-rate %
 */
@Composable
fun LeisureScoreSection(
    accent: Color,
    viewModel: LeisureScoreSectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Leisure",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Standalone from productivity — leisure is its own success metric.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Last 7 days (minutes)",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Sparkline(values = state.sparkline7Day, accent = accent)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(
                    label = "Streak",
                    value = "${state.currentStreakDays}d"
                )
                Stat(
                    label = "Hit rate (30d)",
                    value = "${state.hitRate30DayPct}%"
                )
                Stat(
                    label = "Today",
                    value = "${state.minutesLoggedToday}/${state.targetMinutesToday}m"
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Category variety (last 7 days)",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            CategoryVarietyBars(
                values = state.categoryVariety7Day,
                labels = state.categoryLabels,
                accent = accent
            )
        }
    }
}

@Composable
private fun Sparkline(values: List<Int>, accent: Color) {
    if (values.isEmpty()) {
        Text(
            text = "Nothing logged yet — start your first session below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1).coerceAtLeast(1).toFloat()
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = h - (value.toFloat() / maxValue) * (h - 8f) - 4f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
        // Dots at each sample point.
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = h - (value.toFloat() / maxValue) * (h - 8f) - 4f
            drawCircle(color = accent, radius = 4f, center = Offset(x, y))
        }
    }
}

@Composable
private fun CategoryVarietyBars(
    values: List<Int>,
    labels: List<String>,
    accent: Color
) {
    if (values.isEmpty() || values.sum() == 0) {
        Text(
            text = "No category variety yet — try logging different kinds of leisure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
        val barCount = values.size
        if (barCount == 0) return@Canvas
        val barGap = 8f
        val totalGap = barGap * (barCount - 1)
        val barWidth = (size.width - totalGap) / barCount
        values.forEachIndexed { index, value ->
            val x = index * (barWidth + barGap)
            val barHeight = (value.toFloat() / maxValue) * (size.height - 6f)
            val y = size.height - barHeight - 2f
            drawRect(
                color = accent.copy(alpha = 0.85f),
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
