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
import com.averycorp.prismtask.domain.usecase.CourseCompletionStatus
import com.averycorp.prismtask.domain.usecase.SchoolworkCardState

@Composable
fun SchoolworkCard(
    state: SchoolworkCardState,
    onToggleCourse: (courseId: Long) -> Unit,
    onOpenAssignment: (assignmentId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = Color(0xFFCFB87C)
    val description = buildString {
        append("Schoolwork")
        if (state.courses.isNotEmpty()) {
            val done = state.courses.count { it.completedToday }
            append(", $done of ${state.courses.size} classes done")
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

            val assignmentsByCourse: Map<Long, List<AssignmentSummary>> =
                state.assignmentsDueToday.groupBy { it.courseId }
            val coveredCourseIds = state.courses.map { it.courseId }.toSet()

            state.courses.forEach { course ->
                CourseGroup(
                    course = course,
                    assignments = assignmentsByCourse[course.courseId].orEmpty(),
                    accent = accent,
                    onToggle = { onToggleCourse(course.courseId) },
                    onOpenAssignment = onOpenAssignment
                )
            }

            // Assignments whose course isn't in the active-course list (e.g.
            // archived or deleted course). Render them flat at the bottom so
            // they're still actionable.
            state.assignmentsDueToday
                .filter { it.courseId !in coveredCourseIds }
                .forEach { assignment ->
                    AssignmentRow(
                        summary = assignment,
                        onClick = { onOpenAssignment(assignment.id) }
                    )
                }
        }
    }
}

@Composable
private fun CourseGroup(
    course: CourseCompletionStatus,
    assignments: List<AssignmentSummary>,
    accent: Color,
    onToggle: () -> Unit,
    onOpenAssignment: (assignmentId: Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CourseRow(course = course, accent = accent, onToggle = onToggle)
        assignments.forEach { assignment ->
            AssignmentRow(
                summary = assignment,
                onClick = { onOpenAssignment(assignment.id) }
            )
        }
    }
}

@Composable
private fun CourseRow(
    course: CourseCompletionStatus,
    accent: Color,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (course.completedToday) {
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
            text = course.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (course.completedToday) TextDecoration.LineThrough else null
        )
    }
}

@Composable
private fun AssignmentRow(
    summary: AssignmentSummary,
    onClick: () -> Unit
) {
    val dot = if (summary.courseColor != 0) Color(summary.courseColor) else Color.Gray
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClick = onClick)
            .padding(start = 32.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(modifier = Modifier.width(8.dp))
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
