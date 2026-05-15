package com.averycorp.prismtask.ui.screens.medication.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.ui.components.AnalogClockPicker
import com.averycorp.prismtask.ui.components.rememberAnalogClockState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bottom sheet that lets the user stamp a wall-clock time on the slot's
 * tier-state — the primary affordance for logging "I took this at 8am
 * but only just opened the app". Opened via long-press on the slot's
 * tier chip.
 *
 * The picker is the three-hand analog clock (hour / minute / second) per
 * the `feedback-time-input-use-clock-not-slider` memory. The data model
 * stores HH:mm, so the seconds hand is captured but rounded out at save
 * time; the affordance stays consistent with every other time-of-day
 * input in the app.
 *
 * Save composes the picked hour/minute with the slot card's [logicalDay]
 * (the user's SoD-anchored "today"), then caps to `now` so the user can
 * never produce a future timestamp. See [composeIntendedTime] for the
 * algorithm — in particular, it correctly handles the SoD-boundary
 * window where wall-clock has crossed midnight but the logical day has
 * not yet rolled over.
 *
 * Long-tail "log yesterday's dose" (a logical-day cross-day backlog)
 * remains out of scope; that case is served via the medication log
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationTimeEditSheet(
    initialIntendedTime: Long?,
    slotName: String,
    logicalDay: LocalDate,
    onDismiss: () -> Unit,
    onSave: (intendedTime: Long) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    val zone = remember { ZoneId.systemDefault() }
    val seed = initialIntendedTime ?: System.currentTimeMillis()
    val seedTime = remember(seed) {
        Instant.ofEpochMilli(seed).atZone(zone).toLocalTime()
    }
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val clockState = rememberAnalogClockState(
        initialHour = seedTime.hour,
        initialMinute = seedTime.minute,
        initialSecond = 0,
        is24Hour = is24Hour
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "When did you take it?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = slotName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            AnalogClockPicker(state = clockState)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Button(onClick = {
                    onSave(
                        composeIntendedTime(
                            pickedHour = clockState.hour,
                            pickedMinute = clockState.minute,
                            logicalDay = logicalDay,
                            nowMillis = System.currentTimeMillis(),
                            zone = zone
                        )
                    )
                }) { Text("Save") }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Compose the user-picked HH:mm with the slot card's [logicalDay] into an
 * absolute epoch-millis timestamp.
 *
 * The user-anchor we want is "the latest moment ≤ [nowMillis] within the
 * logical-day window matching the picked HH:mm". Because a logical day
 * with SoD > 00:00 spans two calendar dates (e.g. logical Apr 28 with
 * SoD = 04:00 runs Apr 28 04:00 → Apr 29 03:59 wall-clock), the picked
 * time may resolve to either calendar date. We try both and pick the
 * latest candidate that's still in the past.
 *
 *  - User on logical Apr 28 (wall-clock Apr 28 14:00) picks 08:00 →
 *    onLogicalDay = Apr 28 08:00 ≤ now ✓; onNextDay = Apr 29 08:00 > now ✗.
 *    Result: Apr 28 08:00.
 *  - User on logical Apr 28 (wall-clock Apr 29 02:00, after midnight) picks
 *    08:00 → onLogicalDay = Apr 28 08:00 ≤ now ✓; onNextDay = Apr 29 08:00
 *    > now ✗. Result: Apr 28 08:00 (this morning by SoD).
 *  - Same context, picks 01:00 → onLogicalDay = Apr 28 01:00 ≤ now ✓;
 *    onNextDay = Apr 29 01:00 ≤ now ✓. Result: Apr 29 01:00 (just an
 *    hour ago, the more recent candidate).
 *
 * If neither candidate is ≤ now (the user picked a forward time that
 * wraps past now on both calendar dates — vanishingly rare), cap to now.
 */
internal fun composeIntendedTime(
    pickedHour: Int,
    pickedMinute: Int,
    logicalDay: LocalDate,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val onLogicalDay = logicalDay.atTime(pickedHour, pickedMinute)
        .atZone(zone).toInstant().toEpochMilli()
    val onNextDay = logicalDay.plusDays(1).atTime(pickedHour, pickedMinute)
        .atZone(zone).toInstant().toEpochMilli()
    val pastCandidates = listOf(onLogicalDay, onNextDay).filter { it <= nowMillis }
    return pastCandidates.maxOrNull() ?: nowMillis
}
