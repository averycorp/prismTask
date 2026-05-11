package com.averycorp.prismtask.ui.screens.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.ui.components.settings.SettingsGroup
import com.averycorp.prismtask.ui.theme.PrismTheme
import com.averycorp.prismtask.ui.theme.ThemeViewModel

private data class WidgetThemeOption(val theme: PrismTheme?, val displayName: String, val swatch: Color?)

private val WidgetThemeOptions = listOf(
    WidgetThemeOption(null, "Follow App Theme", null),
    WidgetThemeOption(PrismTheme.CYBERPUNK, "Cyberpunk", Color(0xFF00F5FF)),
    WidgetThemeOption(PrismTheme.SYNTHWAVE, "Synthwave", Color(0xFFFF2D87)),
    WidgetThemeOption(PrismTheme.MATRIX, "Matrix", Color(0xFF00FF41)),
    WidgetThemeOption(PrismTheme.VOID, "Void", Color(0xFFC8B8FF))
)

/**
 * Settings section that lets the user pin home-screen widgets to a
 * different [PrismTheme] than the in-app theme — useful when the launcher
 * already runs a dark wallpaper but the in-app pick is lighter, or when
 * the user wants a louder widget aesthetic.
 *
 * Selecting "Follow App Theme" clears the override; any other choice
 * pins widgets to that theme. The change persists immediately and
 * widgets repaint on next refresh (or on next data event).
 */
@Composable
fun WidgetThemeSection(
    viewModel: ThemeViewModel = hiltViewModel()
) {
    val override by viewModel.widgetThemeOverride.collectAsStateWithLifecycle()

    SettingsGroup(label = "Widget Theme") {
        Text(
            text = "Pin home-screen widgets to a different theme than the app, or let them follow your app selection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        WidgetThemeOptions.forEach { option ->
            WidgetThemeRow(
                option = option,
                isSelected = option.theme == override,
                onSelect = { viewModel.setWidgetThemeOverride(option.theme) }
            )
        }
    }
}

@Composable
private fun WidgetThemeRow(
    option: WidgetThemeOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(option.swatch ?: MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = option.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
