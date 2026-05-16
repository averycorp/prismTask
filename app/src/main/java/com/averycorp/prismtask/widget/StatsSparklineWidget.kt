package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stats Sparkline widget — week-over-week tasks completed trend.
 *
 * Glance only supports RemoteViews-friendly primitives, so the
 * visualization is a 7-bar column chart per day with today's bar
 * highlighted via [WidgetThemePalette.primary] and the other six bars
 * tinted via [primaryContainer]. Days with zero completions render a
 * dim baseline track instead of a degenerate stub, so the chart reads
 * as "no activity that day" rather than "tiny bar".
 *
 * Header shows the total completions this week + delta vs. last week
 * (▲ green / ▼ red). When both windows have zero completions we show an
 * empty-state instead of an all-dim chart.
 *
 * Tapping the surface opens the Insights / Task Analytics screen via
 * [WidgetLaunchAction.OpenInsights], routed through `NavGraph`.
 */
class StatsSparklineWidget : GlanceAppWidget() {
    companion object {
        private val SMALL_WIDE = DpSize(200.dp, 100.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
        private val LARGE_WIDE = DpSize(450.dp, 250.dp)

        internal val EMPTY_DATA = StatsSparklineWidgetData(
            thisWeek = List(7) { 0 },
            lastWeek = List(7) { 0 },
            total = 0,
            lastTotal = 0,
            deltaPct = 0,
            up = true
        )
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_WIDE, LARGE, LARGE_WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val data = try {
            WidgetDataProvider.getStatsSparklineData(context)
        } catch (_: Exception) {
            EMPTY_DATA
        }
        provideContent {
            SparklineContent(context, LocalSize.current, palette, data)
        }
    }
}

@Composable
private fun SparklineContent(
    context: Context,
    size: DpSize,
    palette: WidgetThemePalette,
    data: StatsSparklineWidgetData
) {
    val isWide = size.width >= 450.dp
    val isSmall = size.height < 130.dp
    val isEmpty = data.total == 0 && data.lastTotal == 0

    val openInsights = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenInsights.wireId)
    }

    WidgetScaffold(
        palette = palette,
        isLarge = !isSmall,
        title = "This Week",
        outerAction = actionStartActivity(openInsights),
        headerTrailing = if (!isEmpty) {
            { DeltaBadge(data, palette) }
        } else {
            null
        }
    ) {
        if (isEmpty) {
            WidgetEmptyState(
                emoji = "📊",
                message = "No Completions Yet",
                palette = palette
            )
            return@WidgetScaffold
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = data.total.toString(),
                style = WidgetTextStyles.scoreLargeThemed(palette, palette.onSurface)
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = "Tasks · Vs ${data.lastTotal} Last Week",
                style = WidgetTextStyles.caption(palette.onSurfaceVariant)
            )
        }

        if (!isSmall) Spacer(modifier = GlanceModifier.height(8.dp))

        // Bar chart row — fills remaining vertical space.
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
        ) {
            BarChart(data.thisWeek, palette, isWide)
        }

        if (isWide) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "${roundOneDecimal(data.total / 7.0)}/Day Avg",
                    style = WidgetTextStyles.badge(palette.onSurfaceVariant)
                )
            }
        }
    }
}

@Composable
private fun DeltaBadge(data: StatsSparklineWidgetData, palette: WidgetThemePalette) {
    val arrow = if (data.up) "▲" else "▼"
    val color = if (data.up) palette.successColor else palette.scoreRed
    Text(
        text = "$arrow ${abs(data.deltaPct)}% Vs Last Week",
        style = WidgetTextStyles.captionMedium(color)
    )
}

@Composable
private fun BarChart(values: List<Int>, palette: WidgetThemePalette, isWide: Boolean) {
    // Single Mon-Sun axis. The data provider returns oldest→today (7 entries)
    // anchored to the user's start-of-day, so day-of-week alignment depends on
    // `now`; we render M…S as a stable axis label.
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    val todayIdx = values.lastIndex
    val maxBar = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val barWidth = if (isWide) 14.dp else 10.dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().fillMaxHeight(),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { i, v ->
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.Bottom
            ) {
                // Bars scale 0..40dp. A 0-task day renders a 2dp baseline
                // *track* (palette.surfaceVariant) so the day reads as
                // "no activity" rather than a degenerate stub of the active
                // accent — fixes the empty-bar issue called out in the audit.
                val (barHeight, barColor) = if (v <= 0) {
                    2.dp to palette.surfaceVariant
                } else {
                    val scaled = ((v.toFloat() / maxBar) * 40).coerceAtLeast(4f).dp
                    val color = if (i == todayIdx) palette.primary else palette.primaryContainer
                    scaled to color
                }
                Box(
                    modifier = GlanceModifier
                        .width(barWidth)
                        .height(barHeight)
                        .cornerRadius(2.dp)
                        .background(barColor)
                ) {}
                Spacer(modifier = GlanceModifier.height(3.dp))
                Text(
                    text = days[i],
                    style = WidgetTextStyles.badge(
                        if (i == todayIdx) palette.primary else palette.onSurfaceVariant
                    )
                )
            }
        }
    }
}

private fun roundOneDecimal(v: Double): Double = (v * 10).roundToInt() / 10.0

class StatsSparklineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsSparklineWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
