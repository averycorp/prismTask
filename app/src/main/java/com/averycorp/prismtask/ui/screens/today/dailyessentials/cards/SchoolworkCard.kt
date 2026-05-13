package com.averycorp.prismtask.ui.screens.today.dailyessentials.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.usecase.AssignmentSummary
import com.averycorp.prismtask.domain.usecase.SchoolworkCardState

@Composable
fun SchoolworkCard(
    state: SchoolworkCardState,
    onToggleHabit: () -> Unit,
    onOpenAssignment: (assignmentId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = Color(0xFFCFB87C)
    val description = buildString {
        append("Schoolwork")
        state.habit?.let {
            append(", habit ${if (it.completedToday) "done" else "not done"}")
        }
        if (state.assignmentsDueToday.isNotEmpty()) {
            append(", ${state.assignmentsDueToday.size} assignments due today")
        }
    }

    DailyEssentialCard(
        accent = accent,
        contentDescription = description,
        onClick = null,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Schoolwork",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            state.habit?.let { habit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(onClick = onToggleHabit)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (habit.completedToday) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = habit.name,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (habit.completedToday) TextDecoration.LineThrough else null
                    )
                }
            }

            groupByClass(state.assignmentsDueToday).forEach { group ->
                ClassGroup(group = group, onOpenAssignment = onOpenAssignment)
            }
        }
    }
}

private data class ClassAssignmentGroup(
    val courseId: Long,
    val courseName: String,
    val courseColor: Int,
    val assignments: List<AssignmentSummary>
)

private fun groupByClass(
    assignments: List<AssignmentSummary>
): List<ClassAssignmentGroup> {
    if (assignments.isEmpty()) return emptyList()
    // Preserve assignment order within each class; order classes by first
    // appearance so the visual order matches the underlying due-date sort.
    val byCourse = linkedMapOf<Long, MutableList<AssignmentSummary>>()
    for (a in assignments) {
        byCourse.getOrPut(a.courseId) { mutableListOf() }.add(a)
    }
    return byCourse.map { (courseId, items) ->
        ClassAssignmentGroup(
            courseId = courseId,
            courseName = items.first().courseName,
            courseColor = items.first().courseColor,
            assignments = items
        )
    }
}

@Composable
private fun ClassGroup(
    group: ClassAssignmentGroup,
    onOpenAssignment: (assignmentId: Long) -> Unit
) {
    val dot = if (group.courseColor != 0) Color(group.courseColor) else Color.Gray
    val headerLabel = group.courseName.ifBlank { "Unassigned" }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = headerLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        group.assignments.forEach { assignment ->
            AssignmentRow(
                summary = assignment,
                onClick = { onOpenAssignment(assignment.id) }
            )
        }
    }
}

@Composable
private fun AssignmentRow(
    summary: AssignmentSummary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = summary.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (summary.completed) TextDecoration.LineThrough else null,
            color = if (summary.completed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
