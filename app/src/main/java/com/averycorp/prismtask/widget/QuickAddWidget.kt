package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import java.util.Calendar

/**
 * Quick-Add Glance widget — a pill-shaped home-screen launcher that opens
 * the in-app NLP quick-add capture (and a voice-input shortcut) without
 * waiting for the app's cold start to draw its FAB.
 *
 * Size tiers:
 *  - **SMALL** (200×40): pill only — diamond accent, rotating placeholder,
 *    mic chip.
 *  - **LARGE** (250×100): pill plus a row of up to three top template
 *    shortcuts pulled from [WidgetDataProvider.getTopTemplates]. Empty
 *    template list renders [WidgetEmptyState] instead of collapsing.
 *
 * Deep-link routing — every clickable surface stamps the typed
 * [WidgetLaunchAction] wire id onto an Intent the activity rehydrates
 * via [MainActivity.EXTRA_LAUNCH_ACTION]:
 *  - Diamond + placeholder → [WidgetLaunchAction.QuickAdd]
 *  - Mic chip → [WidgetLaunchAction.VoiceInput]
 *  - Template tile → [WidgetLaunchAction.OpenTemplates]
 *
 * Theming: outer surface uses `palette.surfaceBackground` (atmospheric)
 * and `palette.widgetCornerRadius`; inner pill keeps a 28dp radius as a
 * deliberate design constant — pills aren't supposed to inherit the
 * card-corner language. All text styles go through [WidgetTextStyles].
 *
 * `quick_add_widget_info.xml` declares `updatePeriodMillis="0"` — refresh
 * is driven by [WidgetUpdateManager.updateAllWidgets] rather than the
 * AppWidgetManager periodic schedule.
 */
class QuickAddWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(200.dp, 40.dp)
        private val LARGE = DpSize(250.dp, 100.dp)
        internal val PLACEHOLDERS =
            listOf("What's on your mind?", "Add a task...", "What needs doing?", "Plan something great...", "Quick capture...")

        /** Stable index into [PLACEHOLDERS]; exposed for unit tests. */
        internal fun placeholderFor(dayOfYear: Int): String =
            PLACEHOLDERS[Math.floorMod(dayOfYear, PLACEHOLDERS.size)]
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val templates = try {
            WidgetDataProvider.getTopTemplates(context, limit = 3)
        } catch (_: Exception) {
            emptyList()
        }
        provideContent {
            val size = LocalSize.current
            QuickAddContent(context, templates, size, palette)
        }
    }
}

@Composable
private fun QuickAddContent(
    context: Context,
    templates: List<TemplateShortcut>,
    size: DpSize,
    palette: WidgetThemePalette
) {
    val isLarge = size.height >= 100.dp
    val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val placeholder = QuickAddWidget.placeholderFor(dayOfYear)
    val addTaskIntent = launchIntent(context, WidgetLaunchAction.QuickAdd)
    val voiceIntent = launchIntent(context, WidgetLaunchAction.VoiceInput)
    val templatesIntent = launchIntent(context, WidgetLaunchAction.OpenTemplates)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(palette.widgetCornerRadius)
            .background(palette.surfaceBackground)
            .padding(if (isLarge) 12.dp else 8.dp)
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(28.dp)
                .background(palette.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(actionStartActivity(addTaskIntent)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "◆", style = TextStyle(fontSize = 16.sp, color = palette.primary))
            Spacer(modifier = GlanceModifier.width(10.dp))
            Text(
                text = placeholder,
                style = WidgetTextStyles.body(palette.onSurfaceVariant),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .cornerRadius(18.dp)
                    .background(palette.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clickable(actionStartActivity(voiceIntent))
            ) {
                Text(text = "🎤", style = TextStyle(fontSize = 16.sp, color = palette.onPrimaryContainer))
            }
        }
        if (isLarge) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            if (templates.isEmpty()) {
                WidgetEmptyState(
                    emoji = "📋",
                    message = "No Templates Yet",
                    palette = palette
                )
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    templates.take(3).forEachIndexed { index, tpl ->
                        if (index > 0) Spacer(modifier = GlanceModifier.width(6.dp))
                        Box(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .cornerRadius(8.dp)
                                .background(palette.secondaryContainer)
                                .padding(vertical = 8.dp)
                                .clickable(actionStartActivity(templatesIntent)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = tpl.icon, style = TextStyle(fontSize = 16.sp))
                                Text(
                                    text = tpl.name.take(10),
                                    style = WidgetTextStyles.badge(palette.onSecondaryContainer),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Build an Intent that rehydrates [action] inside `MainActivity`. */
private fun launchIntent(context: Context, action: WidgetLaunchAction): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, action.wireId)
    }

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
