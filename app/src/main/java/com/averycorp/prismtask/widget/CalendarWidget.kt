package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shape carried by [CalendarWidget] for a single calendar event row. The
 * widget renders these alongside [WidgetTaskRow]s on a merged "Today's
 * Schedule" timeline.
 *
 * The events feed isn't wired to a live provider today — see
 * [getCalendarEventsForWidget] — but the data class lives here so callers
 * (and tests) can construct rows without reaching into Glance internals.
 */
data class WidgetCalendarEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val isAllDay: Boolean,
    val calendarColor: Int?
)

/**
 * Unified row used by [CalendarWidget] to sort tasks and events into a single
 * chronological timeline. All-day events sort to the top of the day (sortTime
 * = startTime, which is midnight for those rows); tasks without a due date
 * sink to the bottom.
 */
internal sealed class TimelineItem(
    val sortTime: Long
) {
    class Task(
        val row: WidgetTaskRow
    ) : TimelineItem(row.dueDate ?: Long.MAX_VALUE)

    class Event(
        val event: WidgetCalendarEvent
    ) : TimelineItem(event.startTime)
}

/**
 * Calendar widget — merged "Today's Schedule" timeline of tasks + calendar
 * events. The widget name retains the historical "Calendar" label from the
 * original month-grid scope; the live shape is a daily list view that
 * complements [UpcomingWidget]'s multi-day columns.
 *
 * - Medium (250×170): today timeline only
 * - Large  (350×250): today + tomorrow columns
 *
 * Tapping a task row deep-links into the in-app editor via
 * [WidgetLaunchAction.OpenTask]; tapping anywhere else opens the Today
 * screen via [WidgetLaunchAction.OpenToday].
 */
class CalendarWidget : GlanceAppWidget() {
    companion object {
        private val MEDIUM = DpSize(250.dp, 170.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val upcoming = try {
            WidgetDataProvider.getUpcomingData(context)
        } catch (_: Exception) {
            null
        }
        val calendarEvents = try {
            getCalendarEventsForWidget(context)
        } catch (_: Exception) {
            emptyList()
        }
        provideContent {
            val size = LocalSize.current
            if (upcoming != null) {
                CalendarContent(context, upcoming, calendarEvents, size, palette)
            } else {
                WidgetLoadingState(palette)
            }
        }
    }
}

/**
 * Placeholder for a live calendar-events feed. The Calendar integration
 * (`CalendarSyncRepository`) lives in-app; lifting it into a widget-side
 * snapshot is a follow-up to this audit (see KNOWN GAPS in PR description).
 * Returns an empty list so the widget falls back to a task-only timeline.
 */
private fun getCalendarEventsForWidget(@Suppress("unused") context: Context): List<WidgetCalendarEvent> = emptyList()

@Composable
private fun CalendarContent(
    context: Context,
    data: UpcomingWidgetData,
    calendarEvents: List<WidgetCalendarEvent>,
    size: DpSize,
    palette: WidgetThemePalette
) {
    val isLarge = size.width >= 350.dp
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val todayItems = buildMergedTimeline(data.today, calendarEvents)
    val maxVisibleRows = if (isLarge) 6 else 5

    val openTodayIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenToday.wireId)
        // Date-pin extra for a future NavGraph consumer; ignored on read today.
        putExtra(EXTRA_DATE_EPOCH_MILLIS, System.currentTimeMillis())
    }

    WidgetScaffold(
        palette = palette,
        isLarge = isLarge,
        title = "Today's Schedule",
        outerAction = actionStartActivity(openTodayIntent),
        headerTrailing = {
            Text(
                text = "${data.today.size} tasks · ${calendarEvents.size} events",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
        }
    ) {
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (todayItems.isEmpty()) {
            WidgetEmptyState(
                emoji = "🗓",
                message = "Nothing Scheduled Today",
                palette = palette
            )
        } else if (isLarge) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "Today",
                        style = WidgetTextStyles.badgeBold(palette.primary)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    todayItems.take(maxVisibleRows).forEach { item ->
                        TimelineRow(item, timeFormat, context, palette)
                        Spacer(modifier = GlanceModifier.height(2.dp))
                    }
                    if (todayItems.size > maxVisibleRows) {
                        Text(
                            text = "+${todayItems.size - maxVisibleRows} more",
                            style = WidgetTextStyles.badge(palette.onSurfaceVariant)
                        )
                    }
                }
                Spacer(modifier = GlanceModifier.width(8.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "Tomorrow",
                        style = WidgetTextStyles.badgeBold(palette.primary)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    if (data.tomorrow.isEmpty()) {
                        Text(text = "—", style = WidgetTextStyles.badge(palette.onSurfaceVariant))
                    } else {
                        data.tomorrow.take(5).forEach { row ->
                            TaskTimelineRow(row, timeFormat, context, palette)
                            Spacer(modifier = GlanceModifier.height(2.dp))
                        }
                        if (data.tomorrow.size > 5) {
                            Text(
                                text = "+${data.tomorrow.size - 5} more",
                                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
                            )
                        }
                    }
                }
            }
        } else {
            todayItems.take(maxVisibleRows).forEach { item ->
                TimelineRow(item, timeFormat, context, palette)
                Spacer(modifier = GlanceModifier.height(2.dp))
            }
            if (todayItems.size > maxVisibleRows) {
                Text(
                    text = "+${todayItems.size - maxVisibleRows} more",
                    style = WidgetTextStyles.badge(palette.onSurfaceVariant)
                )
            }
        }
    }
}

