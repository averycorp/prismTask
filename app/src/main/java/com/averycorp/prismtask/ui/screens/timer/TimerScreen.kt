package com.averycorp.prismtask.ui.screens.timer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.ui.components.settings.DurationPickerDialog
import com.averycorp.prismtask.ui.theme.ChipShape
import com.averycorp.prismtask.ui.theme.GlowLevel
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import com.averycorp.prismtask.ui.theme.LocalPrismTheme
import com.averycorp.prismtask.ui.theme.PrismThemeAttrs
import com.averycorp.prismtask.ui.theme.drawCyberpunkTimerTicks
import com.averycorp.prismtask.ui.theme.gridFloor
import com.averycorp.prismtask.ui.theme.prismGlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    navController: NavController,
    suggestedDurationMinutes: Int? = null,
    viewModel: TimerViewModel = hiltViewModel()
) {
    // D13 B.4: chat-driven deep link can pre-load an AI-suggested duration.
    // Applied once per nav-entry; the override is in-flight only and never
    // writes through to TimerPreferences so the user's persisted custom
    // duration stays intact.
    androidx.compose.runtime.LaunchedEffect(suggestedDurationMinutes) {
        if (suggestedDurationMinutes != null) {
            viewModel.applySuggestedDurationMinutes(suggestedDurationMinutes)
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalPrismColors.current
    val prismTheme = LocalPrismTheme.current
    val displayFont = LocalPrismFonts.current.display
    val attrs = LocalPrismAttrs.current

    val topBarLabel = when {
        attrs.terminal -> "◉ pomodoro.sh"
        attrs.brackets -> "// FOCUS.CORE"
        attrs.sunset -> "◆ POMODORO"
        else -> "Focus Timer"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = topBarLabel,
                        fontFamily = displayFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = attrs.displayTracking.sp,
                        color = if (attrs.terminal || attrs.brackets || attrs.sunset) {
                            colors.muted
                        } else {
                            colors.onBackground
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground
                )
            )
        },
        containerColor = colors.background
    ) { padding ->
        TimerContent(
            padding = padding,
            uiState = uiState,
            onToggleStartPause = viewModel::toggleStartPause,
            onReset = viewModel::reset,
            onSetMode = viewModel::setMode,
            onSkipToNext = viewModel::skipToNext,
            onResetPomodoro = viewModel::resetPomodoro,
            onTogglePomodoroEnabled = viewModel::togglePomodoroEnabled,
            onToggleAutoStartBreaks = viewModel::toggleAutoStartBreaks,
            onToggleAutoStartWork = viewModel::toggleAutoStartWork,
            onSetCustomDurationMinutes = viewModel::setCustomDurationMinutes
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerContent(
    padding: PaddingValues,
    uiState: TimerUiState,
    onToggleStartPause: () -> Unit,
    onReset: () -> Unit,
    onSetMode: (TimerMode) -> Unit,
    onSkipToNext: () -> Unit,
    onResetPomodoro: () -> Unit,
    onTogglePomodoroEnabled: () -> Unit,
    onToggleAutoStartBreaks: () -> Unit,
    onToggleAutoStartWork: () -> Unit,
    onSetCustomDurationMinutes: (Int) -> Unit
) {
    val colors = LocalPrismColors.current
    val prismTheme = LocalPrismTheme.current
    val displayFont = LocalPrismFonts.current.display
    val bodyFont = LocalPrismFonts.current.body
    val attrs = LocalPrismAttrs.current

    val accent = colors.primary
    val breakAccent = colors.secondary
    val activeColor = if (uiState.mode == TimerMode.WORK) accent else breakAccent

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gridFloor()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        if (!uiState.pomodoroEnabled) {
            ThemedModeSelector(uiState = uiState, onSetMode = onSetMode, attrs = attrs)
        }

        if (uiState.pomodoroEnabled) {
            PomodoroSessionIndicator(
                completedSessions = uiState.completedSessions,
                sessionsUntilLongBreak = uiState.sessionsUntilLongBreak,
                activeColor = accent
            )
        }

        if (uiState.pomodoroEnabled) {
            val label = when {
                uiState.mode == TimerMode.WORK ->
                    "Focus Session ${(uiState.completedSessions % uiState.sessionsUntilLongBreak) + 1}"
                uiState.isLongBreak -> "Long Break"
                else -> "Short Break"
            }
            Text(
                text = if (attrs.terminal) "// $label" else label,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = bodyFont,
                color = activeColor,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = attrs.displayTracking.sp
            )
        }

        val modeLabel = when {
            !uiState.pomodoroEnabled && uiState.mode == TimerMode.WORK -> "Focus"
            !uiState.pomodoroEnabled && uiState.mode == TimerMode.CUSTOM -> "Custom"
            !uiState.pomodoroEnabled -> "Break"
            uiState.mode == TimerMode.WORK -> "Focus"
            uiState.isLongBreak -> "Long Break"
            else -> "Break"
        }
        ThemedTimerRing(
            remainingSeconds = uiState.remainingSeconds,
            totalSeconds = uiState.totalSeconds,
            activeColor = activeColor,
            modeLabel = modeLabel,
            attrs = attrs
        )

        // Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconButtonShape = if (attrs.chipShape == ChipShape.PILL) CircleShape else LocalPrismShapes.current.button
            FilledIconButton(
                onClick = if (uiState.pomodoroEnabled) onResetPomodoro else onReset,
                modifier = Modifier.size(56.dp),
                shape = iconButtonShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.surfaceVariant,
                    contentColor = colors.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset"
                )
            }

            ThemedStartButton(
                isRunning = uiState.isRunning,
                activeColor = activeColor,
                attrs = attrs,
                onClick = onToggleStartPause
            )

            if (uiState.pomodoroEnabled) {
                FilledIconButton(
                    onClick = onSkipToNext,
                    modifier = Modifier.size(56.dp),
                    shape = iconButtonShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.surfaceVariant,
                        contentColor = colors.onSurface
                    )
                ) {
                    Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Skip To Next")
                }
            }
        }

        if (uiState.pomodoroEnabled && uiState.completedSessions > 0) {
            Text(
                text = "${uiState.completedSessions} ${if (uiState.completedSessions == 1) "Session" else "Sessions"} Completed",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = bodyFont,
                color = colors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = colors.border)

        PomodoroSettings(
            uiState = uiState,
            onTogglePomodoroEnabled = onTogglePomodoroEnabled,
            onToggleAutoStartBreaks = onToggleAutoStartBreaks,
            onToggleAutoStartWork = onToggleAutoStartWork,
            onSetCustomDurationMinutes = onSetCustomDurationMinutes
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ThemedTimerRing(
    remainingSeconds: Int,
    totalSeconds: Int,
    activeColor: Color,
    modeLabel: String,
    attrs: PrismThemeAttrs
) {
    val colors = LocalPrismColors.current
    val prismTheme = LocalPrismTheme.current
    val displayFont = LocalPrismFonts.current.display
    val bodyFont = LocalPrismFonts.current.body

    val progress = if (totalSeconds > 0) remainingSeconds.toFloat() / totalSeconds.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 600),
        label = "timer_progress"
    )

    val trackColor = colors.surface
    val secondaryColor = colors.secondary

    // Larger stroke for full-screen timer; thinner for Matrix terminal look
    val strokeDp = if (attrs.terminal) 6.dp else 10.dp
    val dialSize = 260.dp

    Box(
        modifier = Modifier.size(dialSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeDp.toPx()
            val diameter = size.width
            val radius = diameter / 2f - strokePx / 2f
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            val arcSize = Size(diameter - strokePx, diameter - strokePx)
            val sweepAngle = animatedProgress.coerceIn(0f, 1f) * 360f
            val cap = if (attrs.terminal || attrs.brackets) StrokeCap.Square else StrokeCap.Round

            // Cyberpunk: draw 60 outer tick marks first (behind the ring)
            if (attrs.brackets) {
                drawCyberpunkTimerTicks(
                    ringRadius = radius,
                    strokeWidth = strokePx,
                    primaryColor = activeColor
                )
            }

            // Track ring — dashed for Matrix
            val trackPathEffect = if (attrs.terminal) {
                PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f)
            } else {
                null
            }
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Square, pathEffect = trackPathEffect)
            )

            // Progress arc — Synthwave uses gradient brush
            if (sweepAngle > 0f) {
                if (attrs.sunset) {
                    drawArc(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                            colors = listOf(activeColor, secondaryColor, activeColor),
                            center = Offset(size.width / 2f, size.height / 2f)
                        ),
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = cap)
                    )
                } else {
                    drawArc(
                        color = activeColor,
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = cap)
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val modeLabelDisplay = if (attrs.terminal) "$ focus --work" else modeLabel
            Text(
                text = modeLabelDisplay,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = bodyFont,
                color = colors.onSurface,
                letterSpacing = if (attrs.editorial) 3.sp else attrs.displayTracking.sp
            )
            Text(
                text = formatTime(remainingSeconds),
                fontSize = if (attrs.editorial) 72.sp else 66.sp,
                fontWeight = if (attrs.editorial) FontWeight.Medium else FontWeight.Bold,
                fontFamily = displayFont,
                color = if (attrs.terminal) colors.primary else colors.onBackground,
                textAlign = TextAlign.Center,
                letterSpacing = attrs.displayTracking.sp
            )
            // Matrix: seconds-remaining annotation
            if (attrs.terminal) {
                Text(
                    text = "// ${remainingSeconds}s remaining",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = bodyFont,
                    color = colors.muted
                )
            }
        }
    }
}

