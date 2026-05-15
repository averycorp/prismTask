package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.LocalPrismTheme
import com.averycorp.prismtask.ui.theme.PrismTheme
import com.averycorp.prismtask.ui.theme.TerminalLabel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sticky compact header bar shown in the Scaffold topBar slot.
 *
 * [trailingActions] renders after the analytics icon; callers use it to inject
 * the shared sync indicator (see [com.averycorp.prismtask.ui.components.sync.SyncIndicatorHost]).
 */
@Composable
internal fun CompactProgressHeader(
    completed: Int,
    total: Int,
    progress: Float,
    progressStyle: String = "ring",
    showProgressPercentage: Boolean = false,
    onAnalyticsClick: (() -> Unit)? = null,
    onCompletedClick: (() -> Unit)? = null,
    productivityBadge: @Composable (() -> Unit)? = null,
    trailingActions: @Composable (() -> Unit)? = null
) {
    val dateLabel = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }
    val colors = LocalPrismColors.current
    val fonts = LocalPrismFonts.current.body
    val attrs = LocalPrismAttrs.current
    val prismTheme = LocalPrismTheme.current
    val displayFont = LocalPrismFonts.current.display

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(500),
        label = "headerProgress"
    )

    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(progress >= 1f && total > 0) {
        if (progress >= 1f && total > 0) {
            celebrate = true
            delay(900)
            celebrate = false
        }
    }
    val barColor by animateColorAsState(
        targetValue = colors.primary,
        animationSpec = tween(400),
        label = "headerBarColor"
    )
    val useGradient = prismTheme == PrismTheme.CYBERPUNK || prismTheme == PrismTheme.SYNTHWAVE
    val progressBrush = remember(prismTheme, colors.primary, colors.secondary) {
        if (useGradient) {
            Brush.linearGradient(listOf(colors.primary, colors.secondary))
        } else {
            Brush.linearGradient(listOf(colors.primary, colors.primary))
        }
    }
    val barScale by animateFloatAsState(
        targetValue = if (celebrate) 1.6f else 1f,
        animationSpec = tween(350),
        label = "headerBarScale"
    )

    Surface(
        color = colors.background,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.width(120.dp)) {
                if (attrs.editorial) {
                    BasicText(
                        text = buildAnnotatedString {
                            append("Today")
                            withStyle(SpanStyle(color = colors.primary)) { append(".") }
                        },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = displayFont,
                            fontWeight = FontWeight.Medium,
                            color = colors.onBackground
                        )
                    )
                } else {
                    Text(
                        text = "Today",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = displayFont,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground
                    )
                }
                TerminalLabel(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            val completedClickModifier = if (onCompletedClick != null && completed > 0) {
                Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = "Show completed tasks"
                ) { onCompletedClick() }
            } else {
                Modifier
            }

            when (progressStyle) {
                "ring" -> {
                    val ringSize = (36f * barScale).dp
                    Box(
                        modifier = Modifier
                            .size(ringSize)
                            .then(completedClickModifier),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val stroke = 4.dp.toPx()
                            val diameter = size.width
                            val radius = diameter / 2f - stroke / 2f
                            val topLeft = Offset(stroke / 2f, stroke / 2f)
                            val arcSize = Size(diameter - stroke, diameter - stroke)

                            // Cyberpunk: tick marks outside the mini ring
                            if (attrs.brackets) {
                                val tickCount = 12
                                val outerR = radius + stroke / 2f + 2f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                for (i in 0 until tickCount) {
                                    val angle = (i.toFloat() / tickCount) * 2f * PI.toFloat()
                                    val x1 = center.x + cos(angle).toFloat() * outerR
                                    val y1 = center.y + sin(angle).toFloat() * outerR
                                    val x2 = center.x + cos(angle).toFloat() * (outerR + 3f)
                                    val y2 = center.y + sin(angle).toFloat() * (outerR + 3f)
                                    drawLine(
                                        color = colors.primary.copy(alpha = 0.4f),
                                        start = Offset(x1, y1),
                                        end = Offset(x2, y2),
                                        strokeWidth = 0.8.dp.toPx()
                                    )
                                }
                            }

                            // Always a full ring — the count inside conveys completion,
                            // not a partial arc sweep. Matrix theme keeps its dashed look.
                            val ringPathEffect = if (attrs.terminal) {
                                PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()), 0f)
                            } else {
                                null
                            }
                            if (attrs.sunset) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(colors.primary, colors.secondary, colors.primary),
                                        center = Offset(size.width / 2f, size.height / 2f)
                                    ),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                                )
                            } else {
                                drawArc(
                                    color = barColor,
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(
                                        width = stroke,
                                        cap = if (attrs.terminal || attrs.brackets) StrokeCap.Square else StrokeCap.Round,
                                        pathEffect = ringPathEffect
                                    )
                                )
                            }
                        }
                        Text(
                            text = "$completed",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = fonts,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                    }
                    if (showProgressPercentage) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val pct = ((animatedProgress.coerceIn(0f, 1f)) * 100f).toInt()
                        TerminalLabel(
                            text = "$pct%",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.primary
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                "percentage" -> {
                    Text(
                        text = "$completed done",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = displayFont,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        modifier = completedClickModifier
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                else -> {
                    if (useGradient) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((4f * barScale).dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                                    .height((4f * barScale).dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(progressBrush)
                            )
                        }
                    } else {
                        LinearProgressIndicator(
                            progress = { animatedProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height((4f * barScale).dp)
                                .clip(RoundedCornerShape(8.dp)),
                            color = barColor,
                            trackColor = colors.surface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(modifier = completedClickModifier) {
                TerminalLabel(
                    text = "$completed done",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary
                )
            }

            if (productivityBadge != null) {
                Spacer(modifier = Modifier.width(4.dp))
                productivityBadge()
            }
            if (onAnalyticsClick != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onAnalyticsClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.BarChart,
                        contentDescription = "Task Analytics",
                        modifier = Modifier.size(20.dp),
                        tint = colors.muted
                    )
                }
            }
            if (trailingActions != null) {
                Spacer(modifier = Modifier.width(4.dp))
                trailingActions()
            }
        }
    }
}
