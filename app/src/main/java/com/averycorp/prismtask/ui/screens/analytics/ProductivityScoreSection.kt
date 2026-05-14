package com.averycorp.prismtask.ui.screens.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.ProductiveStreakSnapshot
import com.averycorp.prismtask.domain.model.BestWorstDay
import com.averycorp.prismtask.domain.model.DailyScore
import com.averycorp.prismtask.domain.model.ProductivityRange
import com.averycorp.prismtask.domain.model.ProductivityScoreResponse
import com.averycorp.prismtask.domain.model.ProductivityTrend
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Productivity score section on `TaskAnalyticsScreen` — header + range selector
 * + Compose Canvas area chart + best/worst-day callouts. Mirrors the web PR
 * #715 productivity section (`AnalyticsScreen.tsx` lines 303-381) using
 * Compose Canvas (project convention) rather than a chart library.
 */
@Composable
fun ProductivityScoreSection(
    response: ProductivityScoreResponse,
    selectedRange: ProductivityRange,
    onRangeSelected: (ProductivityRange) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    streak: ProductiveStreakSnapshot? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ProductivityHeader(response = response, streak = streak)
            Spacer(modifier = Modifier.height(8.dp))
            ProductivityRangeSelector(
                selected = selectedRange,
                onSelected = onRangeSelected,
                accent = accent
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (response.scores.size >= 2) {
                ProductivityAreaChart(
                    scores = response.scores,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            } else {
                Text(
                    text = "Need at least 2 days of data to plot the chart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            if (response.bestDay != null || response.worstDay != null) {
                Spacer(modifier = Modifier.height(8.dp))
                BestWorstRow(best = response.bestDay, worst = response.worstDay)
            }
        }
    }
}

@Composable
private fun ProductivityHeader(
    response: ProductivityScoreResponse,
    streak: ProductiveStreakSnapshot?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Productivity Score",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Average ${response.averageScore.roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (streak != null && streak.hasAnyHistory) {
            ProductiveStreakChip(streak = streak)
            Spacer(modifier = Modifier.size(8.dp))
        }
        TrendChip(trend = response.trend)
    }
}

@Composable
private fun ProductiveStreakChip(streak: ProductiveStreakSnapshot) {
    val tint = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "${streak.currentDays}d",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(start = 4.dp)
        )
        if (streak.longestDays > streak.currentDays) {
            Text(
                text = " · best ${streak.longestDays}d",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrendChip(trend: ProductivityTrend) {
    val (icon, tint, label) = when (trend) {
        ProductivityTrend.IMPROVING -> Triple(Icons.Filled.TrendingUp, Color(0xFF2E7D32), "Improving")
        ProductivityTrend.DECLINING -> Triple(Icons.Filled.TrendingDown, Color(0xFFC62828), "Declining")
        ProductivityTrend.STABLE -> Triple(
            Icons.Filled.TrendingFlat,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Stable"
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun ProductivityRangeSelector(
    selected: ProductivityRange,
    onSelected: (ProductivityRange) -> Unit,
    accent: Color
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ProductivityRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelected(range) },
                label = { Text(range.label, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
private fun ProductivityAreaChart(
    scores: List<DailyScore>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (scores.size < 2) return@Canvas

        val maxY = 100f
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val stepX = w / (scores.size - 1)

        val linePath = Path()
        scores.forEachIndexed { i, s ->
            val x = pad + i * stepX
            val y = pad + h - ((s.score.toFloat()) / maxY * h)
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(pad + (scores.size - 1) * stepX, pad + h)
            lineTo(pad, pad + h)
            close()
        }

        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.40f), accent.copy(alpha = 0f))
            )
        )
        drawPath(
            path = linePath,
            color = accent,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        val pointR = 2.5.dp.toPx()
        scores.forEachIndexed { i, s ->
            val x = pad + i * stepX
            val y = pad + h - ((s.score.toFloat()) / maxY * h)
            drawCircle(color = accent, radius = pointR, center = Offset(x, y))
        }
    }
}

@Composable
private fun BestWorstRow(best: BestWorstDay?, worst: BestWorstDay?) {
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        best?.let {
            BestWorstTile(
                modifier = Modifier.weight(1f),
                label = "Highest",
                date = it.date.format(fmt),
                score = it.score.roundToInt(),
                tint = Color(0xFF2E7D32)
            )
        }
        worst?.let {
            BestWorstTile(
                modifier = Modifier.weight(1f),
                label = "Lowest",
                date = it.date.format(fmt),
                score = it.score.roundToInt(),
                tint = Color(0xFFC62828)
            )
        }
    }
}

@Composable
private fun BestWorstTile(
    label: String,
    date: String,
    score: Int,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
            Text(
                text = "$date · $score",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
