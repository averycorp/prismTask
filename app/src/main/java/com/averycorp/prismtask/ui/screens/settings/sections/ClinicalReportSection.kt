package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.ui.components.settings.SectionHeader
import com.averycorp.prismtask.ui.components.settings.SettingsRowWithSubtitle

/**
 * Settings entry for the v1.4.0 V8 clinical report export.
 *
 * Tapping the row triggers the ViewModel to generate and save a PDF
 * health report to the user's Downloads folder. The date range defaults
 * to "last 30 days"; a future richer dialog can expose custom ranges
 * and per-section toggles.
 */
@Composable
fun ClinicalReportSection(
    isExporting: Boolean,
    onExportReport: () -> Unit
) {
    SectionHeader("Health Report")
    SettingsRowWithSubtitle(
        title = if (isExporting) "Exporting Report..." else "Export Health Report",
        subtitle = "PDF of your last 30 days of health + wellness data",
        onClick = { if (!isExporting) onExportReport() }
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Saved to Downloads. This is self-reported — share with a provider if you'd like to.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    HorizontalDivider()
}
