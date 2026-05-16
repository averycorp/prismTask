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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Focus widget — single hero "next thing" card. Where TodayWidget is a
 * list, this picks one task (highest priority / soonest open) and devotes
 * the whole surface to it. Tapping the body opens the task; tapping the
 * Start button deep-links into the timer via [WidgetLaunchAction.OpenTimer]
 * so the user can begin a focus session in one tap.
 *
 * Pomodoro badge surfaces in the header whenever the user has Pomodoro
 * mode enabled in [TimerStateDataStore] so the Start affordance reads as
 * "start a pomodoro" rather than an ambiguous timer.
 */
class FocusWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(120.dp, 120.dp)
        private val LARGE = DpSize(200.dp, 120.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val data = try {
            WidgetDataProvider.getTodayData(context)
        } catch (_: Exception) {
            null
        }
        val pomodoroEnabled = try {
            TimerStateDataStore.read(context).pomodoroEnabled
        } catch (_: Exception) {
            false
        }
        val quietMode = WidgetDataProvider.getQuietMode(context)
        provideContent {
            FocusContent(context, LocalSize.current, palette, data, pomodoroEnabled, quietMode)
        }
    }
}

/**
 * Choose the headline task: highest priority among incomplete rows, with
 * earliest due date as a tiebreaker. Mirrors the in-app urgency-first sort
 * the Today screen uses for its top-of-list focus pick.
 */
internal fun pickFocusTask(rows: List<WidgetTaskRow>): WidgetTaskRow? =
    rows.asSequence()
        .filter { !it.isCompleted }
        .sortedWith(
            compareByDescending<WidgetTaskRow> { it.priority }
                .thenBy { it.dueDate ?: Long.MAX_VALUE }
        )
        .firstOrNull()

@Composable
private fun FocusContent(
    context: Context,
    size: DpSize,
    palette: WidgetThemePalette,
    data: TodayWidgetData?,
    pomodoroEnabled: Boolean,
    quietMode: Boolean
) {
    val isSmall = size.width < 200.dp
    val pick = data?.let { pickFocusTask(it.tasks) }

    val openTimer = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTimer.wireId)
    }

    if (pick == null) {
        // Empty state: open the timer so the user can pick a task in-app.
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(palette.widgetCornerRadius)
                .background(palette.surfaceBackground)
                .padding(if (isSmall) 12.dp else 14.dp)
                .clickable(actionStartActivity(openTimer))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = WidgetTextStyles.headerLabel(palette, "Focus"),
                    style = WidgetTextStyles.headerThemed(palette, palette.onSurface),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (pomodoroEnabled) PomodoroBadge(palette)
            }
            WidgetEmptyState(
                emoji = "◎",
                message = "Nothing To Focus On",
                palette = palette,
                quietMode = quietMode
            )
        }
        return
    }

    val openTask = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTask.WIRE_ID)
        putExtra(MainActivity.EXTRA_TASK_ID, pick.id)
    }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dueLabel = pick.dueDate?.let { timeFormat.format(Date(it)) }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(palette.widgetCornerRadius)
            .background(palette.surfaceBackground)
            .padding(if (isSmall) 12.dp else 14.dp)
            .clickable(actionStartActivity(openTask))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = WidgetTextStyles.headerLabel(palette, "Focus On"),
                style = WidgetTextStyles.headerThemed(palette, palette.primary),
                modifier = GlanceModifier.defaultWeight()
            )
            if (pomodoroEnabled) PomodoroBadge(palette)
        }
        Spacer(modifier = GlanceModifier.height(8.dp))

        Text(
            text = pick.title,
            style = TextStyle(
                fontSize = if (isSmall) 15.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = palette.displayFontFamily,
                color = palette.onSurface
            ),
            maxLines = if (isSmall) 2 else 3
        )

        if (!isSmall) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(6.dp)
                        .cornerRadius(3.dp)
                        .background(priorityColorFor(pick.priority, palette))
                ) {}
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = priorityLabel(pick.priority),
                    style = WidgetTextStyles.caption(palette.onSurfaceVariant)
                )
            }
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                if (dueLabel != null) {
                    Text(
                        text = if (pick.isOverdue) "Overdue · $dueLabel" else "Due $dueLabel",
                        style = WidgetTextStyles.badge(
                            if (pick.isOverdue) palette.overdue else palette.onSurfaceVariant
                        ),
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = "No Due Date",
                        style = WidgetTextStyles.badge(palette.onSurfaceVariant),
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .cornerRadius(palette.widgetCornerRadius)
                    .background(palette.primary)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable(actionStartActivity(openTimer))
            ) {
                Text(
                    text = if (isSmall) "▶" else if (pomodoroEnabled) "▶ Pomodoro" else "▶ Start",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.onPrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun PomodoroBadge(palette: WidgetThemePalette) {
    Box(
        modifier = GlanceModifier
            .cornerRadius(8.dp)
            .background(palette.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "POMODORO",
            style = WidgetTextStyles.badgeBold(palette.onPrimaryContainer)
        )
    }
}

private fun priorityLabel(priority: Int): String = when (priority) {
    4 -> "Urgent"
    3 -> "High"
    2 -> "Medium"
    1 -> "Low"
    else -> "Priority"
}

class FocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
