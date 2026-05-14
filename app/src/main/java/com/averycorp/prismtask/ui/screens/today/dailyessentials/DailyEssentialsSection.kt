package com.averycorp.prismtask.ui.screens.today.dailyessentials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.usecase.DailyEssentialsUiState
import com.averycorp.prismtask.ui.screens.today.components.CollapsibleSection
import com.averycorp.prismtask.ui.screens.today.dailyessentials.cards.HabitCard
import com.averycorp.prismtask.ui.screens.today.dailyessentials.cards.RoutineCard
import com.averycorp.prismtask.ui.screens.today.dailyessentials.cards.SchoolworkCard
import com.averycorp.prismtask.ui.theme.LocalPrismColors

data class DailyEssentialsActions(
    val onToggleRoutineStep: (routineType: String, stepId: String) -> Unit,
    val onToggleHousework: () -> Unit,
    val onToggleCourse: (courseId: Long) -> Unit,
    val onOpenAssignment: (assignmentId: Long) -> Unit,
    val onDismissHint: () -> Unit,
    val onOpenSettings: () -> Unit
)

/**
 * Collapsible section wrapper for the Daily Essentials cards.
 *
 * Card order: Morning → Housework → Schoolwork → Bedtime.
 */
@Composable
fun DailyEssentialsSection(
    state: DailyEssentialsUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actions: DailyEssentialsActions,
    modifier: Modifier = Modifier
) {
    val prismColors = LocalPrismColors.current
    val visibleCardCount = listOfNotNull(
        state.morning,
        state.housework,
        state.houseworkRoutine,
        state.schoolwork?.takeIf { it.hasContent },
        state.bedtime
    ).size

    if (state.isEmpty && state.hasSeenHint) return

    CollapsibleSection(
        emoji = "✨",
        title = "Daily Essentials",
        count = visibleCardCount,
        accentColor = prismColors.primary,
        expanded = expanded,
        onToggle = onToggleExpanded
    ) {
        if (state.isEmpty) {
            EmptyStateHint(
                onOpenSettings = actions.onOpenSettings,
                onDismiss = actions.onDismissHint,
                modifier = modifier
            )
            return@CollapsibleSection
        }

        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.morning?.let { morning ->
                RoutineCard(
                    state = morning,
                    onToggleStep = { stepId -> actions.onToggleRoutineStep("morning", stepId) }
                )
            }
            state.housework?.let { housework ->
                HabitCard(
                    state = housework,
                    contentDescriptionPrefix = "Housework",
                    onToggle = actions.onToggleHousework
                )
            }
            state.houseworkRoutine?.let { houseworkRoutine ->
                RoutineCard(
                    state = houseworkRoutine,
                    onToggleStep = { stepId -> actions.onToggleRoutineStep("housework", stepId) }
                )
            }
            state.schoolwork?.takeIf { it.hasContent }?.let { schoolwork ->
                SchoolworkCard(
                    state = schoolwork,
                    onToggleCourse = actions.onToggleCourse,
                    onOpenAssignment = actions.onOpenAssignment
                )
            }
            state.bedtime?.let { bedtime ->
                RoutineCard(
                    state = bedtime,
                    onToggleStep = { stepId -> actions.onToggleRoutineStep("bedtime", stepId) }
                )
            }
        }
    }
}

@Composable
private fun EmptyStateHint(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Set Up Your Daily Essentials",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Pick the habits and routines you want to see every morning " +
                    "— housework, schoolwork, and more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) { Text("Not Now") }
                TextButton(onClick = onOpenSettings) { Text("Set Up") }
            }
        }
    }
}