@Composable
private fun ThemedStartButton(
    isRunning: Boolean,
    activeColor: Color,
    attrs: PrismThemeAttrs,
    onClick: () -> Unit
) {
    val colors = LocalPrismColors.current
    val displayFont = LocalPrismFonts.current.display

    val buttonShape = when {
        attrs.terminal -> RoundedCornerShape(0.dp)
        attrs.chipShape == ChipShape.SHARP -> RoundedCornerShape(attrs.radius.dp)
        else -> RoundedCornerShape(28.dp)
    }

    // Matrix uses an outline-only button; others use solid fill
    val containerColor = if (attrs.terminal) Color.Transparent else activeColor
    val contentColor = if (attrs.terminal) activeColor else colors.background

    Button(
        onClick = onClick,
        modifier = Modifier
            .height(64.dp)
            .width(160.dp)
            .prismGlow(activeColor, attrs.glow),
        shape = buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = if (attrs.terminal) androidx.compose.foundation.BorderStroke(1.dp, activeColor) else null,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (attrs.glow == GlowLevel.NONE) 4.dp else 0.dp
        )
    ) {
        if (!attrs.terminal) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isRunning) "Pause" else "Start",
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = if (attrs.terminal) {
                if (isRunning) "$ pause" else "$ start"
            } else {
                if (isRunning) "Pause" else "Start"
            },
            fontWeight = FontWeight.Bold,
            fontFamily = displayFont,
            letterSpacing = if (attrs.editorial) 2.5.sp else attrs.displayTracking.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemedModeSelector(
    uiState: TimerUiState,
    onSetMode: (TimerMode) -> Unit,
    attrs: PrismThemeAttrs
) {
    val options = listOf(
        TimerMode.WORK to "Work",
        TimerMode.BREAK to "Break",
        TimerMode.CUSTOM to "Custom"
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            val displayLabel = if (attrs.terminal) "[${label.lowercase()}]" else label
            SegmentedButton(
                selected = uiState.mode == mode,
                onClick = { onSetMode(mode) },
                shape = when {
                    attrs.terminal || attrs.chipShape == ChipShape.SHARP ->
                        SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    else ->
                        SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                }
            ) {
                Text(
                    text = displayLabel,
                    fontFamily = LocalPrismFonts.current.body,
                    letterSpacing = attrs.displayTracking.sp
                )
            }
        }
    }
}

