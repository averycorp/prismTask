package com.averycorp.prismtask.ui.screens.mood

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material3.TextButton
import com.averycorp.prismtask.domain.usecase.CorrelationResult
import com.averycorp.prismtask.domain.usecase.CorrelationStrength
import com.averycorp.prismtask.domain.usecase.MoodCorrelationEngine
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * Mood + Energy analytics screen (v1.4.0 V7).
 *
 * Shows 30-day averages, a simple bar-style "trend" row for mood and
 * energy, and the top correlations reported by
 * [com.averycorp.prismtask.domain.usecase.MoodCorrelationEngine].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodAnalyticsScreen(
    navController: NavController,
    viewModel: MoodAnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mood & Energy", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.logs.isNotEmpty()) {
                        IconButton(onClick = {
                            val avgMood = state.logs.map { it.mood }.average()
                            val avgEnergy = state.logs.map { it.energy }.average()
                            val report = buildString {
                                appendLine("PrismTask Mood & Energy — Last 30 Days")
                                appendLine()
                                appendLine("Entries: ${state.logs.size}")
                                appendLine("Average mood: %.1f / 5".format(avgMood))
                                appendLine("Average energy: %.1f / 5".format(avgEnergy))
                                if (state.observations.size >= MoodCorrelationEngine.MIN_OBSERVATIONS) {
                                    appendLine()
                                    appendLine("Top mood correlations:")
                                    state.moodResults.take(3).forEach {
                                        appendLine("• " + it.plainEnglish())
                                    }
                                    appendLine()
                                    appendLine("Top energy correlations:")
                                    state.energyResults.take(3).forEach {
                                        appendLine("• " + it.plainEnglish())
                                    }
                                }
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    "PrismTask Mood & Energy report"
                                )
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Share report")
                            )
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share report")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.logs.isEmpty()) {
                Text(
                    "No mood or energy logs yet. Start with the Morning Check-In to begin tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            SummaryCard(
                title = "Entries (Last 30 Days)",
                value = state.logs.size.toString()
            )

            val avgMood = if (state.logs.isEmpty()) 0.0 else state.logs.map { it.mood }.average()
            val avgEnergy = if (state.logs.isEmpty()) 0.0 else state.logs.map { it.energy }.average()
            SummaryCard(title = "Average Mood", value = "%.1f / 5".format(avgMood))
            SummaryCard(title = "Average Energy", value = "%.1f / 5".format(avgEnergy))

            // 30-day trend — simple bar rows colored by value.
            if (state.averageByDay.isNotEmpty()) {
                Text("Mood Trend (30 Days)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TrendRow(
                    values = state.averageByDay.entries
                        .sortedBy { it.key }
                        .map { it.value.first },
                    baseColor = LocalPrismColors.current.dataVisualizationPalette.getOrElse(0) { LocalPrismColors.current.primary }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Energy Trend (30 Days)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TrendRow(
                    values = state.averageByDay.entries
                        .sortedBy { it.key }
                        .map { it.value.second },
                    baseColor = LocalPrismColors.current.dataVisualizationPalette.getOrElse(1) { LocalPrismColors.current.warningColor }
                )
            }

            // Top correlations.
            if (state.observations.size >= MoodCorrelationEngine.MIN_OBSERVATIONS) {
                Text(
                    "Mood Correlations",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                state.moodResults.take(3).forEach { result ->
                    CorrelationCard(result)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Energy Correlations",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                state.energyResults.take(3).forEach { result ->
                    CorrelationCard(result)
                }
            } else {
                Text(
                    "Not enough data yet — log at least 7 days before correlations are reliable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // G1 — Crisis resources footer. Surfaced here because mood
            // tracking is the most likely surface to put a user in
            // contact with hard feelings; the entry is small and quiet
            // by design — not alarmist, not load-bearing on the screen.
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    navController.navigate(PrismTaskRoute.CrisisResources.route)
                }
            ) {
                Text("If You Need Help Now →")
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TrendRow(values: List<Float>, baseColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        values.forEach { value ->
            val alpha = (value / 5f).coerceIn(0.15f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height((value * 8).dp.coerceAtLeast(8.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(baseColor.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun CorrelationCard(result: CorrelationResult) {
    val strengthColor = when (result.strength) {
        CorrelationStrength.STRONG -> MaterialTheme.colorScheme.primary
        CorrelationStrength.MODERATE -> MaterialTheme.colorScheme.tertiary
        CorrelationStrength.WEAK -> MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = strengthColor.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                result.plainEnglish(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