/**
 * Merge tasks and events into a single chronological list. Stable sort
 * preserves source order for items with identical timestamps so tests can
 * make deterministic assertions on ordering.
 */
internal fun buildMergedTimeline(
    tasks: List<WidgetTaskRow>,
    events: List<WidgetCalendarEvent>
): List<TimelineItem> = (
    tasks.map { TimelineItem.Task(it) } +
        events.map { TimelineItem.Event(it) }
    ).sortedBy { it.sortTime }

@Composable
private fun TimelineRow(
    item: TimelineItem,
    timeFormat: SimpleDateFormat,
    context: Context,
    palette: WidgetThemePalette
) {
    when (item) {
        is TimelineItem.Task -> TaskTimelineRow(item.row, timeFormat, context, palette)
        is TimelineItem.Event -> EventTimelineRow(item.event, timeFormat, palette)
    }
}

@Composable
private fun TaskTimelineRow(
    row: WidgetTaskRow,
    timeFormat: SimpleDateFormat,
    context: Context,
    palette: WidgetThemePalette
) {
    val taskIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTask.WIRE_ID)
        putExtra(MainActivity.EXTRA_TASK_ID, row.id)
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(actionStartActivity(taskIntent)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.dueDate?.let { timeFormat.format(Date(it)) } ?: "--:--",
            style = WidgetTextStyles.badgeBold(palette.onSurfaceVariant),
            modifier = GlanceModifier.width(56.dp)
        )
        Box(modifier = GlanceModifier.size(6.dp).cornerRadius(3.dp).background(priorityColorFor(row.priority, palette))) {}
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = row.title,
            style = WidgetTextStyles.body(palette.onSurface),
            maxLines = 1
        )
    }
}

@Composable
private fun EventTimelineRow(
    event: WidgetCalendarEvent,
    timeFormat: SimpleDateFormat,
    palette: WidgetThemePalette
) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (event.isAllDay) "All Day" else timeFormat.format(Date(event.startTime)),
            style = WidgetTextStyles.badgeBold(palette.onSurfaceVariant),
            modifier = GlanceModifier.width(56.dp)
        )
        val eventColor: ColorProvider = event.calendarColor?.let { ColorProvider(Color(it)) } ?: palette.calendarEvent
        Box(modifier = GlanceModifier.size(6.dp).cornerRadius(3.dp).background(eventColor)) {}
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = event.title,
            style = WidgetTextStyles.body(palette.onSurface),
            maxLines = 1
        )
    }
}

class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}

/**
 * Intent extra key the widget stamps onto its outer-click intent so a
 * future Today/NavGraph wiring can pin the day to the widget's reference
 * date (currently always today on the rendering device). Lives here rather
 * than [MainActivity] because shared MainActivity is read-only in this audit.
 */
internal const val EXTRA_DATE_EPOCH_MILLIS = "com.averycorp.prismtask.WIDGET_DATE_EPOCH_MILLIS"
