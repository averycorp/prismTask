package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.preferences.CelebrationIntensity
import com.averycorp.prismtask.data.preferences.GoodEnoughEscalation
import com.averycorp.prismtask.data.preferences.NdPreferences
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsToggleRow

@Composable
fun BrainModeSection(
    ndPrefs: NdPreferences,
    onAdhdModeChange: (Boolean) -> Unit,
    onCalmModeChange: (Boolean) -> Unit,
    onFocusReleaseModeChange: (Boolean) -> Unit,
    // F&R sub-setting callbacks
    onGoodEnoughTimersChange: (Boolean) -> Unit,
    onDefaultGoodEnoughMinutesChange: (Int) -> Unit,
    onGoodEnoughEscalationChange: (GoodEnoughEscalation) -> Unit,
    onAntiReworkChange: (Boolean) -> Unit,
    onSoftWarningChange: (Boolean) -> Unit,
    onCoolingOffChange: (Boolean) -> Unit,
    onCoolingOffMinutesChange: (Int) -> Unit,
    onRevisionCounterChange: (Boolean) -> Unit,
    onMaxRevisionsChange: (Int) -> Unit,
    onShipItCelebrationsChange: (Boolean) -> Unit,
    onCelebrationIntensityChange: (CelebrationIntensity) -> Unit
) {
    SectionHeader("Brain Mode")

    SettingsToggleRow(
        title = "Quick-Start Mode",
        subtitle = "Bigger nudges and quick-start prompts to help you begin",
        checked = ndPrefs.adhdModeEnabled,
        onCheckedChange = onAdhdModeChange
    )

    SettingsToggleRow(
        title = "Calm Mode",
        subtitle = "Softer animations and gentler reminders",
        checked = ndPrefs.calmModeEnabled,
        onCheckedChange = onCalmModeChange
    )

    SettingsToggleRow(
        title = "Focus & Release Mode",
        subtitle = "Helps you finish tasks and stop over-polishing",
        checked = ndPrefs.focusReleaseModeEnabled,
        onCheckedChange = onFocusReleaseModeChange
    )

    // Mode combination info chips
    val adhdAndFr = ndPrefs.adhdModeEnabled && ndPrefs.focusReleaseModeEnabled
    val calmAndFr = ndPrefs.calmModeEnabled && ndPrefs.focusReleaseModeEnabled
    val allThree = ndPrefs.adhdModeEnabled && ndPrefs.calmModeEnabled && ndPrefs.focusReleaseModeEnabled

    AnimatedVisibility(visible = allThree) {
        InfoChip("Full brain mode activated \u2014 start, sustain, and release.")
    }
    AnimatedVisibility(visible = adhdAndFr && !allThree) {
        InfoChip("Quick-Start Mode helps you begin. Focus & Release helps you finish. They work great together.")
    }
    AnimatedVisibility(visible = calmAndFr && !allThree) {
        InfoChip("Ship-it celebrations will use subtle animations in Calm Mode.")
    }

    // F&R sub-settings (expandable when F&R is enabled)
    AnimatedVisibility(visible = ndPrefs.focusReleaseModeEnabled) {
        FocusReleaseSubSettings(
            ndPrefs = ndPrefs,
            onGoodEnoughTimersChange = onGoodEnoughTimersChange,
            onDefaultGoodEnoughMinutesChange = onDefaultGoodEnoughMinutesChange,
            onGoodEnoughEscalationChange = onGoodEnoughEscalationChange,
            onAntiReworkChange = onAntiReworkChange,
            onSoftWarningChange = onSoftWarningChange,
            onCoolingOffChange = onCoolingOffChange,
            onCoolingOffMinutesChange = onCoolingOffMinutesChange,
            onRevisionCounterChange = onRevisionCounterChange,
            onMaxRevisionsChange = onMaxRevisionsChange,
            onShipItCelebrationsChange = onShipItCelebrationsChange,
            onCelebrationIntensityChange = onCelebrationIntensityChange
        )
    }

    HorizontalDivider()
}

@Composable
private fun InfoChip(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                MaterialTheme.shapes.medium
            ).padding(12.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "\uD83D\uDCA1",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}
