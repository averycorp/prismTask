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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction

class TimerWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(120.dp, 120.dp)
        private val LARGE = DpSize(200.dp, 120.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val state = try {
            TimerStateDataStore.read(context)
        } catch (_: Exception) {
            null
        }
        provideContent {
            val size = LocalSize.current
            if (state != null) {
                TimerWidgetContent(context, state, size, palette)
            } else {
                WidgetLoadingState(palette)
            }
        }
    }
}

@Composable
private fun TimerWidgetContent(
    context: Context,
    state: TimerWidgetState,
    size: DpSize,
    palette: WidgetThemePalette
) {
    val isLarge = size.width >= 200.dp
    val isActive = state.isRunning || state.isPaused
    val isWork = state.sessionType == "work"
    val accentColor = if (isWork) palette.timerWork else palette.timerBreak
    val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTimer.wireId)
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(palette.widgetCornerRadius)
            .background(palette.surfaceBackground)
            .padding(12.dp)
            .clickable(actionStartActivity(launchIntent)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isActive) {
            Text(
                text = WidgetTextStyles.headerLabel(palette, "⏱️ Timer"),
                style = WidgetTextStyles.headerThemed(palette, palette.onSurface)
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = "Ready to Focus",
                style = WidgetTextStyles.caption(palette.onSurfaceVariant)
            )
            Spacer(modifier = GlanceModifier.height(10.dp))
            val timerIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenTimer.wireId)
            }
            Box(
                modifier = GlanceModifier
                    .cornerRadius(20.dp)
                    .background(palette.primary)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable(actionStartActivity(timerIntent))
            ) {
                Text(
                    text = "▶ Start",
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.onPrimary)
                )
            }
        } else {
            val minutes = state.remainingSeconds / 60
            val seconds = state.remainingSeconds % 60
            val timeText = "%d:%02d".format(minutes, seconds)
            val progress = if (state.totalSeconds > 0) 1f - (state.remainingSeconds.toFloat() / state.totalSeconds) else 0f

            // Mirror the in-app TimerScreen label split: Pomodoro mode shows
            // "Focus Session N of M" / "Long Break" / "Short Break"; plain
            // mode collapses to the generic "Break Time" the widget used to
            // always show. See WIDGET_TAB_PARITY_AUDIT.md item 1.4.
            val sessionLabel = when {
                isWork && state.pomodoroEnabled ->
                    "Session ${state.currentSession} of ${state.totalSessions}"
                isWork -> "Focus"
                state.pomodoroEnabled && state.isLongBreak -> "Long Break"
                state.pomodoroEnabled -> "Short Break"
                else -> "Break Time"
            }
            Text(
                text = sessionLabel,
                style = WidgetTextStyles.badgeBold(accentColor)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))

            Text(
                text = timeText,
                style = if (isLarge) {
                    WidgetTextStyles.timerLargeThemed(
                        palette,
                        if (state.isPaused) palette.onSurfaceVariant else palette.onSurface
                    )
                } else {
                    WidgetTextStyles.timerSmallThemed(
                        palette,
                        if (state.isPaused) palette.onSurfaceVariant else palette.onSurface
                    )
                }
            )

            if (isLarge && state.currentTaskTitle != null) {
                Text(
                    text = state.currentTaskTitle,
                    style = WidgetTextStyles.badge(palette.onSurfaceVariant),
                    maxLines = 1
                )
            }

            if (isLarge && isWork && state.currentSession < state.totalSessions) {
                val isLongBreak = state.currentSession % 4 == 0 && state.currentSession > 0
                val breakDuration = if (isLongBreak) "15 min break next" else "5 min break next"
                Text(
                    text = breakDuration,
                    style = WidgetTextStyles.badge(palette.timerBreak)
                )
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(if (isLarge) 8.dp else 3.dp),
                color = accentColor,
                backgroundColor = palette.surfaceVariant
            )

            if (isLarge && state.pomodoroEnabled && state.totalSessions > 0) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                PomodoroSessionDots(
                    completed = (state.currentSession - if (isWork) 1 else 0)
                        .coerceAtLeast(0),
                    total = state.totalSessions,
                    activeColor = accentColor,
                    mutedColor = palette.surfaceVariant
                )
            }
            Spacer(modifier = GlanceModifier.height(6.dp))

            // Pause/Resume + Stop. Routed through
            // TimerControlFromWidgetAction → PomodoroTimerService so the
            // running foreground service is the single source of truth.
            // TimerViewModel observes the service's broadcasts and refreshes
            // the widget DataStore, so the button state flips on the next
            // tick.
            TimerControlRow(
                isPaused = state.isPaused,
                accentColor = accentColor,
                palette = palette
            )
        }
    }
}

@Composable
private fun TimerControlRow(
    isPaused: Boolean,
    accentColor: androidx.glance.unit.ColorProvider,
    palette: WidgetThemePalette
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TimerControlButton(
            label = if (isPaused) "▶ Resume" else "❚❚ Pause",
            background = accentColor,
            foreground = palette.onPrimary,
            params = timerControlParams(
                if (isPaused) {
                    WidgetActionKeys.TIMER_CONTROL_RESUME
                } else {
                    WidgetActionKeys.TIMER_CONTROL_PAUSE
                }
            )
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        TimerControlButton(
            label = "■ Stop",
            background = palette.surfaceVariant,
            foreground = palette.onSurfaceVariant,
            params = timerControlParams(WidgetActionKeys.TIMER_CONTROL_STOP)
        )
    }
}

@Composable
private fun TimerControlButton(
    label: String,
    background: androidx.glance.unit.ColorProvider,
    foreground: androidx.glance.unit.ColorProvider,
    params: androidx.glance.action.ActionParameters
) {
    Box(
        modifier = GlanceModifier
            .cornerRadius(16.dp)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clickable(
                actionRunCallback<TimerControlFromWidgetAction>(
                    parameters = params
                )
            )
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = foreground
            )
        )
    }
}

@Composable
private fun PomodoroSessionDots(
    completed: Int,
    total: Int,
    activeColor: androidx.glance.unit.ColorProvider,
    mutedColor: androidx.glance.unit.ColorProvider
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            if (i > 0) Spacer(modifier = GlanceModifier.width(4.dp))
            Box(
                modifier = GlanceModifier
                    .size(6.dp)
                    .cornerRadius(3.dp)
                    .background(if (i < completed) activeColor else mutedColor)
            ) {}
        }
    }
}

class TimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimerWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
