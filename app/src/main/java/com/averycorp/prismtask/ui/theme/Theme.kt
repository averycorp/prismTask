package com.averycorp.prismtask.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun parseColorOrNull(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        null
    }
}

@Composable
fun PrismTaskTheme(
    prismTheme: PrismTheme = PrismTheme.VOID,
    themeMode: String = "system",
    accentColor: String = "#2563EB",
    backgroundColorOverride: String = "",
    surfaceColorOverride: String = "",
    errorColorOverride: String = "",
    fontScale: Float = 1.0f,
    priorityColors: PriorityColors = PriorityColors(),
    reduceMotion: Boolean = false,
    highContrast: Boolean = false,
    largeTouchTargets: Boolean = false,
    compactMode: Boolean = false,
    cardCornerRadius: Int = 12,
    showCardBorders: Boolean = true,
    habitBorderBrightness: Float = 0.4f,
    content: @Composable () -> Unit
) {
    // PrismThemes are always dark-surface palettes, so we always start from
    // Material's darkColorScheme() and layer the palette on top. themeMode is
    // retained for backwards compatibility with legacy callers but no longer
    // toggles between light/dark color schemes — the PrismTheme defines the
    // canvas.
    @Suppress("UNUSED_VARIABLE")
    val legacyThemeMode = themeMode

    val prismColors = prismThemeColors(prismTheme)

    val accent = try {
        Color(android.graphics.Color.parseColor(accentColor))
    } catch (_: Exception) {
        prismColors.primary
    }

    val resolvedBackground = parseColorOrNull(backgroundColorOverride) ?: prismColors.background
    val resolvedSurface = parseColorOrNull(surfaceColorOverride) ?: prismColors.surface
    val resolvedError = parseColorOrNull(errorColorOverride) ?: prismColors.urgentAccent

    // Fully map PrismThemeColors onto the Material ColorScheme so every
    // MaterialTheme.colorScheme.* lookup (FABs, buttons, chips, dialogs,
    // text links, top/bottom bars, etc.) automatically reflects the active
    // PrismTheme palette.
    val colorScheme = darkColorScheme(
        background = resolvedBackground,
        surface = resolvedSurface,
        surfaceVariant = prismColors.surfaceVariant,
        surfaceContainer = resolvedSurface,
        surfaceContainerHigh = prismColors.surfaceVariant,
        surfaceContainerHighest = prismColors.surfaceVariant,
        surfaceContainerLow = resolvedSurface,
        surfaceContainerLowest = resolvedBackground,
        surfaceTint = accent,
        primary = accent,
        onPrimary = resolvedBackground,
        primaryContainer = prismColors.surfaceVariant,
        onPrimaryContainer = accent,
        secondary = prismColors.secondary,
        onSecondary = resolvedBackground,
        secondaryContainer = prismColors.surfaceVariant,
        onSecondaryContainer = prismColors.secondary,
        tertiary = prismColors.secondary,
        onTertiary = resolvedBackground,
        tertiaryContainer = prismColors.surfaceVariant,
        onTertiaryContainer = prismColors.secondary,
        onBackground = prismColors.onBackground,
        onSurface = prismColors.onSurface,
        onSurfaceVariant = prismColors.muted,
        outline = prismColors.border,
        outlineVariant = prismColors.border,
        scrim = Color.Black,
        error = resolvedError,
        onError = resolvedBackground,
        errorContainer = prismColors.urgentSurface,
        onErrorContainer = prismColors.urgentAccent,
        inverseSurface = prismColors.onBackground,
        inverseOnSurface = resolvedBackground,
        inversePrimary = accent
    )

    val prismFonts = prismThemeFonts(prismTheme)
    val prismAttrs = prismThemeAttrs(prismTheme)
    val prismShapes = prismAttrs.toShapes()
    val prismDensity = prismAttrs.toDensity()
    val scaledTypography = remember(fontScale, prismTheme) {
        scaledTypography(prismTypography(prismFonts, prismAttrs), fontScale)
    }
    val materialShapes = Shapes(
        extraSmall = prismShapes.extraSmall,
        small = prismShapes.button,
        medium = prismShapes.card,
        large = prismShapes.large,
        extraLarge = prismShapes.large
    )

    // High-contrast mode: boost text contrast on the dark PrismTheme canvas
    // by pinning onSurface/onBackground to fully opaque white and tightening
    // the outline. Accent colors continue to read correctly.
    val effectiveScheme = if (highContrast) {
        colorScheme.copy(
            onSurface = Color.White,
            onBackground = Color.White,
            outline = Color(0xFFCCCCCC)
        )
    } else {
        colorScheme
    }

    CompositionLocalProvider(
        LocalPriorityColors provides priorityColors,
        LocalPrismTheme provides prismTheme,
        LocalPrismColors provides prismColors,
        LocalPrismFonts provides prismFonts,
        LocalPrismAttrs provides prismAttrs,
        LocalPrismShapes provides prismShapes,
        LocalPrismDensity provides prismDensity,
        com.averycorp.prismtask.ui.a11y.LocalReducedMotion provides reduceMotion,
        com.averycorp.prismtask.ui.a11y.LocalHighContrast provides highContrast,
        com.averycorp.prismtask.ui.a11y.LocalLargeTouchTargets provides largeTouchTargets,
        LocalCompactMode provides compactMode,
        LocalCardCornerRadius provides cardCornerRadius.coerceIn(0, 24).dp,
        LocalShowCardBorders provides showCardBorders,
        LocalHabitBorderBrightness provides habitBorderBrightness.coerceIn(0f, 1f)
    ) {
        val scanSpacing = when (prismTheme) {
            PrismTheme.CYBERPUNK -> 3.dp
            PrismTheme.MATRIX -> 2.dp
            else -> 3.dp
        }
        val scanOuterAlpha = when (prismTheme) {
            PrismTheme.CYBERPUNK -> 0.55f
            PrismTheme.MATRIX -> 0.70f
            else -> 0f
        }
        MaterialTheme(
            colorScheme = effectiveScheme,
            typography = scaledTypography,
            shapes = materialShapes
        ) {
            Box(
                modifier = if (prismAttrs.scanlines) {
                    Modifier.scanlines(prismColors.primary, scanSpacing, scanOuterAlpha)
                } else {
                    Modifier
                }
            ) {
                content()
            }
        }
    }
}
