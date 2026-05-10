package com.averycorp.prismtask.ui.screens.today.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact reminder card surfaced on the Today screen for habits the user
 * has booked for today — e.g. an upcoming appointment or commitment.
 * Tapping navigates to the habit detail.
 */
@Composable
internal fun BookableHabitReminderCard(
    habitWithStatus: HabitWithStatus,
    onClick: () -> Unit
) {
    val habit = habitWithStatus.habit
    val colors = LocalPrismColors.current
    val fonts = LocalPrismFonts.current.body
    val habitColor = remember(habit.color, colors.primary) {
        try {
            Color(android.graphics.Color.parseColor(habit.color))
        } catch (_: Exception) {
            colors.primary
        }
    }
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val noteStr = habit.bookedNote?.let { " — $it" } ?: ""
    val dateStr = habit.bookedDate?.let { dateFormat.format(Date(it)) } ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = colors.border,
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = habitColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = habit.icon, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = fonts,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground
                )
                Text(
                    text = "📅 $dateStr$noteStr",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fonts,
                    color = colors.secondary
                )
            }
        }
    }
}
