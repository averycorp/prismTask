package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.CompletionCountMode
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.ui.components.CircularCheckbox
import com.averycorp.prismtask.ui.components.settings.AdvancedToggle
import com.averycorp.prismtask.ui.components.settings.ReorderableRow
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.screens.settings.sectionLabels

@Composable
fun DashboardSection(
    progressStyle: String,
    showProgressPercentage: Boolean,
    ringAsCompletionArc: Boolean,
    completionCountMode: CompletionCountMode,
    sectionOrder: List<String>,
    hiddenSections: Set<String>,
    onProgressStyleChange: (String) -> Unit,
    onShowProgressPercentageChange: (Boolean) -> Unit,
    onRingAsCompletionArcChange: (Boolean) -> Unit,
    onCompletionCountModeChange: (CompletionCountMode) -> Unit,
    onHiddenSectionsChange: (Set<String>) -> Unit,
    onSectionOrderChange: (List<String>) -> Unit,
    onResetDashboardDefaults: () -> Unit
) {
    var showDashboardAdvanced by remember { mutableStateOf(false) }

    SectionHeader("Dashboard")

    Text(
        text = "Progress Style",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ring" to "Ring", "bar" to "Bar", "percentage" to "Percentage").forEach { (value, label) ->
            FilterChip(
                selected = progressStyle == value,
                onClick = { onProgressStyleChange(value) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onShowProgressPercentageChange(!showProgressPercentage) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Show Percentage",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Display a percent-complete label next to the progress ring.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = showProgressPercentage,
            onCheckedChange = onShowProgressPercentageChange
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRingAsCompletionArcChange(!ringAsCompletionArc) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Show Ring As Completion Arc",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Draw the Today ring as a partial arc reflecting completion instead of a full circle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = ringAsCompletionArc,
            onCheckedChange = onRingAsCompletionArcChange
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Today Counter Includes",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = "What contributes to the \"done\" number at the top of Today.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    val countModeOptions = listOf(
        CompletionCountMode.TASKS_ONLY to "Tasks Only",
        CompletionCountMode.TASKS_AND_HABITS to "Tasks + Habits",
        CompletionCountMode.TASKS_HABITS_AND_SELFCARE to "Tasks + Habits + Self-Care"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        countModeOptions.forEach { (mode, label) ->
            FilterChip(
                selected = completionCountMode == mode,
                onClick = { onCompletionCountModeChange(mode) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Visible Sections",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    DashboardPreferences.DEFAULT_ORDER.forEach { key ->
        val label = sectionLabels[key] ?: key
        val isHidden = key in hiddenSections
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val newHidden = if (isHidden) hiddenSections - key else hiddenSections + key
                    onHiddenSectionsChange(newHidden)
                }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularCheckbox(checked = !isHidden, onCheckedChange = {
                val newHidden = if (isHidden) hiddenSections - key else hiddenSections + key
                onHiddenSectionsChange(newHidden)
            })
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }

    AdvancedToggle(expanded = showDashboardAdvanced, onToggle = { showDashboardAdvanced = !showDashboardAdvanced })
    AnimatedVisibility(visible = showDashboardAdvanced) {
        Column {
            Text(
                text = "Section Order",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            sectionOrder.forEachIndexed { index, key ->
                ReorderableRow(
                    label = sectionLabels[key] ?: key,
                    canMoveUp = index > 0,
                    canMoveDown = index < sectionOrder.size - 1,
                    onMoveUp = {
                        val mutable = sectionOrder.toMutableList()
                        mutable[index] = mutable[index - 1].also { mutable[index - 1] = mutable[index] }
                        onSectionOrderChange(mutable)
                    },
                    onMoveDown = {
                        val mutable = sectionOrder.toMutableList()
                        mutable[index] = mutable[index + 1].also { mutable[index + 1] = mutable[index] }
                        onSectionOrderChange(mutable)
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onResetDashboardDefaults) {
                Text("Reset Dashboard", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    HorizontalDivider()
}
