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
import androidx.glance.unit.ColorProvider
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import java.util.concurrent.TimeUnit

/**
 * Eisenhower Matrix widget — at-a-glance 2×2 quadrant snapshot.
 *
 * Each quadrant cell carries the count of tasks currently routed to it
 * plus the headline task in that bucket. Colors are pulled from the
 * active [WidgetThemePalette]'s `quadrantQ*` tokens, which mirror the
 * in-app Eisenhower screen.
 *
 * Tapping anywhere on the widget opens the Eisenhower screen in
 * MainActivity (via the standard launch intent).
 *
 * **LARGE-only per-quadrant complete checkbox.** When the widget is sized
 * at or above the [LARGE] bucket, each quadrant renders a small checkbox
 * next to the headline task title. Tapping it dispatches
 * [ToggleTaskFromWidgetAction] keyed on the top task's id; refresh is
 * driven by the standard `updateTaskWidgets` fan-out inside that action.
 * MEDIUM stays checkbox-free to preserve density on the 2×2 grid — the
 * deferral path documented in `docs/audits/WIDGET_TAB_PARITY_AUDIT.md`
 * § 2.2.
 */
class EisenhowerWidget : GlanceAppWidget() {
    companion object {
        private val MEDIUM = DpSize(250.dp, 150.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
        internal val LARGE_THRESHOLD = LARGE.width
    }

    override val sizeMode = SizeMode.Responsive(setOf(MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val data = try {
            WidgetDataProvider.getEisenhowerData(context)
        } catch (_: Exception) {
            EisenhowerWidgetData(
                EisenhowerQuadrantSummary(0, null),
                EisenhowerQuadrantSummary(0, null),
                EisenhowerQuadrantSummary(0, null),
                EisenhowerQuadrantSummary(0, null)
            )
        }
        provideContent {
            EisenhowerContent(context, LocalSize.current, palette, data)
        }
    }
}

private data class Quad(
    val key: String,
    val label: String,
    val count: Int,
    val color: ColorProvider,
    val bgColor: ColorProvider,
    val top: String?,
    val topId: Long?,
    val topPriority: Int?,
    val topDueDate: Long?
)

@Composable
private fun EisenhowerContent(
    context: Context,
    size: DpSize,
    palette: WidgetThemePalette,
    data: EisenhowerWidgetData
) {
    val isLarge = size.width >= EisenhowerWidget.LARGE_THRESHOLD
    val quads = listOf(
        Quad(
            "Q1", "Do", data.q1.count, palette.quadrantQ1, palette.quadrantQ1Bg,
            data.q1.topTaskTitle, data.q1.topTaskId, data.q1.topTaskPriority, data.q1.topTaskDueDate
        ),
        Quad(
            "Q2", "Schedule", data.q2.count, palette.quadrantQ2, palette.quadrantQ2Bg,
            data.q2.topTaskTitle, data.q2.topTaskId, data.q2.topTaskPriority, data.q2.topTaskDueDate
        ),
        Quad(
            "Q3", "Delegate", data.q3.count, palette.quadrantQ3, palette.quadrantQ3Bg,
            data.q3.topTaskTitle, data.q3.topTaskId, data.q3.topTaskPriority, data.q3.topTaskDueDate
        ),
        Quad(
            "Q4", "Drop", data.q4.count, palette.quadrantQ4, palette.quadrantQ4Bg,
            data.q4.topTaskTitle, data.q4.topTaskId, data.q4.topTaskPriority, data.q4.topTaskDueDate
        )
    )
    val total = data.total
    val openMatrix = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenMatrix.wireId)
    }

