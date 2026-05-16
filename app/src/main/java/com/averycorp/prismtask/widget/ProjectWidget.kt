package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Home-screen widget for a single user-picked project.
 *
 * Content layout:
 * - Header: project theme-color stripe (full-height leading edge) + icon +
 *   project name (1 line, ellipsized) + streak flame badge.
 * - Milestone progress bar keyed to milestone completion, with an "X/Y
 *   milestones" caption underneath when the project has milestones.
 * - Upcoming milestone title, or the project's open task list when the
 *   project has no open milestones.
 * - On MEDIUM/LARGE sizes, the body renders up to N tappable task rows
 *   (priority dot + title). Each row deep-links to that task's editor via
 *   [WidgetLaunchAction.OpenTask].
 * - Footer: task count + "N days idle" badge (only when > 3 days).
 *
 * Empty states:
 * - **No project picked** ([ProjectWidgetConfigActivity] not yet completed,
 *   or the user removed the widget's selected project after placement):
 *   renders [WidgetEmptyState] with a "Tap to configure" affordance.
 * - **Project has no open tasks**: renders an "All Caught Up" line.
 *
 * The project's per-instance theme accent (a hex color stored on the
 * project) drives the stripe + progress bar fill. Surrounding chrome
 * (surface, on-surface, error) is themed by the user's selected
 * [com.averycorp.prismtask.ui.theme.PrismTheme] via [WidgetThemePalette].
 */
class ProjectWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(150.dp, 100.dp)
        private val MEDIUM = DpSize(250.dp, 150.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    /**
     * Local Hilt entry point so [ProjectWidget] can pull project task rows
     * without extending the shared [WidgetDataProvider] contract. Mirrors
     * the `WidgetDatabaseEntryPoint` pattern used inside
     * [WidgetDataProvider] for the same reason: Glance widgets run outside
     * the app process and cannot use field injection.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProjectWidgetEntryPoint {
        fun database(): PrismTaskDatabase
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val (data, taskRows) = runCatching {
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
            val config = WidgetConfigDataStore.snapshotProjectConfig(context, appWidgetId)
            val projectId = config.projectId
            if (projectId == null) {
                null to emptyList<ProjectTaskRow>()
            } else {
                val snapshot = WidgetDataProvider.getProjectData(context, projectId)
                val rows = if (snapshot != null) {
                    fetchProjectTaskRows(context, projectId)
                } else {
                    emptyList()
                }
                snapshot to rows
            }
        }.getOrDefault(null to emptyList())

        provideContent {
            val size = LocalSize.current
            ProjectWidgetContent(
                context = context,
                data = data,
                taskRows = taskRows,
                size = size,
                palette = palette
            )
        }
    }

    private suspend fun fetchProjectTaskRows(
        context: Context,
        projectId: Long
    ): List<ProjectTaskRow> = runCatching {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProjectWidgetEntryPoint::class.java
        )
        val db = ep.database()
        db.taskDao().getTasksByProjectOnce(projectId)
            .asSequence()
            .filter { !it.isCompleted && it.parentTaskId == null && it.archivedAt == null }
            .sortedWith(compareBy({ it.dueDate ?: Long.MAX_VALUE }, { -it.priority }))
            .take(MAX_TASK_ROWS)
            .map { ProjectTaskRow(id = it.id, title = it.title, priority = it.priority) }
            .toList()
    }.getOrDefault(emptyList())
}

/** Lightweight projection of an open project task row for the widget body. */
internal data class ProjectTaskRow(
    val id: Long,
    val title: String,
    val priority: Int
)

/**
 * Upper bound on the number of project task rows materialised per render.
 * The widget shows fewer based on the current size bucket.
 */
private const val MAX_TASK_ROWS = 6

@Composable
private fun ProjectWidgetContent(
    context: Context,
    data: ProjectWidgetData?,
    taskRows: List<ProjectTaskRow>,
    size: DpSize,
    palette: WidgetThemePalette
) {
    val openApp = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val isSmall = size.width < 250.dp
    val isLarge = size.width >= 350.dp

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(palette.widgetCornerRadius)
            .background(palette.surfaceBackground)
            .clickable(actionStartActivity(openApp))
    ) {
        val stripeColor = parseStripeColor(data?.themeColorHex, palette)
        Box(
            modifier = GlanceModifier
                .width(5.dp)
                .fillMaxHeight()
                .background(stripeColor)
        ) { }

        if (data == null) {
            // Either the user hasn't completed the config Activity, or the
            // previously-picked project was deleted / archived. Either way:
            // surface a uniform empty state.
            WidgetEmptyState(
                emoji = "📂",
                message = "Tap To Configure",
                palette = palette
            )
            return@Row
        }

        ProjectBody(
            context = context,
            data = data,
            taskRows = taskRows,
            palette = palette,
            stripeColor = stripeColor,
            isSmall = isSmall,
            isLarge = isLarge
        )
    }
}

