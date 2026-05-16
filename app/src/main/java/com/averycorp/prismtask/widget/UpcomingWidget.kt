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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction

/**
 * Upcoming widget — section-grouped task list spanning Today, Tomorrow,
 * and This Week (the day-after bucket).
 *
 * Layout follows the launcher preview: stacked rows with a left-aligned
 * palette-themed section header (TODAY / TOMORROW / THIS WEEK) followed by
 * the tasks in that bucket. An overdue banner appears above the sections
 * when there are past-due tasks, mirroring the in-app Today screen.
 *
 * Each task row:
 *  - Renders a priority stripe + checkbox + title
 *  - Checkbox tap → [ToggleTaskFromWidgetAction] (refresh fans out through
 *    [WidgetUpdateManager.updateTaskWidgets])
 *  - Title tap → [WidgetLaunchAction.OpenTask] deep link
 *
 * Adapts to two size breakpoints:
 *  - Medium (4x3): 2 tasks per section
 *  - Large (5x4+): 4 tasks per section
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
    val maxTasksPerSection = if (isLarge) 4 else 2

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
        headerAction = actionStartActivity(mainIntent),
        headerTrailing = {
            Text(
                text = "${data.totalCount} Tasks",
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
                        text = "⚠ ${data.overdue.size} Overdue",
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
            Spacer(modifier = GlanceModifier.height(8.dp))
            WidgetEmptyState(
                emoji = "📅",
                message = "Nothing Upcoming",
                palette = palette
            )
        } else {
            Spacer(modifier = GlanceModifier.height(6.dp))
            val sections = buildList {
                add("Today" to data.today)
                add("Tomorrow" to data.tomorrow)
                if (isLarge) add("This Week" to data.dayAfter)
            }.filter { (_, rows) -> rows.isNotEmpty() }

            sections.forEachIndexed { index, (label, rows) ->
                if (index > 0) Spacer(modifier = GlanceModifier.height(4.dp))
                UpcomingSection(
                    context = context,
                    label = label,
                    tasks = rows,
                    maxTasks = maxTasksPerSection,
                    palette = palette
                )
            }
        }
    }
}

@Composable
private fun UpcomingSection(
    context: Context,
    label: String,
    tasks: List<WidgetTaskRow>,
    maxTasks: Int,
    palette: WidgetThemePalette
) {
    Text(
        text = label.uppercase(),
        style = WidgetTextStyles.badgeBold(palette.primary)
    )
    Spacer(modifier = GlanceModifier.height(2.dp))
    tasks.take(maxTasks).forEach { row ->
        UpcomingTaskRow(context = context, task = row, palette = palette)
        Spacer(modifier = GlanceModifier.height(3.dp))
    }
    if (tasks.size > maxTasks) {
        Text(
            text = "+${tasks.size - maxTasks} More",
            style = WidgetTextStyles.badge(palette.onSurfaceVariant)
        )
    }
}

@Composable
private fun UpcomingTaskRow(
    context: Context,
    task: WidgetTaskRow,
    palette: WidgetThemePalette
) {
    val taskIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTask.WIRE_ID)
        putExtra(MainActivity.EXTRA_TASK_ID, task.id)
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(width = 3.dp, height = 16.dp)
                .cornerRadius(2.dp)
                .background(priorityColorFor(task.priority, palette))
        ) {}
        Spacer(modifier = GlanceModifier.width(5.dp))
        Box(
            modifier = GlanceModifier
                .size(16.dp)
                .cornerRadius(4.dp)
                .background(if (task.isCompleted) palette.primary else palette.surfaceVariant)
                .clickable(
                    actionRunCallback<ToggleTaskFromWidgetAction>(
                        parameters = taskIdParams(task.id)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (task.isCompleted) {
                Text(
                    text = "✓",
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = palette.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = task.title,
            style = TextStyle(
                fontSize = 12.sp,
                color = when {
                    task.isOverdue -> palette.error
                    task.isCompleted -> palette.onSurfaceVariant
                    else -> palette.onSurface
                },
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 1,
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(taskIntent))
        )
    }
}

class UpcomingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpcomingWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
