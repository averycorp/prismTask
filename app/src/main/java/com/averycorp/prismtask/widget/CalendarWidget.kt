package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WidgetCalendarEvent(val title: String, val startTime: Long, val endTime: Long, val isAllDay: Boolean, val calendarColor: Int?)

private sealed class TimelineItem(val sortTime: Long) {
    class Task(val row: WidgetTaskRow) : TimelineItem(row.dueDate ?: Long.MAX_VALUE)

    class Event(val event: WidgetCalendarEvent) : TimelineItem(event.startTime)
}

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
    val hasCalendarPermission = calendarEvents.isNotEmpty()
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val maxVisibleRows = if (isLarge) 6 else 5

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(palette.widgetCornerRadius)
            .background(palette.surfaceBackground)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = WidgetTextStyles.headerLabel(palette, "Today's Schedule"),
                style = WidgetTextStyles.headerThemed(palette, palette.onSurface),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "${data.today.size} tasks · ${calendarEvents.size} events",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (todayItems.isEmpty() && !hasCalendarPermission) {
            Box(
                modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(openAppIntent)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "📅", style = TextStyle(fontSize = 24.sp))
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = "Grant Calendar Access",
                        style = WidgetTextStyles.caption(palette.primary)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = "Tap to open settings",
                        style = WidgetTextStyles.badge(palette.onSurfaceVariant)
                    )
                }
            }
        } else if (todayItems.isEmpty()) {
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

private fun buildMergedTimeline(tasks: List<WidgetTaskRow>, events: List<WidgetCalendarEvent>): List<TimelineItem> = (
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
        putExtra("task_id", row.id)
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
            text = if (event.isAllDay) "All day" else timeFormat.format(Date(event.startTime)),
            style = WidgetTextStyles.badgeBold(palette.onSurfaceVariant),
            modifier = GlanceModifier.width(56.dp)
        )
        val eventColor: ColorProvider = event.calendarColor?.let { ColorProvider(Color(it)) } ?: palette.calendarEvent
        Box(modifier = GlanceModifier.size(6.dp).cornerRadius(3.dp).background(eventColor)) {}
        Spacer(modifier = GlanceModifier.width(2.dp))
        Text(text = "📅", style = TextStyle(fontSize = 8.sp))
        Spacer(modifier = GlanceModifier.width(2.dp))
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
