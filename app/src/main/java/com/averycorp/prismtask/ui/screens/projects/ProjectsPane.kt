package com.averycorp.prismtask.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.domain.model.ProjectStatus
import com.averycorp.prismtask.ui.components.RichEmptyState
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.projects.components.ProjectCard
import com.averycorp.prismtask.ui.theme.LocalPrismColors

/**
 * The Projects side of the Tasks-tab segmented toggle. Renders a filter
 * chip row + a list of [ProjectCard]s. Tapping a card navigates to the
 * project detail screen; the FAB opens the create flow.
 *
 * This composable is deliberately inline (not a full screen) — it lives
 * inside TaskListScreen's Scaffold body so switching sides of the toggle
 * doesn't blow away scroll / filter state.
 */
@Composable
fun ProjectsPane(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: ProjectsPaneViewModel = hiltViewModel()
) {
    val prismColors = LocalPrismColors.current
    val statusFilter by viewModel.statusFilter.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nowMillis = remember { System.currentTimeMillis() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusFilterRow(
                selected = statusFilter,
                onSelect = viewModel::setStatusFilter,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            if (projects.isEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                RichEmptyState(
                    icon = "\uD83D\uDCC2",
                    title = when (statusFilter) {
                        ProjectStatus.COMPLETED -> "No Completed Projects"
                        ProjectStatus.ARCHIVED -> "No Archived Projects"
                        ProjectStatus.ACTIVE -> "No Active Projects"
                        null -> "No Projects Yet"
                    },
                    description = "Tap + To Create A Project."
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(projects, key = { it.project.id }) { data ->
                        ProjectCard(
                            data = data,
                            onClick = {
                                navController.navigate(
                                    PrismTaskRoute.ProjectDetail.createRoute(data.project.id)
                                )
                            },
                            nowMillis = nowMillis,
                            onArchive = { viewModel.archiveProject(data.project.id) },
                            onComplete = { viewModel.completeProject(data.project.id) },
                            onReopen = { viewModel.reopenProject(data.project.id) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                navController.navigate(PrismTaskRoute.AddEditProject.createRoute(null))
            },
            containerColor = prismColors.primary,
            contentColor = prismColors.onBackground,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Project")
        }
    }
}

@Composable
private fun StatusFilterRow(
    selected: ProjectStatus?,
    onSelect: (ProjectStatus?) -> Unit,
    modifier: Modifier = Modifier
) {
    val prismColors = LocalPrismColors.current
    val options: List<Pair<ProjectStatus?, String>> = listOf(
        ProjectStatus.ACTIVE to "Active",
        ProjectStatus.COMPLETED to "Completed",
        ProjectStatus.ARCHIVED to "Archived",
        null to "All"
    )
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (status, label) ->
            val isSelected = selected == status
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(status) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = prismColors.surface,
                    labelColor = prismColors.onSurface,
                    selectedContainerColor = prismColors.tagSurface,
                    selectedLabelColor = prismColors.primary
                )
            )
        }
    }
}
