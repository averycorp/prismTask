package com.averycorp.prismtask.ui.screens.addedittask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.model.FileExtractionSuggestion
import java.text.DateFormat
import java.util.Date

/**
 * Bottom-sheet preview of a backend file-extraction suggestion. Renders
 * one checkbox per non-empty field so the user can pick which pieces to
 * apply to the in-progress task. The sheet never mutates state directly
 * — it returns the user's checkbox set via [onApply].
 *
 * Field rows hide entirely when the suggestion didn't surface that field
 * (e.g. an image with no detectable due date will not show a date row at
 * all), so the sheet stays compact when extraction returned only a title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileImportSuggestionSheet(
    suggestion: FileExtractionSuggestion,
    onDismiss: () -> Unit,
    onApply: (FileImportSelections) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val titleAvailable = suggestion.title.isNotBlank()
    val descriptionAvailable = !suggestion.description.isNullOrBlank()
    val dueDateAvailable = suggestion.suggestedDueDateMillis != null
    val priorityAvailable = suggestion.suggestedPriority > 0
    val projectAvailable = !suggestion.suggestedProject.isNullOrBlank()
    val tagsAvailable = suggestion.tags.isNotEmpty()
    val subtasksAvailable = suggestion.subtasks.isNotEmpty()

    var applyTitle by remember { mutableStateOf(titleAvailable) }
    var applyDescription by remember { mutableStateOf(descriptionAvailable) }
    var applyDueDate by remember { mutableStateOf(dueDateAvailable) }
    var applyPriority by remember { mutableStateOf(priorityAvailable) }
    var applyProject by remember { mutableStateOf(projectAvailable) }
    var applyTags by remember { mutableStateOf(tagsAvailable) }
    var applySubtasks by remember { mutableStateOf(subtasksAvailable) }

    val anyApplyable = titleAvailable || descriptionAvailable || dueDateAvailable ||
        priorityAvailable || projectAvailable || tagsAvailable || subtasksAvailable
    val anySelected = applyTitle || applyDescription || applyDueDate ||
        applyPriority || applyProject || applyTags || applySubtasks

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Extract Details from File",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            val sourceLabel = suggestion.sourceFileName?.takeIf { it.isNotBlank() }
                ?: "uploaded file"
            Text(
                text = "Found in $sourceLabel — pick which fields to apply.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (titleAvailable) {
                SuggestionCheckboxRow(
                    label = "Title",
                    value = suggestion.title,
                    checked = applyTitle,
                    onCheckedChange = { applyTitle = it }
                )
            }
            if (descriptionAvailable) {
                SuggestionCheckboxRow(
                    label = "Description",
                    value = suggestion.description!!,
                    checked = applyDescription,
                    onCheckedChange = { applyDescription = it }
                )
            }
            if (dueDateAvailable) {
                SuggestionCheckboxRow(
                    label = "Due Date",
                    value = formatDate(suggestion.suggestedDueDateMillis!!),
                    checked = applyDueDate,
                    onCheckedChange = { applyDueDate = it }
                )
            }
            if (priorityAvailable) {
                SuggestionCheckboxRow(
                    label = "Priority",
                    value = priorityLabel(suggestion.suggestedPriority),
                    checked = applyPriority,
                    onCheckedChange = { applyPriority = it }
                )
            }
            if (projectAvailable) {
                SuggestionCheckboxRow(
                    label = "Project",
                    value = suggestion.suggestedProject!!,
                    checked = applyProject,
                    onCheckedChange = { applyProject = it }
                )
            }
            if (tagsAvailable) {
                SuggestionCheckboxRow(
                    label = "Tags",
                    value = suggestion.tags.joinToString(", ") { "#$it" },
                    checked = applyTags,
                    onCheckedChange = { applyTags = it }
                )
            }
            if (subtasksAvailable) {
                SuggestionCheckboxRow(
                    label = "Subtasks (${suggestion.subtasks.size})",
                    value = suggestion.subtasks.joinToString("\n") { "• ${it.title}" },
                    checked = applySubtasks,
                    onCheckedChange = { applySubtasks = it }
                )
            }
            if (!anyApplyable) {
                Text(
                    text = "Nothing actionable was extracted from this file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!suggestion.notes.isNullOrBlank()) {
                Text(
                    text = suggestion.notes!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.height(0.dp))
                Button(
                    onClick = {
                        onApply(
                            FileImportSelections(
                                applyTitle = applyTitle && titleAvailable,
                                applyDescription = applyDescription && descriptionAvailable,
                                applyDueDate = applyDueDate && dueDateAvailable,
                                applyPriority = applyPriority && priorityAvailable,
                                applyProject = applyProject && projectAvailable,
                                applyTags = applyTags && tagsAvailable,
                                applySubtasks = applySubtasks && subtasksAvailable
                            )
                        )
                    },
                    enabled = anyApplyable && anySelected
                ) {
                    Text("Apply Selected")
                }
            }
        }
    }
}

@Composable
private fun SuggestionCheckboxRow(
    label: String,
    value: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * What the user picked in the suggestion sheet. The host composable hands
 * this to [AddEditTaskViewModel.applyFileExtractionSuggestion] alongside
 * the original suggestion.
 */
internal data class FileImportSelections(
    val applyTitle: Boolean,
    val applyDescription: Boolean,
    val applyDueDate: Boolean,
    val applyPriority: Boolean,
    val applyProject: Boolean,
    val applyTags: Boolean,
    val applySubtasks: Boolean
)

private fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

private fun priorityLabel(priority: Int): String = when (priority) {
    1 -> "Low"
    2 -> "Medium"
    3 -> "High"
    4 -> "Urgent"
    else -> "None"
}
