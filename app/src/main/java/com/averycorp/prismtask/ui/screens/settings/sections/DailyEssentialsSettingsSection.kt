package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.ui.components.settings.SectionHeader

/**
 * Settings entry point for the Daily Essentials section. Only two
 * configuration points live here — the rest are deep links into the
 * dedicated management screens that already own those features
 * (Self-Care for Morning/Bedtime, Medication).
 */
@Composable
fun DailyEssentialsSettingsSection(
    habits: List<HabitEntity>,
    houseworkHabitId: Long?,
    schoolworkHabitId: Long?,
    onHouseworkHabitChange: (Long?) -> Unit,
    onSchoolworkHabitChange: (Long?) -> Unit,
    onOpenMorningRoutine: () -> Unit,
    onOpenBedtimeRoutine: () -> Unit,
    onOpenMedication: () -> Unit
) {
    SectionHeader("Daily Essentials")

    Text(
        text = "Pick which habits power the Housework and Schoolwork cards on the " +
            "Today screen's Daily Essentials section.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    HabitPicker(
        label = "Housework Habit",
        habits = habits,
        selectedId = houseworkHabitId,
        onSelect = onHouseworkHabitChange
    )
    HabitPicker(
        label = "Schoolwork Habit",
        habits = habits,
        selectedId = schoolworkHabitId,
        onSelect = onSchoolworkHabitChange
    )

    Spacer(modifier = Modifier.height(8.dp))
    InfoRow(
        title = "Morning Routine",
        body = "Morning routine steps are configured under Self-Care \u2192 Morning.",
        actionLabel = "Open Morning",
        onAction = onOpenMorningRoutine
    )
    InfoRow(
        title = "Bedtime Routine",
        body = "Bedtime routine steps are configured under Self-Care \u2192 Bedtime.",
        actionLabel = "Open Bedtime",
        onAction = onOpenBedtimeRoutine
    )
    InfoRow(
        title = "Medication",
        body = "Medications are configured under Self-Care \u2192 Medication and habit reminders.",
        actionLabel = "Open Medication",
        onAction = onOpenMedication
    )

    HorizontalDivider()
}

@Composable
private fun HabitPicker(
    label: String,
    habits: List<HabitEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = habits.firstOrNull { it.id == selectedId }
    val displayLabel = selected?.name ?: "None"

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    text = displayLabel,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                habits.forEach { habit ->
                    DropdownMenuItem(
                        text = { Text("${habit.icon} ${habit.name}") },
                        onClick = {
                            onSelect(habit.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
