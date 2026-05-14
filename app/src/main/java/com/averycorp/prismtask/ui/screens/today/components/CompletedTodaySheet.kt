package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * Bottom sheet shown when the user taps the "X done" indicator in the
 * Today screen header. Lists every task completed today plus every
 * habit checked off today, so the total matches the header count.
 *
 * Tapping a completed task uncompletes it (same affordance as the
 * Today screen's "Completed" section).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompletedTodaySheet(
    completedTasks: List<TaskEntity>,
    completedHabits: List<HabitWithStatus>,
    onUncompleteTask: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val colors = LocalPrismColors.current
    val total = completedTasks.size + completedHabits.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Completed Today",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$total",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }

            if (total == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nothing completed yet today.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (completedTasks.isNotEmpty()) {
                        item(key = "tasks_header") {
                            CompletedSectionHeader(
                                label = "Tasks",
                                count = completedTasks.size
                            )
                        }
                        items(items = completedTasks, key = { "task_${it.id}" }) { task ->
                            CompletedTaskItem(
                                task = task,
                                onUncomplete = { onUncompleteTask(task.id) }
                            )
                        }
                    }

                    if (completedHabits.isNotEmpty()) {
                        item(key = "habits_header") {
                            CompletedSectionHeader(
                                label = "Habits",
                                count = completedHabits.size
                            )
                        }
                        items(items = completedHabits, key = { "habit_${it.habit.id}" }) { hws ->
                            CompletedHabitRow(habit = hws)
                        }
                    }

                    item(key = "bottom_pad") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedSectionHeader(label: String, count: Int) {
    val colors = LocalPrismColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted
        )
    }
}

@Composable
private fun CompletedHabitRow(habit: HabitWithStatus) {
    val colors = LocalPrismColors.current
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = colors.surface.copy(alpha = 0.6f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "✅ ${habit.habit.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.weight(1f)
            )
            if (habit.currentStreak > 0) {
                Text(
                    text = "🔥 ${habit.currentStreak}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted
                )
            }
        }
    }
}
