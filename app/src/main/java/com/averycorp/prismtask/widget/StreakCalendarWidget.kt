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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.ui.theme.prismThemeColors
import com.averycorp.prismtask.util.DayBoundary
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import kotlinx.coroutines.flow.first

/**
 * Streak Calendar widget — GitHub-style heatmap of the last N weeks of
 * habit completion activity. Companion to [HabitStreakWidget]: that one
 * is per-habit; this one is the macro view.
 *
 * Cells use the active [WidgetThemePalette]'s primary at four alpha
 * intensities (0.25 / 0.50 / 0.75 / 1.00) to indicate density. Empty
 * days fall back to `habitIncomplete`.
 *
 * Each cell tap deep-links into the Habits tab with the cell's start-of-day
 * timestamp stamped onto the launch intent under [EXTRA_HABIT_DATE]. The
 * existing `NavGraph.OpenHabits` branch scrolls to the Habits tab; the date
 * extra is forward-compatible payload for a future "scroll history to this
 * day" handler — it's preserved on the intent so `getIntent().getLongExtra`
 * can read it without further widget-side changes.
 *
 * Heatmap data + longest-streak header are read from
 * [WidgetDataProvider.getStreakCalendarData] (PR #1025), refreshed via
 * [WidgetUpdateManager.updateHabitWidgets] on every habit completion.
 */
class StreakCalendarWidget : GlanceAppWidget() {
    companion object {
        private val MEDIUM = DpSize(250.dp, 170.dp)
        private val LARGE = DpSize(350.dp, 250.dp)

        /**
         * Intent extra: the user-local start-of-day timestamp (millis since
         * epoch) corresponding to the tapped heatmap cell. Consumed by the
         * Activity / NavGraph to scope a future "open habit log for this day"
         * view; ignored today by the existing `OpenHabits` routing.
         */
        const val EXTRA_HABIT_DATE: String = "com.averycorp.prismtask.HABIT_DATE"
    }

    override val sizeMode = SizeMode.Responsive(setOf(MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val weeks = 12
        val totalDays = weeks * 7
        val now = System.currentTimeMillis()
        val (dayStartHour, dayStartMinute) = readDayStart(context)
        val startOfToday = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val startOfWindow = startOfToday - (totalDays - 1L) * DayBoundary.DAY_MILLIS
        // Snapshot 12 weeks; the @Composable picks 12 vs 8 visually based on
        // current size, but always reads from the same backing data so a
        // live size change doesn't trigger a fresh DB read.
        val data = try {
            WidgetDataProvider.getStreakCalendarData(context, weeks = weeks, now = now)
        } catch (_: Exception) {
            StreakCalendarWidgetData(
                intensities = List(totalDays) { 0 },
                activeDays = 0,
                longestStreak = 0,
                weeks = weeks
            )
        }
        provideContent {
            StreakCalendarContent(
                context = context,
                size = LocalSize.current,
                palette = palette,
                data = data,
                startOfWindow = startOfWindow
            )
        }
    }

    private suspend fun readDayStart(context: Context): Pair<Int, Int> {
        return try {
            val prefs = TaskBehaviorPreferences(context.applicationContext)
            val sod = prefs.getStartOfDay().first()
            sod.hour to sod.minute
        } catch (_: Exception) {
            0 to 0
        }
    }
}

/**
 * Builds the per-cell deep-link intent: opens Habits tab and carries the
 * cell's start-of-day timestamp. Mirrors the `EXTRA_TASK_ID` pattern used
 * by other widgets — the extra is forward-compatible payload.
 */
private fun openHabitsAtDateIntent(context: Context, dateStart: Long): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenHabits.wireId)
        putExtra(StreakCalendarWidget.EXTRA_HABIT_DATE, dateStart)
    }

@Composable
private fun StreakCalendarContent(
    context: Context,
    size: DpSize,
    palette: WidgetThemePalette,
    data: StreakCalendarWidgetData,
    startOfWindow: Long
) {
    val isLarge = size.width >= 350.dp
    val weeks = if (isLarge) data.weeks else minOf(data.weeks, 8)
    val cellSize = if (isLarge) 14.dp else 11.dp
    val gap = 3.dp
    // Slice the most-recent `weeks` columns from the 12-week buffer.
    val sourceWeeks = data.weeks
    val startCol = (sourceWeeks - weeks).coerceAtLeast(0)
    val intensities = data.intensities
    val totalDays = data.activeDays
    val longestStreak = data.longestStreak
    val openHabitsIntent = openHabitsAtDateIntent(context, startOfWindow + (sourceWeeks * 7L - 1L) * DayBoundary.DAY_MILLIS)

    WidgetScaffold(
        palette = palette,
        isLarge = isLarge,
        title = "Streak Calendar",
        outerAction = actionStartActivity(openHabitsIntent),
        headerTrailing = if (longestStreak > 0) {
            {
                Text(
                    text = "🔥 $longestStreak day${if (longestStreak != 1) "s" else ""}",
                    style = WidgetTextStyles.captionMedium(palette.streakGold)
                )
            }
        } else {
            null
        }
    ) {
        if (data.activeDays == 0 && longestStreak == 0) {
            WidgetEmptyState(
                emoji = "🌱",
                message = "No Habit Activity Yet",
                palette = palette
            )
            return@WidgetScaffold
        }
        Text(
            text = "$totalDays days active · last $weeks weeks",
            style = WidgetTextStyles.badge(palette.onSurfaceVariant)
        )
        Spacer(modifier = GlanceModifier.height(8.dp))

        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            for (wi in 0 until weeks) {
                Column {
                    for (di in 0 until 7) {
                        val sourceIdx = (startCol + wi) * 7 + di
                        val v = intensities.getOrElse(sourceIdx) { 0 }
                        val cellDate = startOfWindow + sourceIdx.toLong() * DayBoundary.DAY_MILLIS
                        Box(
                            modifier = GlanceModifier
                                .size(cellSize)
                                .cornerRadius(2.dp)
                                .background(heatColor(v, palette))
                                .clickable(actionStartActivity(openHabitsAtDateIntent(context, cellDate)))
                        ) {}
                        if (di < 6) Spacer(modifier = GlanceModifier.height(gap))
                    }
                }
                if (wi < weeks - 1) Spacer(modifier = GlanceModifier.width(gap))
            }
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(text = "Less", style = WidgetTextStyles.badge(palette.onSurfaceVariant))
            Spacer(modifier = GlanceModifier.width(6.dp))
            for (v in 0..4) {
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .cornerRadius(2.dp)
                        .background(heatColor(v, palette))
                ) {}
                Spacer(modifier = GlanceModifier.width(3.dp))
            }
            Text(text = "More", style = WidgetTextStyles.badge(palette.onSurfaceVariant))
        }
    }
}

/**
 * Maps an intensity bucket (0..4) onto a themed color. Bucket 0 falls back
 * to [WidgetThemePalette.habitIncomplete]; buckets 1..4 ride on the active
 * PrismTheme primary at increasing opacity, mirroring the GitHub
 * contribution-graph density ramp.
 *
 * Visible for unit-testing the intensity scaling and the empty-cell guard.
 */
internal fun heatColor(v: Int, palette: WidgetThemePalette): ColorProvider {
    if (v == 0) return palette.habitIncomplete
    val base = prismThemeColors(palette.theme).primary
    val alpha = when (v) {
        1 -> 0.25f
        2 -> 0.50f
        3 -> 0.75f
        else -> 1.0f
    }
    return ColorProvider(base.copy(alpha = alpha))
}

class StreakCalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakCalendarWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
