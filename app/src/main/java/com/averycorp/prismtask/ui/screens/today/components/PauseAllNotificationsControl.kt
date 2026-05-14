package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.notifications.NotificationPauseGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * MH-first G4 Today-screen Pause-All control.
 *
 * Compact icon in the header that opens a three-option sheet:
 * "Pause for 1 hour", "Pause for 4 hours", and "Pause until tomorrow
 * morning" (resolves to the user's Start-of-Day boundary). When paused
 * the icon swaps to a "notifications off" variant and a small status
 * pill renders the expiry as a wall-clock time.
 *
 * See `NotificationPauseGate` for the gate semantics. Medication
 * reminders are deliberately exempt — the sheet copy is non-judgmental
 * and never claims to silence "all" notifications, only the productivity
 * stream.
 */
@HiltViewModel
class PauseAllNotificationsViewModel
@Inject
constructor(
    private val pauseGate: NotificationPauseGate
) : ViewModel() {
    private val _now = MutableStateFlow(System.currentTimeMillis())
    /**
     * Re-emits every 30s so the "Paused until 4:30 PM" label drops off
     * naturally when the pause expires without requiring the user to
     * navigate away and back. 30s granularity keeps the cost trivial
     * while never lagging the expiry by more than a render tick.
     */
    val now: StateFlow<Long> = _now.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        System.currentTimeMillis()
    )

    val pauseUntilEpochMs: StateFlow<Long> = pauseGate.pauseUntilFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    fun pauseForHours(hours: Int) {
        viewModelScope.launch {
            pauseGate.pauseFor(hours * 60L * 60L * 1000L)
        }
    }

    fun pauseUntilTomorrowMorning() {
        viewModelScope.launch {
            pauseGate.pauseUntilTomorrowMorning()
        }
    }

    fun resume() {
        viewModelScope.launch { pauseGate.resume() }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 30_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseAllNotificationsControl(
    viewModel: PauseAllNotificationsViewModel = hiltViewModel()
) {
    val now by viewModel.now.collectAsState()
    val pauseUntil by viewModel.pauseUntilEpochMs.collectAsState()
    val isPaused = pauseUntil > now

    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = { showSheet = true },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (isPaused) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
            contentDescription = if (isPaused) {
                "Notifications Paused — Tap to Resume or Adjust"
            } else {
                "Pause Notifications"
            },
            modifier = Modifier.size(20.dp),
            tint = if (isPaused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            PauseAllNotificationsSheet(
                isPaused = isPaused,
                pauseUntilEpochMs = pauseUntil,
                onPauseHours = { hours ->
                    viewModel.pauseForHours(hours)
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showSheet = false
                    }
                },
                onPauseUntilTomorrow = {
                    viewModel.pauseUntilTomorrowMorning()
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showSheet = false
                    }
                },
                onResume = {
                    viewModel.resume()
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showSheet = false
                    }
                }
            )
        }
    }
}

/**
 * Small status pill rendered in the Today header when notifications are
 * paused. Reads "Paused Until 4:30 PM" — descriptive only, no
 * "snoozed" / "DND" / "you turned off" framing.
 *
 * Returns an empty composition when not paused; safe to render
 * unconditionally.
 */
@Composable
fun PauseStatusPill(
    viewModel: PauseAllNotificationsViewModel = hiltViewModel()
) {
    val now by viewModel.now.collectAsState()
    val pauseUntil by viewModel.pauseUntilEpochMs.collectAsState()
    if (pauseUntil <= now) return

    val labelTime = remember(pauseUntil) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pauseUntil))
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Paused Until $labelTime",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun PauseAllNotificationsSheet(
    isPaused: Boolean,
    pauseUntilEpochMs: Long,
    onPauseHours: (Int) -> Unit,
    onPauseUntilTomorrow: () -> Unit,
    onResume: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (isPaused) "Notifications Paused" else "Pause Notifications",
            style = MaterialTheme.typography.titleMedium
        )
        if (isPaused) {
            val labelTime = remember(pauseUntilEpochMs) {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pauseUntilEpochMs))
            }
            Text(
                text = "Paused until $labelTime. Medication reminders still fire.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Quiet the productivity stream for a bit. Medication " +
                    "reminders still fire.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.size(4.dp))
        TextButton(
            onClick = { onPauseHours(1) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Pause for 1 Hour",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start
            )
        }
        TextButton(
            onClick = { onPauseHours(4) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Pause for 4 Hours",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start
            )
        }
        TextButton(
            onClick = onPauseUntilTomorrow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Pause Until Tomorrow Morning",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start
            )
        }
        if (isPaused) {
            TextButton(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Resume Notifications Now",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
    }
}
