package com.averycorp.prismtask.ui.screens.tasklist.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.domain.model.TaskFilter

/**
 * Horizontal scrolling row of project filter chips + an "All" reset
 * chip + a "Manage" assist chip that opens the project management
 * screen. Each project's chip is tinted with the project's own color
 * and shows its incomplete-task count, so the row reads as a small
 * project dashboard rather than a flat filter list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectFilterRow(
    projects: List<ProjectEntity>,
    selectedProjectId: Long?,
    taskCountByProject: Map<Long, Int>,
    onSelectProject: (Long?) -> Unit,
    onManageProjects: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        item {
            FilterChip(
                selected = selectedProjectId == null,
                onClick = { onSelectProject(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        items(projects, key = { it.id }) { project ->
            val projectColor = try {
                Color(android.graphics.Color.parseColor(project.color))
            } catch (_: Exception) {
                MaterialTheme.colorScheme.primary
            }
            val count = taskCountByProject[project.id] ?: 0
            FilterChip(
                selected = selectedProjectId == project.id,
                onClick = { onSelectProject(project.id) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(projectColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(project.name)
                        if (count > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = projectColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = projectColor.copy(alpha = 0.06f),
                    selectedContainerColor = projectColor.copy(alpha = 0.18f),
                    selectedLabelColor = projectColor
                )
            )
        }
        item {
            AssistChip(
                onClick = onManageProjects,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FolderCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Manage")
                    }
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    }
}

/**
 * Row of active filter "pill" chips — one per active filter facet
 * (tags, priorities, projects, date range, completed, archived, and
 * search query). Tapping any pill clears that specific facet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActiveFilterPills(
    filter: TaskFilter,
    allTags: List<TagEntity>,
    projects: List<ProjectEntity>,
    onUpdateFilter: (TaskFilter) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (filter.selectedTagIds.isNotEmpty()) {
            val tagNames = allTags.filter { it.id in filter.selectedTagIds }.joinToString(", ") { it.name }
            val modeLabel = if (filter.tagFilterMode == com.averycorp.prismtask.domain.model.TagFilterMode.ALL) "ALL" else "ANY"
            RemovableFilterChip(
                label = "Tags ($modeLabel): $tagNames",
                onRemove = { onUpdateFilter(filter.copy(selectedTagIds = emptyList())) }
            )
        }

        if (filter.selectedPriorities.isNotEmpty()) {
            val labels = filter.selectedPriorities.sorted().joinToString(", ") { p ->
                when (p) {
                    0 -> "None"
                    1 -> "Low"
                    2 -> "Med"
                    3 -> "High"
                    4 -> "Urgent"
                    else -> "$p"
                }
            }
            RemovableFilterChip(
                label = "Priority: $labels",
                onRemove = { onUpdateFilter(filter.copy(selectedPriorities = emptyList())) }
            )
        }

        if (filter.selectedProjectIds.isNotEmpty()) {
            val projNames = projects.filter { it.id in filter.selectedProjectIds }.joinToString(", ") { it.name }
            RemovableFilterChip(
                label = "Project: $projNames",
                onRemove = { onUpdateFilter(filter.copy(selectedProjectIds = emptyList())) }
            )
        }

        if (filter.dateRange != null) {
            val rangeLabel = when {
                filter.dateRange.start == null && filter.dateRange.end == null -> "No Date"
                else -> "Date range"
            }
            RemovableFilterChip(
                label = rangeLabel,
                onRemove = { onUpdateFilter(filter.copy(dateRange = null)) }
            )
        }

        if (filter.showCompleted) {
            RemovableFilterChip(
                label = "Completed",
                onRemove = { onUpdateFilter(filter.copy(showCompleted = false)) }
            )
        }

        if (filter.showArchived) {
            RemovableFilterChip(
                label = "Archived",
                onRemove = { onUpdateFilter(filter.copy(showArchived = false)) }
            )
        }

        if (filter.searchQuery.isNotBlank()) {
            RemovableFilterChip(
                label = "\"${filter.searchQuery}\"",
                onRemove = { onUpdateFilter(filter.copy(searchQuery = "")) }
            )
        }
    }
}

/**
 * An assist chip with a trailing close icon — pressing the chip
 * invokes [onRemove] and the parent is expected to drop the matching
 * filter facet.
 */
@Composable
internal fun RemovableFilterChip(
    label: String,
    onRemove: () -> Unit
) {
    AssistChip(
        onClick = onRemove,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove filter",
                modifier = Modifier.size(14.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}
