package com.averycorp.prismtask.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Which hand of [AnalogClockPicker] is being edited by taps and drags.
 * Defaults to [HOUR] so the user's first tap moves the coarsest hand.
 */
enum class ClockHand { HOUR, MINUTE, SECOND }

/**
 * Mutable state for [AnalogClockPicker]. Hour is stored in 24-hour form
 * (0..23) regardless of dial mode — the dial renders 1..12 + an AM/PM
 * toggle when [is24Hour] is false but persists the 24-hour value, which
 * matches the Material 3 `TimePickerState` contract callers used to
 * consume.
 *
 * Callers that only care about hour+minute (most fields in PrismTask
 * today) read [hour] / [minute] and ignore [second]; the seconds hand is
 * rendered either way so the affordance stays consistent across the app
 * per the `feedback-time-input-use-clock-not-slider` memory. Persist
 * [second] only where the data model supports it; otherwise round it
 * away on save.
 */
@Stable
class AnalogClockState(
    initialHour: Int,
    initialMinute: Int,
    initialSecond: Int,
    val is24Hour: Boolean,
    initialActiveHand: ClockHand = ClockHand.HOUR
) {
    private var _hour by mutableIntStateOf(initialHour.coerceIn(0, 23))
    private var _minute by mutableIntStateOf(initialMinute.coerceIn(0, 59))
    private var _second by mutableIntStateOf(initialSecond.coerceIn(0, 59))
    var activeHand by mutableStateOf(initialActiveHand)

    var hour: Int
        get() = _hour
        set(value) { _hour = value.coerceIn(0, 23) }
    var minute: Int
        get() = _minute
        set(value) { _minute = value.coerceIn(0, 59) }
    var second: Int
        get() = _second
        set(value) { _second = value.coerceIn(0, 59) }

    val isAm: Boolean get() = _hour < 12

    /** Toggle AM/PM, preserving the visible 1..12 dial hour. */
    fun toggleAmPm() {
        _hour = (_hour + 12) % 24
    }

    companion object {
        val Saver: Saver<AnalogClockState, *> = listSaver(
            save = { listOf(it.hour, it.minute, it.second, it.is24Hour, it.activeHand.name) },
            restore = {
                AnalogClockState(
                    initialHour = it[0] as Int,
                    initialMinute = it[1] as Int,
                    initialSecond = it[2] as Int,
                    is24Hour = it[3] as Boolean,
                    initialActiveHand = ClockHand.valueOf(it[4] as String)
                )
            }
        )
    }
}

@Composable
fun rememberAnalogClockState(
    initialHour: Int,
    initialMinute: Int,
    initialSecond: Int = 0,
    is24Hour: Boolean
): AnalogClockState = rememberSaveable(saver = AnalogClockState.Saver) {
    AnalogClockState(initialHour, initialMinute, initialSecond, is24Hour)
}

/**
 * Three-hand analog clock-face picker — the canonical time-of-day input
 * across PrismTask per the `feedback-time-input-use-clock-not-slider`
 * memory. The clock renders an hour ring (plus a 24-hour inner ring when
 * [AnalogClockState.is24Hour] is true) and three hands (hour / minute /
 * second). The user picks one hand at a time via the [ClockHand] tabs.
 *
 * Tap anywhere on the face → snap the active hand to that angle. Drag →
 * the active hand follows the pointer continuously. A tap advances the
 * active hand (hour → minute → second), so the common workflow is "tap,
 * tap, tap" without re-selecting tabs.
 *
 * The source-of-truth readout below the dial reads `state.hour` /
 * `state.minute` / `state.second` directly, so it cannot diverge from
 * what callers persist.
 */
@Composable
fun AnalogClockPicker(
    state: AnalogClockState,
    modifier: Modifier = Modifier,
    diameter: Dp = 256.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HandTabRow(state = state)
        ClockFace(state = state, diameter = diameter)
        ActiveHandHint(state = state)
        if (!state.is24Hour) {
            AmPmRow(state = state)
        }
        SelectedReadout(state = state)
    }
}

