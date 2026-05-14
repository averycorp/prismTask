package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.ForgivenessPrefs
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsToggleRow

/**
 * Settings section for the forgiveness-first streak system (v1.4.0 V5).
 *
 * The core toggle switches between the classic "any miss breaks the streak"
 * behavior and the forgiving "N misses per rolling window" behavior. When
 * forgiveness is on, the section reveals two sliders for the grace period
 * length and the number of allowed misses inside that window.
 */
@Composable
fun ForgivenessStreakSection(
    prefs: ForgivenessPrefs,
    onPrefsChange: (ForgivenessPrefs) -> Unit
) {
    SectionHeader("Forgiveness-First Streaks")

    SettingsToggleRow(
        title = "Forgive the Occasional Miss",
        subtitle = "One missed day still counts as part of the streak",
        checked = prefs.enabled,
        onCheckedChange = { onPrefsChange(prefs.copy(enabled = it)) }
    )

    AnimatedVisibility(visible = prefs.enabled) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                text = "Grace Window: ${prefs.gracePeriodDays} Days",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Misses inside this rolling window count against the grace allowance",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = prefs.gracePeriodDays.toFloat(),
                onValueChange = {
                    onPrefsChange(prefs.copy(gracePeriodDays = it.toInt().coerceIn(1, 30)))
                },
                valueRange = 1f..30f,
                steps = 28
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Allowed Misses: ${prefs.allowedMisses}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "How many missed days the grace window tolerates",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = prefs.allowedMisses.toFloat(),
                onValueChange = {
                    onPrefsChange(prefs.copy(allowedMisses = it.toInt().coerceIn(0, 5)))
                },
                valueRange = 0f..5f,
                steps = 4
            )
        }
    }

    HorizontalDivider()
}
