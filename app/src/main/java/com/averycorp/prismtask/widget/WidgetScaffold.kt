package com.averycorp.prismtask.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text

/**
 * Standard chrome for PrismTask Glance widgets.
 *
 * Absorbs the truly-duplicated outer shell shared by the widget family:
 *
 *  - Full-bleed [Column] sized to the widget bounds.
 *  - Themed corner radius from [WidgetThemePalette.widgetCornerRadius].
 *  - Layered atmospheric drawable background from
 *    [WidgetThemePalette.surfaceBackground].
 *  - Responsive padding (12dp at large sizes, 8dp otherwise).
 *  - Header [Row] with a themed title text — uppercased per
 *    [WidgetTextStyles.headerLabel] — and an optional trailing slot.
 *
 * Per-widget body content goes in the [content] slot, which renders as a
 * [Column] scope so callers compose `Spacer` / rows / nested layouts
 * exactly as they did inline.
 *
 * Designed to be additive: callers that need a header onClick (open
 * MainActivity, deep link, etc.) pass it via [headerAction]; callers that
 * want the entire widget surface clickable pass [outerAction]; callers
 * that don't need either skip them.
 *
 * Visually equivalent to the inline chrome it replaces — same padding,
 * same corner radius, same background, same header typography. See
 * `TodayWidget`, `HabitStreakWidget`, `UpcomingWidget` for canonical
 * migrations.
 */
@Composable
fun WidgetScaffold(
    palette: WidgetThemePalette,
    isLarge: Boolean,
    title: String,
    subtitle: String? = null,
    outerAction: Action? = null,
    headerAction: Action? = null,
    headerTrailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val outerModifier = GlanceModifier
        .fillMaxSize()
        .cornerRadius(palette.widgetCornerRadius)
        .background(palette.surfaceBackground)
        .padding(if (isLarge) 12.dp else 8.dp)
        .let { if (outerAction != null) it.clickable(outerAction) else it }

    Column(modifier = outerModifier) {
        val headerRowModifier = GlanceModifier.fillMaxWidth()
            .let { if (headerAction != null) it.clickable(headerAction) else it }
        Row(
            modifier = headerRowModifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (subtitle != null) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = WidgetTextStyles.headerLabel(palette, title),
                        style = WidgetTextStyles.headerThemed(palette, palette.onSurface)
                    )
                    Text(
                        text = subtitle,
                        style = WidgetTextStyles.caption(palette.onSurfaceVariant)
                    )
                }
            } else {
                Text(
                    text = WidgetTextStyles.headerLabel(palette, title),
                    style = WidgetTextStyles.headerThemed(palette, palette.onSurface),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
            if (headerTrailing != null) {
                headerTrailing()
            }
        }
        content()
    }
}
