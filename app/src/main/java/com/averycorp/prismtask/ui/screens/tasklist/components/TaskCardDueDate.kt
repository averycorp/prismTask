package com.averycorp.prismtask.ui.screens.tasklist.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A due-date label together with the color it should render in.
 * Overdue dates keep the normal on-surface-variant color; "Today"
 * picks up [warningColor] for the quick visual scan.
 */
internal data class DueDateLabel(val text: String, val color: Color)

/**
 * Format a task's due date into its card-row label: "Today" for
 * today, "Tomorrow" for tomorrow, formatted date otherwise.
 *
 * Reads SoD-anchored day bounds from [LocalDayBounds] so the
 * Today/Tomorrow split tracks the user's configured Start of Day,
 * not raw calendar midnight — see
 * `docs/audits/TODAY_LABEL_SOD_BOUNDARY_AUDIT.md`.
 */
@Composable
internal fun formatDueDate(epochMillis: Long): DueDateLabel {
    val bounds = LocalDayBounds.current
    val normal = MaterialTheme.colorScheme.onSurfaceVariant
    val todayColor = LocalPrismColors.current.warningColor
    val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    return when (classifyDueDate(epochMillis, bounds)) {
        DueDateBucket.PAST -> DueDateLabel(dateFmt.format(Date(epochMillis)), normal)
        DueDateBucket.TODAY -> DueDateLabel("Today", todayColor)
        DueDateBucket.TOMORROW -> DueDateLabel("Tomorrow", normal)
        DueDateBucket.FUTURE -> DueDateLabel(dateFmt.format(Date(epochMillis)), normal)
    }
}
