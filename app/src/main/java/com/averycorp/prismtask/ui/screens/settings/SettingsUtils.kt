package com.averycorp.prismtask.ui.screens.settings

import androidx.compose.ui.graphics.Color

internal val accentColors = listOf(
    "#2563EB",
    "#7C3AED",
    "#DB2777",
    "#DC2626",
    "#EA580C",
    "#D97706",
    "#65A30D",
    "#059669",
    "#0891B2",
    "#6366F1",
    "#8B5CF6",
    "#EC4899"
)

internal val sectionLabels = mapOf(
    "progress" to "Progress Card",
    "overdue" to "From Earlier",
    "today_tasks" to "Today Tasks",
    "daily_essentials" to "Daily Essentials",
    "habits" to "Habits",
    "plan_more" to "Plan More",
    "completed" to "Completed"
)

internal val sortLabels = mapOf(
    "DUE_DATE" to "Due Date",
    "PRIORITY" to "Priority",
    "URGENCY" to "Urgency",
    "CREATED" to "Date Created",
    "ALPHABETICAL" to "Alphabetical",
    "CUSTOM" to "Custom"
)

internal val viewModeLabels = mapOf(
    "UPCOMING" to "Upcoming",
    "LIST" to "List",
    "WEEK" to "Week",
    "MONTH" to "Month"
)

internal fun parseColorSafe(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFF4285F4) // Google Blue default
}

internal fun formatLastSync(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} hr ago"
        else -> {
            val date = java.util.Date(timestamp)
            val format = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            format.format(date)
        }
    }
}
