package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.UrgencyWeights
import com.averycorp.prismtask.domain.usecase.UrgencyLevel
import com.averycorp.prismtask.domain.usecase.UrgencyScorer
import com.averycorp.prismtask.ui.components.DurationPickerDialog
import com.averycorp.prismtask.ui.components.StartOfDayPickerDialog
import com.averycorp.prismtask.ui.components.formatStartOfDay
import com.averycorp.prismtask.ui.components.settings.AdvancedToggle
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsRowWithSubtitle
import com.averycorp.prismtask.ui.components.settings.WeightSlider
import com.averycorp.prismtask.ui.screens.settings.sortLabels
import com.averycorp.prismtask.ui.screens.settings.viewModeLabels
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TaskDefaultsSection(
    defaultSort: String,
    defaultViewMode: String,
    firstDayOfWeek: DayOfWeek,
    dayStartHour: Int,
    dayStartMinute: Int,
    urgencyWeights: UrgencyWeights,
    defaultTaskDurationMinutes: Int,
    onDefaultSortChange: (String) -> Unit,
    onDefaultViewModeChange: (String) -> Unit,
    onFirstDayOfWeekChange: (DayOfWeek) -> Unit,
    onStartOfDayChange: (hour: Int, minute: Int) -> Unit,
    onUrgencyWeightsChange: (UrgencyWeights) -> Unit,
    onDefaultTaskDurationChange: (Int) -> Unit,
    onResetTaskBehaviorDefaults: () -> Unit
) {
    var showTaskAdvanced by remember { mutableStateOf(false) }
    var showSodPicker by remember { mutableStateOf(false) }
    var showDurationPicker by remember { mutableStateOf(false) }

    SectionHeader("Global Defaults")

    SettingsRowWithSubtitle(
        title = "Default Sort",
        subtitle = sortLabels[defaultSort] ?: defaultSort,
        onClick = {
            val keys = sortLabels.keys.toList()
            val next = keys[(keys.indexOf(defaultSort) + 1) % keys.size]
            onDefaultSortChange(next)
        }
    )
    SettingsRowWithSubtitle(
        title = "Default View",
        subtitle = viewModeLabels[defaultViewMode] ?: defaultViewMode,
        onClick = {
            val keys = viewModeLabels.keys.toList()
            val next = keys[(keys.indexOf(defaultViewMode) + 1) % keys.size]
            onDefaultViewModeChange(next)
        }
    )
    SettingsRowWithSubtitle(
        title = "First Day of Week",
        subtitle = firstDayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
        onClick = {
            val days = DayOfWeek.entries
            val next = days[(firstDayOfWeek.ordinal + 1) % days.size]
            onFirstDayOfWeekChange(next)
        }
    )
    SettingsRowWithSubtitle(
        title = "Start of Day",
        subtitle = if (dayStartHour == 0 && dayStartMinute == 0) {
            "Midnight"
        } else {
            formatStartOfDay(dayStartHour, dayStartMinute)
        },
        onClick = { showSodPicker = true }
    )

    SettingsRowWithSubtitle(
        title = "Default Task Length",
        subtitle = formatDurationSubtitle(defaultTaskDurationMinutes) +
            " · Powers balance & cognitive-load weighting for tasks without an estimate.",
        onClick = { showDurationPicker = true }
    )

    if (showSodPicker) {
        StartOfDayPickerDialog(
            initialHour = dayStartHour,
            initialMinute = dayStartMinute,
            dismissable = true,
            onConfirm = { h, m ->
                showSodPicker = false
                onStartOfDayChange(h, m)
            },
            onDismiss = { showSodPicker = false }
        )
    }

    if (showDurationPicker) {
        DurationPickerDialog(
            initialMinutes = defaultTaskDurationMinutes,
            onConfirm = { m ->
                showDurationPicker = false
                onDefaultTaskDurationChange(m)
            },
            onDismiss = { showDurationPicker = false }
        )
    }

    AdvancedToggle(expanded = showTaskAdvanced, onToggle = { showTaskAdvanced = !showTaskAdvanced })
    AnimatedVisibility(visible = showTaskAdvanced) {
        Column {
            Text(
                text = "Urgency Scoring Weights",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Weights auto-normalize to sum to 100%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            var localDueDate by remember(urgencyWeights) { mutableFloatStateOf(urgencyWeights.dueDate) }
            var localPriority by remember(urgencyWeights) { mutableFloatStateOf(urgencyWeights.priority) }
            var localAge by remember(urgencyWeights) { mutableFloatStateOf(urgencyWeights.age) }
            var localSubtasks by remember(urgencyWeights) { mutableFloatStateOf(urgencyWeights.subtasks) }

            fun normalizeAndSave() {
                val total = localDueDate + localPriority + localAge + localSubtasks
                if (total > 0) {
                    val w = UrgencyWeights(
                        localDueDate / total,
                        localPriority / total,
                        localAge / total,
                        localSubtasks / total
                    )
                    localDueDate = w.dueDate
                    localPriority = w.priority
                    localAge = w.age
                    localSubtasks = w.subtasks
                    onUrgencyWeightsChange(w)
                }
            }

            WeightSlider("Due Date", localDueDate) {
                localDueDate = it
                normalizeAndSave()
            }
            WeightSlider("Priority", localPriority) {
                localPriority = it
                normalizeAndSave()
            }
            WeightSlider("Task Age", localAge) {
                localAge = it
                normalizeAndSave()
            }
            WeightSlider("Subtasks", localSubtasks) {
                localSubtasks = it
                normalizeAndSave()
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Preview",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            val previewWeights = UrgencyWeights(
                dueDate = localDueDate,
                priority = localPriority,
                age = localAge,
                subtasks = localSubtasks
            )
            UrgencyPreviewSamples(weights = previewWeights)

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = {
                onUrgencyWeightsChange(UrgencyWeights())
            }) {
                Text("Reset Urgency Weights to Defaults", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onResetTaskBehaviorDefaults) {
                Text("Reset All Global Defaults", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    HorizontalDivider()
}

private fun formatDurationSubtitle(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}

@Composable
private fun UrgencyPreviewSamples(weights: UrgencyWeights) {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    val samples = listOf(
        Triple(
            "Overdue report",
            TaskEntity(
                id = 1,
                title = "Overdue report",
                priority = 3,
                dueDate = now - day,
                createdAt = now - 3 * day
            ),
            0 to 0
        ),
        Triple(
            "New idea",
            TaskEntity(
                id = 2,
                title = "New idea",
                priority = 1,
                dueDate = now + 7 * day,
                createdAt = now
            ),
            0 to 0
        ),
        Triple(
            "Big project",
            TaskEntity(
                id = 3,
                title = "Big project",
                priority = 2,
                dueDate = now + 2 * day,
                createdAt = now - 14 * day
            ),
            5 to 2
        )
    )
    Column {
        samples.forEach { (label, task, subtasks) ->
            val score = UrgencyScorer.calculateScore(
                task = task,
                subtaskCount = subtasks.first,
                subtaskCompleted = subtasks.second,
                weights = weights
            )
            val level = UrgencyScorer.getUrgencyLevel(score)
            val indicatorColor = when (level) {
                UrgencyLevel.CRITICAL -> Color(0xFFE53935)
                UrgencyLevel.HIGH -> Color(0xFFFB8C00)
                UrgencyLevel.MEDIUM -> Color(0xFFFDD835)
                UrgencyLevel.LOW -> Color(0xFF43A047)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(indicatorColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    String.format("%.2f", score),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
