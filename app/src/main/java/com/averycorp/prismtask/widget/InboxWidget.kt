package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction

/**
 * Inbox widget — recently captured items waiting to be triaged.
 *
 * Companion to [QuickAddWidget]: capture there, triage here. Items are
 * the user's incomplete root tasks with no project + no due date, ordered
 * by created_at DESC. Each row shows a priority chip whose color comes
 * from the active [WidgetThemePalette]'s priority tokens.
 */
class InboxWidget : GlanceAppWidget() {
    companion object {
        private val MEDIUM = DpSize(250.dp, 170.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
        private val LARGE_WIDE = DpSize(450.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(MEDIUM, LARGE, LARGE_WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = WidgetConfigDataStore.snapshotInboxConfig(context, appWidgetId)
        val data = try {
            WidgetDataProvider.getInboxData(context, limit = config.maxItems.coerceAtMost(8))
        } catch (_: Exception) {
            InboxWidgetData(items = emptyList())
        }
        provideContent {
            InboxContent(context, LocalSize.current, palette, config, data)
        }
    }
}

private data class InboxRowVm(val text: String, val age: String, val priorityChipColor: ColorProvider, val priorityLabel: String)

@Composable
private fun InboxContent(
    context: Context,
    size: DpSize,
    palette: WidgetThemePalette,
    config: WidgetConfigDataStore.InboxConfig,
    data: InboxWidgetData
) {
    val isWide = size.width >= 450.dp
    val isMed = size.width < 350.dp

    val rows = data.items.map { item ->
        InboxRowVm(
            text = item.title,
            age = item.ageLabel,
            priorityChipColor = priorityColorFor(item.priority, palette),
            priorityLabel = priorityLabelFor(item.priority)
        )
    }
    val sizeTierCap = if (isMed) 3 else 5
    val visible = rows.take(minOf(config.maxItems, sizeTierCap))

    val openInbox = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenInbox.wireId)
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(palette.widgetCornerRadius)
            .background(palette.surfaceBackground)
            .padding(12.dp)
            .clickable(actionStartActivity(openInbox))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = WidgetTextStyles.headerLabel(palette, "Inbox"),
                style = WidgetTextStyles.headerThemed(palette, palette.onSurface),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "${data.items.size} to triage",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
        }
        Text(
            text = "tap to triage",
            style = WidgetTextStyles.badge(palette.onSurfaceVariant)
        )
        Spacer(modifier = GlanceModifier.height(8.dp))

        if (visible.isEmpty()) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = "Inbox zero",
                style = WidgetTextStyles.captionMedium(palette.onSurfaceVariant)
            )
        } else {
            visible.forEach { item ->
                InboxRow(item, palette, wide = isWide)
                Spacer(modifier = GlanceModifier.height(if (isWide) 6.dp else 5.dp))
            }
        }
    }
}

private fun priorityLabelFor(priority: Int): String = when (priority) {
    4 -> "Urgent"
    3 -> "High"
    2 -> "Medium"
    1 -> "Low"
    else -> "None"
}

@Composable
private fun InboxRow(item: InboxRowVm, palette: WidgetThemePalette, wide: Boolean) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(6.dp)
            .background(palette.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .cornerRadius(3.dp)
                .background(item.priorityChipColor)
        ) {}
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = item.text,
            style = WidgetTextStyles.caption(palette.onSurface),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = item.age,
            style = WidgetTextStyles.badge(palette.onSurfaceVariant)
        )
        if (wide) {
            Spacer(modifier = GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .cornerRadius(9.dp)
                    .background(palette.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = GlanceModifier
                            .size(5.dp)
                            .cornerRadius(3.dp)
                            .background(item.priorityChipColor)
                    ) {}
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        text = item.priorityLabel,
                        style = WidgetTextStyles.badgeBold(item.priorityChipColor)
                    )
                }
            }
        }
    }
}

class InboxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = InboxWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
