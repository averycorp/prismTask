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
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction

class HabitStreakWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(150.dp, 100.dp)
        private val MEDIUM = DpSize(250.dp, 150.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val data = try {
            WidgetDataProvider.getHabitData(context)
        } catch (_: Exception) {
            null
        }
        provideContent {
            val size = LocalSize.current
            if (data != null) {
                HabitStreakContent(context, data, size, palette)
            } else {
                WidgetLoadingState(palette)
            }
        }
    }
}

@Composable
private fun HabitStreakContent(
    context: Context,
    data: HabitWidgetData,
    size: DpSize,
    palette: WidgetThemePalette
) {
    val isSmall = size.width < 250.dp
    val isLarge = size.width >= 350.dp
    val habitsIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenHabits.wireId)
    }
    val completedToday = data.habits.count { it.isCompletedToday }
    val totalHabits = data.habits.size

    WidgetScaffold(
        palette = palette,
        isLarge = isLarge,
        title = "Habits",
        headerAction = actionStartActivity(habitsIntent),
        headerTrailing = if (data.longestStreak > 0) {
            {
                Text(
                    text = "🔥 ${data.longestStreak} day${if (data.longestStreak != 1) "s" else ""}",
                    style = WidgetTextStyles.captionMedium(
                        if (data.longestStreak > 30) palette.streakGold else palette.primary
                    )
                )
            }
        } else {
            null
        }
    ) {
        if (totalHabits > 0) {
            Text(
                text = "$completedToday of $totalHabits done today",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        if (data.habits.isEmpty()) {
            WidgetEmptyState(
                emoji = "🌱",
                message = "No Habits Yet",
                palette = palette
            )
        } else if (isSmall) {
            data.habits.take(3).forEach { habit ->
                SmallHabitRow(habit, palette)
                Spacer(modifier = GlanceModifier.height(4.dp))
            }
        } else {
            val maxHabits = if (isLarge) minOf(data.habits.size, 16) else 6
            val maxRows = if (isLarge) 8 else 3
            data.habits.take(maxHabits).chunked(2).take(maxRows).forEach { pair ->
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    pair.forEachIndexed { index, habit ->
                        if (index > 0) {
                            Spacer(modifier = GlanceModifier.width(6.dp))
                        }
                        HabitCell(habit, palette = palette, showWeeklyDots = isLarge, modifier = GlanceModifier.defaultWeight())
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                    }
                }
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        Box(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(habitsIntent))) {
            Text(
                text = "View All →",
                style = WidgetTextStyles.badge(palette.primary)
            )
        }
    }
}

@Composable
private fun SmallHabitRow(habit: HabitWidgetItem, palette: WidgetThemePalette) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().clickable(
            actionRunCallback<ToggleHabitFromWidgetAction>(parameters = habitIdParams(habit.id))
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = habit.icon, style = TextStyle(fontSize = 16.sp))
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = habit.name,
            style = WidgetTextStyles.caption(palette.onSurface),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        Text(
            text = if (habit.isCompletedToday) "✅" else "○",
            style = TextStyle(
                fontSize = 14.sp,
                color = if (habit.isCompletedToday) palette.habitComplete else palette.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun HabitCell(
    habit: HabitWidgetItem,
    palette: WidgetThemePalette,
    showWeeklyDots: Boolean,
    modifier: GlanceModifier
) {
    val tint = if (habit.isCompletedToday) palette.habitCompleteBg else palette.surfaceVariant
    Box(
        modifier = modifier
            .cornerRadius(8.dp)
            .background(tint)
            .padding(8.dp)
            .clickable(actionRunCallback<ToggleHabitFromWidgetAction>(parameters = habitIdParams(habit.id)))
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = habit.icon, style = TextStyle(fontSize = 18.sp))
                Spacer(modifier = GlanceModifier.width(6.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = habit.name,
                        style = WidgetTextStyles.captionMedium(palette.onSurface),
                        maxLines = 1
                    )
                    if (habit.streak > 0) {
                        Text(
                            text = "🔥 ${habit.streak}",
                            style = TextStyle(fontSize = 9.sp, color = palette.streakFire)
                        )
                    }
                }
                Text(
                    text = if (habit.isCompletedToday) "●" else "○",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = if (habit.isCompletedToday) palette.habitComplete else palette.onSurfaceVariant
                    )
                )
            }
            if (showWeeklyDots) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                WeeklyDots(habit, palette)
            }
        }
    }
}

@Composable
private fun WeeklyDots(habit: HabitWidgetItem, palette: WidgetThemePalette) {
    Row {
        habit.last7Days.forEachIndexed { index, completed ->
            val dotColor = if (completed) palette.streakFire else palette.habitIncomplete
            Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(dotColor)) {}
            if (index < habit.last7Days.size - 1) {
                Spacer(modifier = GlanceModifier.width(2.dp))
            }
        }
    }
}

class HabitStreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitStreakWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