@Composable
private fun PomodoroSessionIndicator(
    completedSessions: Int,
    sessionsUntilLongBreak: Int,
    activeColor: Color
) {
    val currentInCycle = completedSessions % sessionsUntilLongBreak
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val colors = LocalPrismColors.current
        for (i in 0 until sessionsUntilLongBreak) {
            val isCompleted = i < currentInCycle
            Surface(
                modifier = Modifier.size(12.dp),
                shape = CircleShape,
                color = if (isCompleted) activeColor else colors.surfaceVariant
            ) {}
        }
    }
}

@Composable
private fun PomodoroSettings(
    uiState: TimerUiState,
    onTogglePomodoroEnabled: () -> Unit,
    onToggleAutoStartBreaks: () -> Unit,
    onToggleAutoStartWork: () -> Unit,
    onSetCustomDurationMinutes: (Int) -> Unit
) {
    val colors = LocalPrismColors.current
    val bodyFont = LocalPrismFonts.current.body
    var showCustomDurationDialog by remember { mutableStateOf(false) }

    if (showCustomDurationDialog) {
        DurationPickerDialog(
            title = "Custom Duration",
            currentMinutes = uiState.customDurationSeconds / 60,
            onConfirm = {
                onSetCustomDurationMinutes(it)
                showCustomDurationDialog = false
            },
            onDismiss = { showCustomDurationDialog = false }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Timer Settings",
            style = MaterialTheme.typography.titleSmall,
            fontFamily = bodyFont,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )

        SettingsToggleRow(
            label = "Pomodoro Mode",
            description = "Auto-cycle: work, short break, long break",
            checked = uiState.pomodoroEnabled,
            onToggle = onTogglePomodoroEnabled
        )
        SettingsToggleRow(
            label = "Auto-Start Breaks",
            description = "Start break timer automatically after focus",
            checked = uiState.autoStartBreaks,
            onToggle = onToggleAutoStartBreaks
        )
        SettingsToggleRow(
            label = "Auto-Start Focus",
            description = "Start focus timer automatically after break",
            checked = uiState.autoStartWork,
            onToggle = onToggleAutoStartWork
        )
        SettingsClickableRow(
            label = "Custom Duration",
            description = "${uiState.customDurationSeconds / 60} min",
            onClick = { showCustomDurationDialog = true }
        )
    }
}

@Composable
private fun SettingsClickableRow(label: String, description: String, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    val bodyFont = LocalPrismFonts.current.body
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = bodyFont,
                color = colors.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = bodyFont,
                color = colors.muted
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(label: String, description: String, checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalPrismColors.current
    val bodyFont = LocalPrismFonts.current.body
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = bodyFont,
                color = colors.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = bodyFont,
                color = colors.muted
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
