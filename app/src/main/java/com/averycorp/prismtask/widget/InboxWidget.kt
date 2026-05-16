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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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
 *
 * Per-row triage: tap the title/age to deep-link into the task detail
 * screen via [WidgetLaunchAction.OpenTask]; tap the leading checkbox to
 * complete in place via [ToggleTaskFromWidgetAction]. Refresh fan-out is
 * already wired in [WidgetActions].
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
        val quietMode = WidgetDataProvider.getQuietMode(context)
        provideContent {
            InboxContent(context, LocalSize.current, palette, config, data, quietMode)
        }
    }
}

private data class InboxRowVm(
    val id: Long,
    val text: String,
    val age: String,
    val priorityChipColor: ColorProvider,
    val priorityLabel: String
)

@Composable
private fun InboxContent(
    context: Context,
    size: DpSize,
    palette: WidgetThemePalette,
    config: WidgetConfigDataStore.InboxConfig,
    data: InboxWidgetData,
    quietMode: Boolean
) {
    val isWide = size.width >= 450.dp
    val isLarge = size.width >= 350.dp
    val isMed = size.width < 350.dp

    val rows = data.items.map { item ->
        InboxRowVm(
            id = item.id,
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

    WidgetScaffold(
        palette = palette,
        isLarge = isLarge,
        title = "Inbox",
        subtitle = "Tap to triage",
        outerAction = actionStartActivity(openInbox),
        headerTrailing = {
            Text(
                text = "${data.items.size} to triage",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
        }
    ) {
        Spacer(modifier = GlanceModifier.height(8.dp))

        if (visible.isEmpty()) {
            WidgetEmptyState(
                emoji = "🎉",
                message = "Inbox Zero",
                palette = palette,
                quietMode = quietMode
            )
        } else {
            visible.forEach { item ->
                InboxRow(context, item, palette, wide = isWide)
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
private fun InboxRow(
    context: Context,
    item: InboxRowVm,
    palette: WidgetThemePalette,
    wide: Boolean
) {
    val openTask = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTask.WIRE_ID)
        putExtra(MainActivity.EXTRA_TASK_ID, item.id)
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(6.dp)
            .background(palette.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Triage action — tap to mark complete. Mirrors the TodayWidget
        // checkbox so the row's leading slot is a tap target distinct
        // from the title (which deep-links to the task detail screen).
        Box(
            modifier = GlanceModifier
                .size(18.dp)
                .cornerRadius(4.dp)
                .background(palette.surfaceBackground)
                .clickable(
                    actionRunCallback<ToggleTaskFromWidgetAction>(parameters = taskIdParams(item.id))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "○",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = palette.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
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
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(openTask))
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = item.age,
            style = WidgetTextStyles.badge(palette.onSurfaceVariant),
            modifier = GlanceModifier.clickable(actionStartActivity(openTask))
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
