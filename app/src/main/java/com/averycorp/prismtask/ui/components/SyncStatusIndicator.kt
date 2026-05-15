package com.averycorp.prismtask.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismShapes

/**
 * Top-bar sync indicator state model.
 *
 * Priority ordering (most to least important) when the state repository maps
 * ambient signals into a [SyncState]:
 * 1. [NotSignedIn] — silent (no indicator rendered).
 * 2. [Syncing] — a network operation is currently in flight.
 * 3. [Error] — the most recent sync attempt failed; tap opens the details sheet.
 * 4. [Offline] — no connectivity and [count] pending operations queued.
 * 5. [Pending] — online but [count] operations haven't been pushed yet.
 * 6. [Synced] — most recent sync succeeded; shown briefly then fades out.
 */
sealed class SyncState {
    data object Synced : SyncState()

    data object Syncing : SyncState()

    data class Pending(val count: Int) : SyncState()

    data class Offline(val count: Int) : SyncState()

    data class Error(val message: String) : SyncState()

    data object NotSignedIn : SyncState()
}

@Composable
fun SyncStatusIndicator(
    syncState: SyncState,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalPrismColors.current
    when (syncState) {
        is SyncState.NotSignedIn -> {}
        is SyncState.Synced -> {
            StatusChip(
                text = "Synced",
                color = colors.primary,
                icon = Icons.Filled.CheckCircle,
                onClick = onTap,
                onLongClick = onLongPress,
                modifier = modifier
            )
        }
        is SyncState.Syncing -> {
            StatusChip(
                text = "Syncing…",
                color = colors.primary,
                showSpinner = true,
                onClick = onTap,
                onLongClick = onLongPress,
                modifier = modifier
            )
        }
        is SyncState.Pending -> {
            StatusChip(
                text = "${syncState.count} pending",
                color = colors.secondary,
                icon = Icons.Filled.WatchLater,
                onClick = onTap,
                onLongClick = onLongPress,
                modifier = modifier
            )
        }
        is SyncState.Offline -> {
            StatusChip(
                text = if (syncState.count > 0) "Offline — ${syncState.count} queued" else "Offline",
                color = colors.muted,
                icon = Icons.Filled.CloudOff,
                onClick = onTap,
                onLongClick = onLongPress,
                modifier = modifier
            )
        }
        is SyncState.Error -> {
            StatusChip(
                text = "Sync error",
                color = colors.urgentAccent,
                icon = Icons.Filled.Error,
                onClick = onTap,
                onLongClick = onLongPress,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    color: Color,
    showSpinner: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val gestureModifier = if (onClick != null || onLongClick != null) {
        Modifier.pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onTap = { onClick?.invoke() },
                onLongPress = { onLongClick?.invoke() }
            )
        }
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .clip(LocalPrismShapes.current.chip)
            .background(color.copy(alpha = 0.12f))
            .then(gestureModifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .animateContentSize()
            .semantics {
                contentDescription = "Sync status: $text"
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = color
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}
