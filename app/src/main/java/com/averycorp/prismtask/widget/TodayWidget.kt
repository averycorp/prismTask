package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.glance.appwidget.LinearProgressIndicator
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TodayWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(150.dp, 100.dp)
        private val MEDIUM = DpSize(250.dp, 150.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val config = runCatching {
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
            WidgetConfigDataStore.snapshotTodayConfig(context, appWidgetId)
        }.getOrNull() ?: WidgetConfigDataStore.TodayConfig()
        val data = try {
            WidgetDataProvider.getTodayData(context, maxTasks = config.maxTasks)
        } catch (_: Exception) {
            null
        }
        val quietMode = WidgetDataProvider.getQuietMode(context)
        provideContent {
            val size = LocalSize.current
            if (data != null) {
                TodayWidgetContent(context, data, size, palette, config, quietMode)
            } else {
                WidgetLoadingState(palette)
            }
        }
    }
}

@Composable
private fun TodayWidgetContent(
    context: Context,
    data: TodayWidgetData,
    size: DpSize,
    palette: WidgetThemePalette,
    config: WidgetConfigDataStore.TodayConfig,
    quietMode: Boolean
) {
    val isSmall = size.width < 250.dp
    val isLarge = size.width >= 350.dp
    val total = data.totalTasks
    val completed = data.completedTasks
    val progress = if (total > 0) completed.toFloat() / total else 0f
    val todayLabel = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()) }
    val overdueCount = data.tasks.count { it.isOverdue }
    val openTodayIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenToday.wireId)
    }
    WidgetScaffold(
        palette = palette,
        isLarge = isLarge,
        title = "Today",
        subtitle = if (!isSmall) todayLabel else null,
        headerTrailing = {
            ScoreBadge(score = data.productivityScore, palette = palette, sizeDp = if (isSmall) 30 else 34)
        }
    ) {
        if (isSmall) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = "$completed/$total tasks done",
                style = WidgetTextStyles.body(palette.onSurface)
            )
            if (config.showHabitSummary && data.totalHabits > 0) {
                Text(
                    text = "${data.completedHabits}/${data.totalHabits} habits",
                    style = WidgetTextStyles.caption(palette.secondary)
                )
            }
        } else {
            Spacer(modifier = GlanceModifier.height(8.dp))
            if (config.showProgress) {
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = palette.primary,
                    backgroundColor = palette.surfaceVariant
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = if (config.showHabitSummary) {
                        "$completed of $total tasks · ${data.completedHabits}/${data.totalHabits} habits"
                    } else {
                        "$completed of $total tasks"
                    },
                    style = WidgetTextStyles.badge(palette.onSurfaceVariant)
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
            if (config.showTaskList) {
                val sizeTierCap = if (isLarge) 8 else 3
                val effective = minOf(config.maxTasks, sizeTierCap)
                val maxTasks = effective.coerceIn(1, 20)
                // Dormancy Re-Entry: Ready-to-Resume prefix (hidden when empty).
                if (data.dormantResume.isNotEmpty()) {
                    Text(
                        text = "Ready to Resume",
                        style = WidgetTextStyles.badge(palette.primary)
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    data.dormantResume.take(if (isLarge) 3 else 1).forEach { row ->
                        WidgetResumeRow(context, row, palette)
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }
                }
                if (data.tasks.isEmpty()) {
                    WidgetEmptyState(
                        emoji = "✅",
                        message = "All Caught Up!",
                        palette = palette,
                        quietMode = quietMode
                    )
                } else {
                    data.tasks.take(maxTasks).forEach { task ->
                        WidgetTaskRowView(context, task, palette = palette, showDate = isLarge)
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }
                    if (isLarge && data.tasks.size < 3) {
                        val nextTask = data.tasks.firstOrNull { !it.isOverdue && !it.isCompleted }
                        if (nextTask != null) {
                            Text(
                                text = "Next Up",
                                style = WidgetTextStyles.badge(palette.primary)
                            )
                            Spacer(modifier = GlanceModifier.height(2.dp))
                        }
                    }
                }
            }
            if (isLarge && config.showHabitSummary && data.totalHabits > 0) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    data.habitIcons.take(5).forEach { icon ->
                        Text(text = icon, style = TextStyle(fontSize = 13.sp))
                        Spacer(modifier = GlanceModifier.width(3.dp))
                    }
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        text = "${data.completedHabits}/${data.totalHabits}",
                        style = WidgetTextStyles.badge(palette.onSurfaceVariant)
                    )
                }
            }
            if (isLarge && config.showOverdueBadge && overdueCount > 0) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Box(
                    modifier = GlanceModifier
                        .cornerRadius(8.dp)
                        .background(palette.overdueBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .clickable(actionStartActivity(openTodayIntent))
                ) {
                    Text(
                        text = "⚠ $overdueCount overdue",
                        style = WidgetTextStyles.badgeBold(palette.overdue)
                    )
                }
            }
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        Box(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openTodayIntent))) {
            Text(
                text = "View All →",
                style = WidgetTextStyles.caption(palette.primary)
            )
        }
    }
}