    WidgetScaffold(
        palette = palette,
        isLarge = isLarge,
        title = "Matrix",
        outerAction = actionStartActivity(openMatrix),
        headerTrailing = {
            Text(
                text = "$total tasks",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
        }
    ) {
        Spacer(modifier = GlanceModifier.height(6.dp))
        // 2×2 grid: two rows, two quads each.
        QuadRow(quads[0], quads[1], palette = palette, compact = !isLarge)
        Spacer(modifier = GlanceModifier.height(5.dp))
        QuadRow(quads[2], quads[3], palette = palette, compact = !isLarge)
    }
}

@Composable
private fun QuadRow(left: Quad, right: Quad, palette: WidgetThemePalette, compact: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        QuadCell(left, palette = palette, compact = compact, modifier = GlanceModifier.defaultWeight())
        Spacer(modifier = GlanceModifier.width(5.dp))
        QuadCell(right, palette = palette, compact = compact, modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun QuadCell(q: Quad, palette: WidgetThemePalette, compact: Boolean, modifier: GlanceModifier) {
    Box(
        modifier = modifier
            .cornerRadius(8.dp)
            .background(q.bgColor)
            .padding(if (compact) 6.dp else 8.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = q.key, style = WidgetTextStyles.badgeBold(q.color))
                Spacer(modifier = GlanceModifier.width(4.dp))
                Text(
                    text = q.label,
                    style = WidgetTextStyles.captionMedium(palette.onSurface),
                    modifier = GlanceModifier.defaultWeight()
                )
                Text(
                    text = q.count.toString(),
                    style = WidgetTextStyles.bodyBold(q.color)
                )
            }
            if (!compact) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                QuadTopTaskRow(q = q, palette = palette)
            }
        }
    }
}

/**
 * Bottom row of a LARGE quadrant cell. Shows (in order):
 *  - per-quadrant complete-top-task checkbox (LARGE only, hidden when the
 *    quadrant has no top task), wired to [ToggleTaskFromWidgetAction];
 *  - priority dot when both a top task and priority exist;
 *  - the top task title or an em-dash fallback;
 *  - a relative due-date label when the task has a due date.
 */
@Composable
private fun QuadTopTaskRow(q: Quad, palette: WidgetThemePalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (q.top != null && q.topId != null) {
            QuadCheckbox(topId = q.topId, palette = palette)
            Spacer(modifier = GlanceModifier.width(4.dp))
        }
        if (q.top != null && q.topPriority != null) {
            Box(
                modifier = GlanceModifier
                    .size(6.dp)
                    .cornerRadius(3.dp)
                    .background(priorityColorFor(q.topPriority, palette))
            ) {}
            Spacer(modifier = GlanceModifier.width(4.dp))
        }
        Text(
            text = q.top ?: "—",
            style = WidgetTextStyles.badge(palette.onSurfaceVariant),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        val dueLabel = q.topDueDate?.let { dueDateLabel(it) }
        if (dueLabel != null) {
            Spacer(modifier = GlanceModifier.width(4.dp))
            Text(
                text = dueLabel.text,
                style = WidgetTextStyles.badge(
                    if (dueLabel.overdue) palette.overdue else palette.onSurfaceVariant
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * Empty surface-variant box used as the LARGE-only complete-top-task
 * checkbox. The top task surfaced by `getEisenhowerData` is always
 * incomplete (completed roots are filtered out), so an unchecked box is
 * the only state we render. Tapping fires [ToggleTaskFromWidgetAction]
 * which triggers `updateTaskWidgets`; the widget redraws with the next
 * task in line.
 */
@Composable
private fun QuadCheckbox(topId: Long, palette: WidgetThemePalette) {
    Box(
        modifier = GlanceModifier
            .size(12.dp)
            .cornerRadius(3.dp)
            .background(palette.surfaceVariant)
            .clickable(
                actionRunCallback<ToggleTaskFromWidgetAction>(
                    parameters = taskIdParams(topId)
                )
            )
    ) {}
}

internal data class DueDateLabel(val text: String, val overdue: Boolean)

internal fun dueDateLabel(dueDate: Long, now: Long = System.currentTimeMillis()): DueDateLabel {
    val diffMs = dueDate - now
    val days = TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
    return when {
        days < 0 -> DueDateLabel("${-days}d ago", overdue = true)
        days == 0 -> DueDateLabel("Today", overdue = false)
        days == 1 -> DueDateLabel("Tmrw", overdue = false)
        else -> DueDateLabel("${days}d", overdue = false)
    }
}

class EisenhowerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EisenhowerWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
