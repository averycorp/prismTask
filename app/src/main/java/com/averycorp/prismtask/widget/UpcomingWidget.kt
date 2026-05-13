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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction

/**
 * Upcoming widget — shows tasks for today, tomorrow, and the day after.
 *
 * Adapts to two size breakpoints:
 * - Medium (4x3): today + tomorrow columns
 * - Large (5x4+): 3-day columns with expanded task details per day
 */
class UpcomingWidget : GlanceAppWidget() {
    companion object {
        private val MEDIUM = DpSize(250.dp, 170.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val data = try {
            WidgetDataProvider.getUpcomingData(context)
        } catch (_: Exception) {
            null
        }

        provideContent {
            val size = LocalSize.current
            if (data != null) {
                UpcomingContent(context, data, size, palette)
            } else {
                WidgetLoadingState(palette)
            }
        }
    }
}

@Composable
private fun UpcomingContent(
    context: Context,
    data: UpcomingWidgetData,
    size: DpSize,
    palette: WidgetThemePalette
) {
    val isLarge = size.width >= 350.dp
    val maxTasksPerColumn = if (isLarge) 5 else 3

    val mainIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val overdueIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenToday.wireId)
    }

    WidgetScaffold(
        palette = palette,
        isLarge = isLarge,
        title = "Upcoming",
        outerAction = actionStartActivity(mainIntent),
        headerTrailing = {
            Text(
                text = "${data.totalCount} tasks",
                style = WidgetTextStyles.caption(palette.onSurfaceVariant)
            )
        }
    ) {
        if (data.overdue.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(6.dp)
                    .background(palette.overdueBg)
                    .padding(6.dp)
                    .clickable(actionStartActivity(overdueIntent))
            ) {
                Column {
                    Text(
                        text = "⚠ ${data.overdue.size} overdue",
                        style = WidgetTextStyles.badgeBold(palette.overdue)
                    )
                    data.overdue.take(2).forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = GlanceModifier
                                    .size(4.dp)
                                    .cornerRadius(2.dp)
                                    .background(priorityColorFor(row.priority, palette))
                            ) {}
                            Spacer(modifier = GlanceModifier.width(3.dp))
                            Text(
                                text = row.title,
                                style = WidgetTextStyles.badge(palette.overdue),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (data.totalCount == 0) {
            Spacer(modifier = GlanceModifier.height(16.dp))
            WidgetEmptyState(
                emoji = "📅",
                message = "Nothing Upcoming",
                palette = palette
            )
        } else {
            Spacer(modifier = GlanceModifier.height(6.dp))

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                DayColumn(
                    label = "Today",
                    tasks = data.today,
                    maxTasks = maxTasksPerColumn,
                    palette = palette,
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                DayColumn(
                    label = "Tomorrow",
                    tasks = data.tomorrow,
                    maxTasks = maxTasksPerColumn,
                    palette = palette,
                    modifier = GlanceModifier.defaultWeight()
                )
                if (isLarge) {
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    DayColumn(
                        label = "+2 Days",
                        tasks = data.dayAfter,
                        maxTasks = maxTasksPerColumn,
                        palette = palette,
                        modifier = GlanceModifier.defaultWeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    label: String,
    tasks: List<WidgetTaskRow>,
    maxTasks: Int,
    palette: WidgetThemePalette,
    modifier: GlanceModifier
) {
    Column(
        modifier = modifier
            .cornerRadius(8.dp)
            .background(palette.surfaceVariant)
            .padding(6.dp)
    ) {
        Text(
            text = label,
            style = WidgetTextStyles.badgeBold(palette.primary)
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        if (tasks.isEmpty()) {
            Text(
                text = "—",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
        } else {
            tasks.take(maxTasks).forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = GlanceModifier
                            .size(4.dp)
                            .cornerRadius(2.dp)
                            .background(priorityColorFor(row.priority, palette))
                    ) {}
                    Spacer(modifier = GlanceModifier.width(3.dp))
                    Text(
                        text = row.title,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = palette.onSurface
                        ),
                        maxLines = 1
                    )
                }
                Spacer(modifier = GlanceModifier.height(2.dp))
            }
            if (tasks.size > maxTasks) {
                Text(
                    text = "+${tasks.size - maxTasks} more",
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = palette.onSurfaceVariant
                    )
                )
            }
        }
    }
}

class UpcomingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpcomingWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
