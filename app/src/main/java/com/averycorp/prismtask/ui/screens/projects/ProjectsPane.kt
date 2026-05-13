package com.averycorp.prismtask.ui.screens.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * chip row (Active / Completed / All) + a list of [ProjectCard]s. Tapping
 * a card navigates to the project detail screen; the FAB opens the create
 * flow.
 *
 * Archived projects are intentionally absent from the chip row. They live
 * behind a small "View Archived" footer link so they stay accessible
 * without crowding the default view. Tapping it swaps the pane into a
 * dedicated archived-only mode with its own back-affordance header.
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
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val archivedCount by viewModel.archivedCount.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nowMillis = remember { System.currentTimeMillis() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showArchived) {
                ArchivedHeader(onBack = { viewModel.setShowArchived(false) })
            } else {
                StatusFilterRow(
                    selected = statusFilter,
                    onSelect = viewModel::setStatusFilter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (projects.isEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                RichEmptyState(
                    icon = "📂",
                    title = when {
                        showArchived -> "No Archived Projects"
                        statusFilter == ProjectStatus.COMPLETED -> "No Completed Projects"
                        statusFilter == ProjectStatus.ACTIVE -> "No Active Projects"
                        else -> "No Projects Yet"
                    },
                    description = if (showArchived) {
                        "Archive A Project To Move It Out Of Your Active List."
                    } else {
                        "Tap + To Create A Project."
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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

            if (!showArchived && archivedCount > 0) {
                ArchivedFooterLink(
                    count = archivedCount,
                    onClick = { viewModel.setShowArchived(true) }
                )
            }
        }

        if (!showArchived) {
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
        null to "All"
    )
    Row(
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

@Composable
private fun ArchivedHeader(onBack: () -> Unit) {
    val prismColors = LocalPrismColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back To Projects",
                tint = prismColors.onSurface
            )
        }
        Text(
            text = "Archived Projects",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = prismColors.onSurface
        )
    }
}

@Composable
private fun ArchivedFooterLink(
    count: Int,
    onClick: () -> Unit
) {
    val prismColors = LocalPrismColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Inventory2,
            contentDescription = null,
            tint = prismColors.muted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = "View Archived ($count)",
            style = MaterialTheme.typography.labelMedium,
            color = prismColors.muted
        )
    }
}
