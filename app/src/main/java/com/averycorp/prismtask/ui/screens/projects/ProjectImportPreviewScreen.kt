package com.averycorp.prismtask.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.averycorp.prismtask.domain.usecase.ImportOutcome
import com.averycorp.prismtask.domain.usecase.ImportPlan
import com.averycorp.prismtask.domain.usecase.RiskPreviewRow
import com.averycorp.prismtask.domain.usecase.TaskPreviewRow
import com.averycorp.prismtask.ui.theme.LocalPriorityColors
import java.text.DateFormat
import java.util.Date

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

        val taskPreviews = plan.taskPreviews
        if (taskPreviews.isNotEmpty()) {
            item { SectionLabel("Tasks (${taskPreviews.size})") }
            itemsIndexed(taskPreviews) { idx, row ->
                TaskPreviewToggleRow(
                    row = row,
                    excluded = idx in excludedTasks,
                    onToggle = { onToggleTask(idx) }
                )
            }
        }

        val riskPreviews = plan.riskPreviews
        if (riskPreviews.isNotEmpty()) {
            item { SectionLabel("Risks (${riskPreviews.size})") }
            itemsIndexed(riskPreviews) { idx, risk ->
                RiskPreviewToggleRow(
                    row = risk,
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

        if (taskPreviews.isEmpty() && riskPreviews.isEmpty()) {
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
private fun TaskPreviewToggleRow(
    row: TaskPreviewRow,
    excluded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(checked = !excluded, onCheckedChange = { onToggle() })
            Column(
                modifier = Modifier.padding(start = 8.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TaskTitleLine(row)
                TaskMetadataRow(row)
                if (!row.description.isNullOrBlank()) {
                    Text(
                        row.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.tags.isNotEmpty()) {
                    TagChipsRow(row.tags)
                }
                if (row.subtasks.isNotEmpty()) {
                    SubtaskTree(row.subtasks, depth = 1)
                }
            }
        }
    }
}

@Composable
private fun TaskTitleLine(row: TaskPreviewRow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PriorityDot(row.priority)
        Text(
            row.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textDecoration = if (row.completed) TextDecoration.LineThrough else null,
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = true)
        )
        if (row.completed) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Already completed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun TaskMetadataRow(row: TaskPreviewRow) {
    val bits = buildList {
        if (row.priority > 0) add(priorityLabel(row.priority))
        row.dueDate?.let { add("Due ${formatDate(it)}") }
        row.estimatedMinutes?.let { add("~$it min") }
        row.phaseName?.let { add("Phase: $it") }
    }
    if (bits.isEmpty()) return
    Text(
        bits.joinToString("  •  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PriorityDot(priority: Int) {
    val color = LocalPriorityColors.current.forLevel(priority)
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color = color, shape = CircleShape)
    )
}

@Composable
private fun TagChipsRow(tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    "#$tag",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SubtaskTree(subtasks: List<TaskPreviewRow>, depth: Int) {
    Column(
        modifier = Modifier.padding(start = (12 * depth).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        subtasks.forEach { sub ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityDot(sub.priority)
                Text(
                    "↳ ${sub.title}",
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = if (sub.completed) TextDecoration.LineThrough else null,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            val subBits = buildList {
                if (sub.priority > 0) add(priorityLabel(sub.priority))
                sub.dueDate?.let { add("Due ${formatDate(it)}") }
                sub.estimatedMinutes?.let { add("~$it min") }
            }
            if (subBits.isNotEmpty()) {
                Text(
                    subBits.joinToString("  •  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            if (sub.subtasks.isNotEmpty()) {
                SubtaskTree(sub.subtasks, depth = depth + 1)
            }
        }
    }
}

@Composable
private fun RiskPreviewToggleRow(
    row: RiskPreviewRow,
    excluded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(checked = !excluded, onCheckedChange = { onToggle() })
            Column(
                modifier = Modifier.padding(start = 8.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskLevelChip(row.level)
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (!row.description.isNullOrBlank()) {
                    Text(
                        row.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskLevelChip(level: String) {
    val normalized = level.uppercase()
    val color = when (normalized) {
        "HIGH" -> Color(0xFFD4534A)
        "MEDIUM" -> Color(0xFFF59E0B)
        "LOW" -> Color(0xFF4A90D9)
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color) {
        Text(
            normalized,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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

private fun priorityLabel(priority: Int): String = when (priority) {
    1 -> "Low"
    2 -> "Medium"
    3 -> "High"
    4 -> "Urgent"
    else -> "None"
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