@Composable
private fun ProjectBody(
    context: Context,
    data: ProjectWidgetData,
    taskRows: List<ProjectTaskRow>,
    palette: WidgetThemePalette,
    stripeColor: ColorProvider,
    isSmall: Boolean,
    isLarge: Boolean
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(if (isSmall) 8.dp else 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth()
        ) {
            Text(text = data.icon, style = TextStyle(fontSize = 16.sp))
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = WidgetTextStyles.headerLabel(palette, data.name),
                style = WidgetTextStyles.headerThemed(palette, palette.onSurface),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
            if (data.streak > 0) {
                Text(
                    text = "🔥 ${data.streak}",
                    style = WidgetTextStyles.captionMedium(palette.streakFire)
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        ProgressTrack(progress = data.milestoneProgress, accent = stripeColor, palette = palette)

        if (data.totalMilestones > 0) {
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = "${data.completedMilestones}/${data.totalMilestones} milestones",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant),
                maxLines = 1
            )
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        val upcomingMilestone = data.upcomingMilestoneTitle
        if (upcomingMilestone != null) {
            // Project has open milestones — surface the next one as the
            // headline. Task list still renders below on MEDIUM/LARGE.
            Text(
                text = "Next: $upcomingMilestone",
                style = WidgetTextStyles.caption(palette.onSurfaceVariant),
                maxLines = 1
            )
        }

        if (isSmall) {
            // SMALL bucket has no room for a task list; fall back to a
            // single headline-task line when no upcoming milestone exists.
            if (upcomingMilestone == null) {
                val headlineTask = taskRows.firstOrNull()
                if (headlineTask != null) {
                    SmallTaskHeadline(
                        context = context,
                        row = headlineTask,
                        palette = palette
                    )
                } else if (data.nextDueTaskTitle != null) {
                    Text(
                        text = "Task: ${data.nextDueTaskTitle}",
                        style = WidgetTextStyles.caption(palette.onSurfaceVariant),
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = "All Caught Up",
                        style = WidgetTextStyles.caption(palette.onSurfaceVariant),
                        maxLines = 1
                    )
                }
            }
        } else {
            // MEDIUM / LARGE: render up to N tappable task rows. Each row
            // deep-links to the task editor via OpenTask.
            val maxRows = if (isLarge) 4 else 2
            val visible = taskRows.take(maxRows)
            if (visible.isEmpty()) {
                Text(
                    text = "All Caught Up",
                    style = WidgetTextStyles.caption(palette.onSurfaceVariant),
                    maxLines = 1
                )
            } else {
                visible.forEach { row ->
                    Spacer(modifier = GlanceModifier.height(3.dp))
                    TaskRow(context = context, row = row, palette = palette)
                }
                if (taskRows.size > maxRows) {
                    Spacer(modifier = GlanceModifier.height(3.dp))
                    Text(
                        text = "+${taskRows.size - maxRows} more",
                        style = WidgetTextStyles.badge(palette.onSurfaceVariant),
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth()
        ) {
            val done = (data.totalTasks - data.openTasks).coerceAtLeast(0)
            Text(
                text = if (data.totalTasks > 0) "$done/${data.totalTasks}" else "No Tasks",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant)
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            val daysSince = data.daysSinceActivity
            if (daysSince != null && daysSince > 3) {
                Text(
                    text = "$daysSince d idle",
                    style = WidgetTextStyles.badgeBold(palette.error)
                )
            }
        }
    }
}

/**
 * Single-line tappable task row. Mirrors [CalendarWidget]'s `TaskTimelineRow`
 * shape (priority dot + title) but without the time column since project
 * tasks are filtered by completion / archive state, not by today's slot.
 */
@Composable
private fun TaskRow(
    context: Context,
    row: ProjectTaskRow,
    palette: WidgetThemePalette
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(openTaskIntent(context, row.id))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .cornerRadius(3.dp)
                .background(priorityColorFor(row.priority, palette))
        ) { }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = row.title,
            style = WidgetTextStyles.caption(palette.onSurface),
            maxLines = 1
        )
    }
}

/** Small-bucket headline task: caption-only, but still tappable. */
@Composable
private fun SmallTaskHeadline(
    context: Context,
    row: ProjectTaskRow,
    palette: WidgetThemePalette
) {
    Text(
        text = "Task: ${row.title}",
        style = WidgetTextStyles.caption(palette.onSurfaceVariant),
        maxLines = 1,
        modifier = GlanceModifier.clickable(actionStartActivity(openTaskIntent(context, row.id)))
    )
}

/**
 * Build the deep-link intent for opening a specific task in the editor.
 * Shared by every tap target in the body (full rows on MEDIUM/LARGE,
 * single headline on SMALL) so the wire-id contract stays in one place.
 */
private fun openTaskIntent(context: Context, taskId: Long): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTask.WIRE_ID)
        putExtra(MainActivity.EXTRA_TASK_ID, taskId)
    }

@Composable
private fun ProgressTrack(progress: Float, accent: ColorProvider, palette: WidgetThemePalette) {
    val safe = progress.coerceIn(0f, 1f)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(6.dp)
            .cornerRadius(3.dp)
            .background(palette.surfaceVariant)
    ) {
        if (safe > 0f) {
            Box(
                modifier = GlanceModifier
                    .fillMaxHeight()
                    .width((200 * safe).dp.coerceAtLeast(2.dp))
                    .cornerRadius(3.dp)
                    .background(accent)
            ) { }
        }
    }
}

/**
 * Parse a project's stored hex (or future theme-token shim) into a
 * [ColorProvider] for the Glance stripe. Falls back to the user's
 * selected PrismTheme primary so the stripe still feels themed when
 * the project lacks a custom color.
 */
internal fun parseStripeColor(hex: String?, palette: WidgetThemePalette): ColorProvider {
    val parsed = hex
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
        }
    return if (parsed != null) ColorProvider(parsed) else palette.primary
}

class ProjectWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProjectWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
