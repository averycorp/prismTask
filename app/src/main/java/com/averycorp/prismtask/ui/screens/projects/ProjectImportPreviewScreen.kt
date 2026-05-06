package com.averycorp.prismtask.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.averycorp.prismtask.domain.usecase.ImportOutcome
import com.averycorp.prismtask.domain.usecase.ImportPlan

/**
 * Full-screen preview of a parsed project import. Renders the parsed
 * tree (project name, phases, tasks, risks, anchors, dependencies) so
 * the user can review what would land in Room before approving.
 *
 * Mirrors the BatchPreview shape — sealed-state ViewModel with
 * re-entry guards, per-row Checkbox to opt out of tasks / risks,
 * Cancel / Approve bottom bar that emits [ImportPreviewEvent] back
 * to the caller for snackbar display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectImportPreviewScreen(
    navController: NavHostController,
    uriString: String?,
    asProject: Boolean,
    onApproved: (outcome: ImportOutcome) -> Unit,
    onCancelled: () -> Unit,
    viewModel: ProjectImportPreviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val excludedTasks by viewModel.excludedTasks.collectAsStateWithLifecycle()
    val excludedRisks by viewModel.excludedRisks.collectAsStateWithLifecycle()

    LaunchedEffect(uriString, asProject) {
        viewModel.loadPlan(uriString, asProject)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportPreviewEvent.Approved -> onApproved(event.outcome)
                is ImportPreviewEvent.Cancelled -> onCancelled()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Preview") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancel() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        },
        bottomBar = {
            BottomBar(
                state = state,
                onCancel = viewModel::cancel,
                onApprove = viewModel::approve
            )
        }
    ) { padding ->
        when (val s = state) {
            ImportPreviewState.Idle -> Box(Modifier.fillMaxSize().padding(padding))
            is ImportPreviewState.Loading -> LoadingBody("Parsing import…", padding)
            is ImportPreviewState.Committing -> LoadingBody("Importing…", padding)
            is ImportPreviewState.Applied -> LoadingBody("Imported", padding)
            is ImportPreviewState.Error -> ErrorBody(s, padding)
            is ImportPreviewState.Loaded -> LoadedBody(
                state = s,
                excludedTasks = excludedTasks,
                excludedRisks = excludedRisks,
                onToggleTask = viewModel::toggleTask,
                onToggleRisk = viewModel::toggleRisk,
                padding = padding
            )
        }
    }
}

@Composable
private fun LoadingBody(label: String, padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ErrorBody(state: ImportPreviewState.Error, padding: PaddingValues) {
    val title = when (state.kind) {
        ImportPreviewErrorKind.ReadFailure -> "Couldn't Read Import"
        ImportPreviewErrorKind.Unparseable -> "Couldn't Parse Import"
        ImportPreviewErrorKind.WriteFailure -> "Couldn't Save Import"
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun LoadedBody(
    state: ImportPreviewState.Loaded,
    excludedTasks: Set<Int>,
    excludedRisks: Set<Int>,
    onToggleTask: (Int) -> Unit,
    onToggleRisk: (Int) -> Unit,
    padding: PaddingValues
) {
    val plan = state.plan
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PlanHeader(plan, state.asProject) }

        if (plan is ImportPlan.Rich && plan.phases.isNotEmpty()) {
            item { SectionLabel("Phases (${plan.phases.size})") }
            item {
                ReadOnlyList(
                    titles = plan.phases.map { it.name },
                    emptyHint = null
                )
            }
        }

        val taskTitles = plan.taskTitles
        if (taskTitles.isNotEmpty()) {
            item { SectionLabel("Tasks (${taskTitles.size})") }
            itemsIndexed(taskTitles) { idx, title ->
                ToggleRow(
                    title = title,
                    excluded = idx in excludedTasks,
                    onToggle = { onToggleTask(idx) }
                )
            }
        }

        if (plan is ImportPlan.Rich && plan.result.risks.isNotEmpty()) {
            item { SectionLabel("Risks (${plan.result.risks.size})") }
            itemsIndexed(plan.result.risks) { idx, risk ->
                ToggleRow(
                    title = "[${risk.level.uppercase()}] ${risk.title}",
                    excluded = idx in excludedRisks,
                    onToggle = { onToggleRisk(idx) }
                )
            }
        }

        if (plan is ImportPlan.Rich && plan.externalAnchors.isNotEmpty()) {
            item { SectionLabel("External Anchors (${plan.externalAnchors.size})") }
            item {
                ReadOnlyList(
                    titles = plan.externalAnchors.map { it.title },
                    emptyHint = null
                )
            }
        }

        if (plan is ImportPlan.Rich && plan.taskDependencies.isNotEmpty()) {
            item { SectionLabel("Task Dependencies (${plan.taskDependencies.size})") }
            item {
                ReadOnlyList(
                    titles = plan.taskDependencies.map { "${it.blockerTitle} → ${it.blockedTitle}" },
                    emptyHint = null
                )
            }
        }

        if (taskTitles.isEmpty() && (plan !is ImportPlan.Rich || plan.result.risks.isEmpty())) {
            item {
                Text(
                    "Nothing to import — refine the source and try again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PlanHeader(plan: ImportPlan, asProject: Boolean) {
    val subtitle = when (plan) {
        is ImportPlan.Rich -> "Project import — ${plan.taskTitles.size} task${plural(plan.taskTitles.size)}, " +
            "${plan.phases.size} phase${plural(plan.phases.size)}, ${plan.result.risks.size} risk${plural(plan.result.risks.size)}"
        is ImportPlan.FlatProject -> "Project import — ${plan.taskTitles.size} task${plural(plan.taskTitles.size)}"
        is ImportPlan.FlatOrphans ->
            if (asProject) {
                "Orphan tasks — ${plan.taskTitles.size} task${plural(plan.taskTitles.size)}"
            } else {
                "Orphan tasks — ${plan.taskTitles.size} task${plural(plan.taskTitles.size)} (no project)"
            }
    }
    Column {
        Text(plan.projectName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    excluded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = !excluded, onCheckedChange = { onToggle() })
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun ReadOnlyList(titles: List<String>, emptyHint: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (titles.isEmpty()) {
                if (emptyHint != null) Text(emptyHint, style = MaterialTheme.typography.bodySmall)
            } else {
                for (t in titles) {
                    Text("• $t", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: ImportPreviewState,
    onCancel: () -> Unit,
    onApprove: () -> Unit
) {
    Surface(tonalElevation = 4.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                val approveEnabled = state is ImportPreviewState.Loaded
                Button(enabled = approveEnabled, onClick = onApprove) { Text("Import") }
            }
        }
    }
}
