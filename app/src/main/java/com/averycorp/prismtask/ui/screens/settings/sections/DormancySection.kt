package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.ui.components.settings.SectionHeader

/**
 * Settings section for the global Dormancy threshold (Dormancy Re-Entry, v1.9.x).
 *
 * A recurring task untouched longer than this appears in "Ready to Resume".
 * Per-task overrides (in the task editor) take precedence over this global value.
 */
@Composable
fun DormancySection(
    thresholdDays: Int,
    onThresholdChange: (Int) -> Unit
) {
    SectionHeader("Dormancy")

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "Ready to Resume After: $thresholdDays ${if (thresholdDays == 1) "Day" else "Days"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Tasks untouched this long appear in Ready to Resume. " +
                "Set a per-task override in any task's Schedule tab.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = thresholdDays.toFloat(),
            onValueChange = {
                onThresholdChange(
                    it.toInt().coerceIn(
                        UserPreferencesDataStore.MIN_DORMANCY_THRESHOLD_DAYS,
                        UserPreferencesDataStore.MAX_DORMANCY_THRESHOLD_DAYS
                    )
                )
            },
            valueRange = UserPreferencesDataStore.MIN_DORMANCY_THRESHOLD_DAYS.toFloat()..
                UserPreferencesDataStore.MAX_DORMANCY_THRESHOLD_DAYS.toFloat()
        )
    }

    HorizontalDivider()
}