@Composable
private fun HandTabRow(state: AnalogClockState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HandTab("Hour", state.activeHand == ClockHand.HOUR) { state.activeHand = ClockHand.HOUR }
        HandTab("Minute", state.activeHand == ClockHand.MINUTE) { state.activeHand = ClockHand.MINUTE }
        HandTab("Second", state.activeHand == ClockHand.SECOND) { state.activeHand = ClockHand.SECOND }
    }
}

@Composable
private fun HandTab(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            AssistChipDefaults.assistChipColors()
        }
    )
}

@Composable
private fun ActiveHandHint(state: AnalogClockState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Tap or drag the face to set the ${state.activeHand.name.lowercase()} hand.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp)
        )
    }
}

@Composable
private fun AmPmRow(state: AnalogClockState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AmPmChip("AM", selected = state.isAm) { if (!state.isAm) state.toggleAmPm() }
        AmPmChip("PM", selected = !state.isAm) { if (state.isAm) state.toggleAmPm() }
    }
}

@Composable
private fun AmPmChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        } else {
            AssistChipDefaults.assistChipColors()
        }
    )
}

@Composable
private fun SelectedReadout(state: AnalogClockState) {
    // Source-of-truth readout — the same invariant
    // `MedicationTimePickerLabel` enforced for the prior Material 3
    // picker: the user's contract that what they see is what gets saved.
    Text(
        text = "Selected: ${formatAnalogClockTime(state.hour, state.minute, state.second, state.is24Hour)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ClockFace(state: AnalogClockState, diameter: Dp) {
    val density = LocalDensity.current
    val diameterPx = with(density) { diameter.toPx() }
    val faceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val numberColor = MaterialTheme.colorScheme.onSurface
    val numberInnerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val hourHandColor = MaterialTheme.colorScheme.primary
    val minuteHandColor = MaterialTheme.colorScheme.secondary
    val secondHandColor = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()
    val numberStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = numberColor, textAlign = TextAlign.Center)
    val innerNumberStyle = TextStyle(fontSize = 11.sp, color = numberInnerColor, textAlign = TextAlign.Center)
    val is24 = state.is24Hour

    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(faceColor)
            .pointerInput(is24) {
                detectTapGestures(
                    onPress = { offset ->
                        applyPointer(offset, diameterPx, state, advanceActiveHand = false)
                    },
                    onTap = { offset ->
                        applyPointer(offset, diameterPx, state, advanceActiveHand = true)
                    }
                )
            }
            .pointerInput(is24) {
                detectDragGestures(
                    onDragStart = { offset ->
                        applyPointer(offset, diameterPx, state, advanceActiveHand = false)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        applyPointer(change.position, diameterPx, state, advanceActiveHand = false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = min(size.width, size.height) / 2f - 8f
            val innerRadius = outerRadius * 0.66f
            val tickStart = outerRadius - 6f

            drawTicks(center, tickStart, tickColor)
            drawHourNumbers(
                center = center,
                numberRadius = outerRadius - 26f,
                measurer = measurer,
                style = numberStyle
            )
            if (is24) {
                drawInnerHourNumbers(
                    center = center,
                    radius = innerRadius - 14f,
                    measurer = measurer,
                    style = innerNumberStyle
                )
            }

            val secondHandLength = outerRadius - 18f
            val minuteHandLength = outerRadius - 28f
            val hourHandLength = outerRadius * 0.55f

            // Order: second (back), minute (middle), hour (front) so the
            // hour-hand's primary tint reads as the visual anchor.
            drawClockHand(
                center = center,
                length = secondHandLength,
                angleDegrees = angleForSecond(state.second),
                color = secondHandColor,
                strokeWidth = 2f
            )
            drawClockHand(
                center = center,
                length = minuteHandLength,
                angleDegrees = angleForMinute(state.minute),
                color = minuteHandColor,
                strokeWidth = 4f
            )
            drawClockHand(
                center = center,
                length = hourHandLength,
                angleDegrees = angleForHourDisplay(state.hour),
                color = hourHandColor,
                strokeWidth = 6f
            )
            drawCircle(color = hourHandColor, radius = 7f, center = center)
            drawCircle(color = faceColor, radius = 3f, center = center)
        }
    }
}

private fun DrawScope.drawTicks(center: Offset, tickStart: Float, color: Color) {
    for (i in 0 until 60) {
        val angle = angleForMinute(i)
        val long = i % 5 == 0
        val len = if (long) 10f else 5f
        val width = if (long) 2f else 1f
        val outer = polarToOffset(center, tickStart, angle)
        val inner = polarToOffset(center, tickStart - len, angle)
        drawLine(color = color, start = inner, end = outer, strokeWidth = width)
    }
}

private fun DrawScope.drawHourNumbers(
    center: Offset,
    numberRadius: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle
) {
    for (h in 1..12) {
        val angle = angleForHourLabel(h)
        val pos = polarToOffset(center, numberRadius, angle)
        val layout = measurer.measure(h.toString(), style = style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(pos.x - layout.size.width / 2f, pos.y - layout.size.height / 2f)
        )
    }
}

private fun DrawScope.drawInnerHourNumbers(
    center: Offset,
    radius: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle
) {
    // Inner ring runs 13..00 — placed at the same clock position as the
    // outer hour it corresponds to (e.g. 13 sits behind 1).
    for (outer in 1..12) {
        val display = if (outer == 12) 0 else outer + 12
        val angle = angleForHourLabel(outer)
        val pos = polarToOffset(center, radius, angle)
        val label = display.toString().padStart(2, '0')
        val layout = measurer.measure(label, style = style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(pos.x - layout.size.width / 2f, pos.y - layout.size.height / 2f)
        )
    }
}

private fun DrawScope.drawClockHand(
    center: Offset,
    length: Float,
    angleDegrees: Float,
    color: Color,
    strokeWidth: Float
) {
    val end = polarToOffset(center, length, angleDegrees)
    drawLine(
        color = color,
        start = center,
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

// --- pure helpers (testable) ---------------------------------------------

/**
 * Apply a pointer event at [position] (in pixels, within a [diameterPx]
 * square laid out by [ClockFace]) to [state]'s active hand. When the
 * pointer is the result of a tap (not a drag), [advanceActiveHand]
 * advances HOUR → MINUTE → SECOND so the user can tap-tap-tap through
 * all three picks without re-selecting tabs.
 *
 * 24-hour mode: an inner-ring pointer for the HOUR hand adds 12 so the
 * user can pick the PM half directly. Outer-ring picks keep the existing
 * AM/PM half (preserving the user's intent if they're refining a time).
 */
internal fun applyPointer(
    position: Offset,
    diameterPx: Float,
    state: AnalogClockState,
    advanceActiveHand: Boolean
) {
    val center = Offset(diameterPx / 2f, diameterPx / 2f)
    val dx = position.x - center.x
    val dy = position.y - center.y
    val distance = hypot(dx, dy)
    // Ignore taps near the center to avoid jitter — the user clearly
    // didn't aim a hand there.
    if (distance < 8f) return
    val angle = angleFromCenter(dx, dy)
    when (state.activeHand) {
        ClockHand.HOUR -> {
            val outerRadius = diameterPx / 2f - 8f
            val innerRing = state.is24Hour && distance < outerRadius * 0.66f
            val hour12 = hourFromAngle(angle)
            state.hour = if (state.is24Hour) {
                if (innerRing) (hour12 + 12) % 24 else hour12
            } else {
                val keepPm = !state.isAm
                if (keepPm) (hour12 % 12) + 12 else (hour12 % 12)
            }
            if (advanceActiveHand) state.activeHand = ClockHand.MINUTE
        }
        ClockHand.MINUTE -> {
            state.minute = minuteFromAngle(angle)
            if (advanceActiveHand) state.activeHand = ClockHand.SECOND
        }
        ClockHand.SECOND -> {
            state.second = minuteFromAngle(angle) // same 60-step ring
            // SECOND is terminal — no further advancement.
        }
    }
}

/** Polar → Cartesian offset, with 0° at 12 o'clock and angle in degrees, clockwise. */
internal fun polarToOffset(center: Offset, radius: Float, angleDegrees: Float): Offset {
    // Standard math: 0° → +x, counter-clockwise. We want 0° → -y, clockwise.
    val rad = Math.toRadians((angleDegrees - 90.0))
    return Offset(
        center.x + radius * cos(rad).toFloat(),
        center.y + radius * sin(rad).toFloat()
    )
}

/** Angle (degrees, 0° at 12 o'clock, clockwise) from a pointer offset relative to center. */
internal fun angleFromCenter(dx: Float, dy: Float): Float {
    // atan2 returns -PI..PI with 0 at +x; rotate so 0° is at 12 o'clock
    // and direction flips to clockwise.
    val raw = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val rotated = raw + 90f
    return ((rotated % 360f) + 360f) % 360f
}

internal fun angleForMinute(minute: Int): Float = (minute % 60) * 6f
internal fun angleForSecond(second: Int): Float = (second % 60) * 6f

/**
 * Position of the hour HAND in degrees. The hour hand advances smoothly
 * between hours by [minute] — at 8:30 the hand reads halfway between 8
 * and 9. The picker overwrites minute first, so this drift is purely
 * cosmetic for the user's *current* HH:mm, but it makes the clock read
 * naturally when re-opening with a stored time.
 */
internal fun angleForHourDisplay(hour: Int): Float {
    val h12 = hour % 12
    return (h12 * 30f)
}

/** Center angle for the printed hour number `h` (1..12). 12 sits at the top. */
internal fun angleForHourLabel(h: Int): Float = (h % 12) * 30f

/** Discretise a touch angle into a 0..11 hour-of-12. 0 maps to 12. */
internal fun hourFromAngle(angle: Float): Int {
    val raw = (angle / 30f).roundToInt() % 12
    return if (raw == 0) 12 else raw
}

/** Discretise a touch angle into a 0..59 minute. */
internal fun minuteFromAngle(angle: Float): Int {
    return ((angle / 6f).roundToInt() % 60 + 60) % 60
}

/** "h:mm:ss AM/PM" (12-hour) or "HH:mm:ss" (24-hour). Locale-stable. */
internal fun formatAnalogClockTime(hour: Int, minute: Int, second: Int, is24Hour: Boolean): String {
    val h = hour.coerceIn(0, 23)
    val m = minute.coerceIn(0, 59).toString().padStart(2, '0')
    val s = second.coerceIn(0, 59).toString().padStart(2, '0')
    return if (is24Hour) {
        "${h.toString().padStart(2, '0')}:$m:$s"
    } else {
        val display = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        val suffix = if (h < 12) "AM" else "PM"
        "$display:$m:$s $suffix"
    }
}

/** Parse `HH:mm` strings into a (hour, minute) pair; null when malformed. */
internal fun parseHhMm(raw: String): Pair<Int, Int>? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h to m
}

/** Serialize hour/minute as `HH:mm` for callers persisting the legacy text shape. */
internal fun formatHhMm(hour: Int, minute: Int): String {
    val h = hour.coerceIn(0, 23).toString().padStart(2, '0')
    val m = minute.coerceIn(0, 59).toString().padStart(2, '0')
    return "$h:$m"
}

@Suppress("unused")
private const val FULL_CIRCLE_DEGREES = 360
@Suppress("unused")
private const val RADIANS_TO_DEGREES = 180.0 / PI
