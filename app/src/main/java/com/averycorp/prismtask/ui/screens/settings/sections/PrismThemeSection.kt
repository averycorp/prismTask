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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.ui.components.settings.SettingsGroup
import com.averycorp.prismtask.ui.theme.PrismTheme
import com.averycorp.prismtask.ui.theme.ThemeViewModel

/**
 * Metadata for a single selectable PrismTheme row. The swatch color is the
 * dominant accent hex from the design spec — the palette itself lives in
 * [com.averycorp.prismtask.ui.theme.prismThemeColors].
 */
private data class PrismThemeOption(val theme: PrismTheme, val displayName: String, val swatch: Color)

private val ThemeOptions = listOf(
    PrismThemeOption(PrismTheme.CYBERPUNK, "Cyberpunk", Color(0xFF00F5FF)),
    PrismThemeOption(PrismTheme.SYNTHWAVE, "Synthwave", Color(0xFFFF2D87)),
    PrismThemeOption(PrismTheme.MATRIX, "Matrix", Color(0xFF00FF41)),
    PrismThemeOption(PrismTheme.VOID, "Void", Color(0xFFC8B8FF))
)

/**
 * "Appearance" section on the main Settings screen that lets the user pick a
 * PrismTheme. Tapping a row persists the choice immediately via
 * [ThemeViewModel.setTheme] — CompositionLocals in [PrismTaskTheme] then
 * propagate the new palette and fonts through the Compose tree for a live
 * preview.
 */
@Composable
fun PrismThemeSection(
    viewModel: ThemeViewModel = hiltViewModel()
) {
    val currentTheme by viewModel.currentTheme.collectAsStateWithLifecycle()

    SettingsGroup(label = "Appearance") {
        ThemeOptions.forEach { option ->
            PrismThemeRow(
                option = option,
                isSelected = option.theme == currentTheme,
                onSelect = { viewModel.setTheme(option.theme) }
            )
        }
    }
}

@Composable
private fun PrismThemeRow(
    option: PrismThemeOption,
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
                .background(option.swatch)
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