@Composable
private fun WidgetResumeRow(
    context: Context,
    row: WidgetDormantRow,
    palette: WidgetThemePalette
) {
    // Tap → MainActivity with the ResumeTiny launch action; the nav graph
    // navigates to the Pomodoro screen, which auto-starts the 5-minute session.
    val resumeIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.ResumeTiny.WIRE_ID)
        putExtra(MainActivity.EXTRA_TASK_ID, row.id)
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(8.dp)
            .background(palette.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(actionStartActivity(resumeIntent)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.title,
                maxLines = 1,
                style = WidgetTextStyles.body(palette.onSurface)
            )
            Text(
                text = "${row.daysDormant}d dormant",
                style = WidgetTextStyles.caption(palette.onSurfaceVariant)
            )
        }
        Text(
            text = "Resume 5m →",
            style = WidgetTextStyles.badgeBold(palette.primary)
        )
    }
}

@Composable
private fun WidgetTaskRowView(
    context: Context,
    task: WidgetTaskRow,
    palette: WidgetThemePalette,
    showDate: Boolean = false
) {
    val taskIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTask.WIRE_ID)
        putExtra("task_id", task.id)
    }
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = GlanceModifier
                .size(width = 3.dp, height = 18.dp)
                .cornerRadius(2.dp)
                .background(priorityColorFor(task.priority, palette))
        ) {}
        Spacer(modifier = GlanceModifier.width(4.dp))
        Box(
            modifier = GlanceModifier
                .size(18.dp)
                .cornerRadius(4.dp)
                .background(if (task.isCompleted) palette.primary else palette.surfaceVariant)
                .clickable(actionRunCallback<ToggleTaskFromWidgetAction>(parameters = taskIdParams(task.id))),
            contentAlignment = Alignment.Center
        ) {
            if (task.isCompleted) {
                Text(
                    text = "✓",
                    style = TextStyle(fontSize = 12.sp, color = palette.onPrimary, fontWeight = FontWeight.Bold)
                )
            }
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = task.title,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = when {
                        task.isOverdue -> palette.error
                        task.isCompleted -> palette.onSurfaceVariant
                        else -> palette.onSurface
                    },
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                ),
                maxLines = 1,
                modifier = GlanceModifier.clickable(actionStartActivity(taskIntent))
            )
            if (showDate && task.dueDate != null) {
                Text(
                    text = smartDateLabel(task.dueDate, task.isOverdue),
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = if (task.isOverdue) palette.error else palette.onSurfaceVariant
                    )
                )
            }
        }
    }
}

private fun smartDateLabel(dueDate: Long, isOverdue: Boolean): String {
    val now = System.currentTimeMillis()
    if (isOverdue) {
        val daysDiff = TimeUnit.MILLISECONDS.toDays(now - dueDate)
        return when {
            daysDiff <= 0 -> "Due today"
            daysDiff == 1L -> "Overdue by 1 day"
            else -> "Overdue by $daysDiff days"
        }
    }
    return "Due ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(dueDate))}"
}

@Composable
private fun ScoreBadge(score: Int, palette: WidgetThemePalette, sizeDp: Int = 34) {
    val bgColor = when {
        score >= 80 -> palette.scoreGreenBg
        score >= 60 -> palette.scoreOrangeBg
        else -> palette.scoreRedBg
    }
    val textColor = when {
        score >= 80 -> palette.scoreGreen
        score >= 60 -> palette.scoreOrange
        else -> palette.scoreRed
    }
    // Mockup ScoreBadge: fontSize = size * 0.38 (≈ 12.92sp at 34dp, ≈ 11.4sp at 30dp).
    val fontSizeSp = (sizeDp * 0.38f).coerceAtLeast(10f)
    Box(
        modifier = GlanceModifier.size(sizeDp.dp).cornerRadius((sizeDp / 2).dp).background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = score.toString(),
            style = TextStyle(
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = palette.displayFontFamily,
                color = textColor
            )
        )
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
