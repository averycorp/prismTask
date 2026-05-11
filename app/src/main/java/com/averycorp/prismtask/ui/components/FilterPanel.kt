package com.averycorp.prismtask.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.domain.model.DateRange
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.model.TagFilterMode
import com.averycorp.prismtask.domain.model.TaskFilter
import com.averycorp.prismtask.ui.theme.LifeCategoryColor
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.prismGlow
import java.util.Calendar

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPanel(
    currentFilter: TaskFilter,
    allTags: List<TagEntity>,
    allProjects: List<ProjectEntity>,
    onFilterChanged: (TaskFilter) -> Unit,
    onClearAll: () -> Unit
) {
    val prismColors = LocalPrismColors.current
    val attrs = LocalPrismAttrs.current
    var workingFilter by remember(currentFilter) { mutableStateOf(currentFilter) }

    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startOfToday = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_YEAR, 1)
    val endOfToday = calendar.timeInMillis
    calendar.timeInMillis = startOfToday
    calendar.add(Calendar.DAY_OF_YEAR, 7)
    val endOfWeek = calendar.timeInMillis

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Filters",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (workingFilter.isActive()) {
                TextButton(onClick = {
                    workingFilter = TaskFilter()
                    onClearAll()
                }) {
                    Text("Clear All")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tags section
        if (allTags.isNotEmpty()) {
            Text(
                text = "Tags",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = workingFilter.tagFilterMode == TagFilterMode.ANY,
                    onClick = {
                        workingFilter = workingFilter.copy(tagFilterMode = TagFilterMode.ANY)
                    },
                    label = { Text("OR", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier
                        .height(28.dp)
                        .then(
                            if (workingFilter.tagFilterMode == TagFilterMode.ANY) {
                                Modifier.prismGlow(
                                    prismColors.primary,
                                    attrs.glow
                                )
                            } else {
                                Modifier
                            }
                        )
                )
                FilterChip(
                    selected = workingFilter.tagFilterMode == TagFilterMode.ALL,
                    onClick = {
                        workingFilter = workingFilter.copy(tagFilterMode = TagFilterMode.ALL)
                    },
                    label = { Text("AND", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier
                        .height(28.dp)
                        .then(
                            if (workingFilter.tagFilterMode == TagFilterMode.ALL) {
                                Modifier.prismGlow(
                                    prismColors.primary,
                                    attrs.glow
                                )
                            } else {
                                Modifier
                            }
                        )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                allTags.forEach { tag ->
                    val isSelected = tag.id in workingFilter.selectedTagIds
                    val tagColor = try {
                        Color(android.graphics.Color.parseColor(tag.color))
                    } catch (_: Exception) {
                        Color.Gray
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            val newIds = if (isSelected) {
                                workingFilter.selectedTagIds - tag.id
                            } else {
                                workingFilter.selectedTagIds + tag.id
                            }
                            workingFilter = workingFilter.copy(selectedTagIds = newIds)
                        },
                        label = { Text(tag.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tagColor.copy(alpha = 0.2f),
                            selectedLabelColor = tagColor
                        ),
                        modifier = if (isSelected) Modifier.prismGlow(tagColor, attrs.glow) else Modifier
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Priority section
        Text(
            text = "Priority",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val priorityLabels = listOf("None" to 0, "Low" to 1, "Medium" to 2, "High" to 3, "Urgent" to 4)
            priorityLabels.forEach { (label, level) ->
                val isSelected = level in workingFilter.selectedPriorities
                val priorityColor = LocalPriorityColors.current.forLevel(level)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        val newPriorities = if (isSelected) {
                            workingFilter.selectedPriorities - level
                        } else {
                            workingFilter.selectedPriorities + level
                        }
                        workingFilter = workingFilter.copy(selectedPriorities = newPriorities)
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = priorityColor.copy(alpha = 0.2f),
                        selectedLabelColor = priorityColor
                    ),
                    modifier = if (isSelected) Modifier.prismGlow(priorityColor, attrs.glow) else Modifier
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Life Category section (Work-Life Balance Engine v1.4.0 V1)
        Text(
            text = "Life Category",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LifeCategory.TRACKED.forEach { category ->
                val isSelected = category in workingFilter.selectedLifeCategories
                val color = LifeCategoryColor.forCategory(category)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        val newList = if (isSelected) {
                            workingFilter.selectedLifeCategories - category
                        } else {
                            workingFilter.selectedLifeCategories + category
                        }
                        workingFilter = workingFilter.copy(selectedLifeCategories = newList)
                    },
                    label = { Text(LifeCategory.label(category)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.2f),
                        selectedLabelColor = color
                    ),
                    modifier = if (isSelected) Modifier.prismGlow(color, attrs.glow) else Modifier
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Due Date section
        Text(
            text = "Due Date",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            data class DateOption(val label: String, val range: DateRange?)
            val dateOptions = listOf(
                DateOption("Today", DateRange(startOfToday, endOfToday)),
                DateOption("This Week", DateRange(startOfToday, endOfWeek)),
                DateOption("From Earlier", DateRange(0L, startOfToday)),
                DateOption("No Date", DateRange(null, null))
            )

            dateOptions.forEach { option ->
                val isSelected = workingFilter.dateRange == option.range
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        workingFilter = workingFilter.copy(
                            dateRange = if (isSelected) null else option.range
                        )
                    },
                    label = { Text(option.label) },
                    modifier = if (isSelected) Modifier.prismGlow(prismColors.primary, attrs.glow) else Modifier
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status section
        Text(
            text = "Status",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularCheckbox(
                checked = workingFilter.showCompleted,
                onCheckedChange = { workingFilter = workingFilter.copy(showCompleted = it) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Show Completed")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularCheckbox(
                checked = workingFilter.showArchived,
                onCheckedChange = { workingFilter = workingFilter.copy(showArchived = it) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Show Archived")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularCheckbox(
                checked = workingFilter.showFlaggedOnly,
                onCheckedChange = { workingFilter = workingFilter.copy(showFlaggedOnly = it) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Flagged Only")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Apply button
        Button(
            onClick = { onFilterChanged(workingFilter) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Apply")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
