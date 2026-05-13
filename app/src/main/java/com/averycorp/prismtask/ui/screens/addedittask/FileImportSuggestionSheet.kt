package com.averycorp.prismtask.ui.screens.addedittask

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.HorizontalDivider
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
import com.averycorp.prismtask.domain.model.LifeCategory
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
 *
 * Three visual groups:
 *   1. Task-shape fields (title / description / due / priority / project /
 *      tags / subtasks) — each with an apply toggle.
 *   2. Enrichment fields (life category / duration / location / recurrence
 *      hint / reminder) — each with an apply toggle when the field can be
 *      written to the task draft. Read-only rows for fields the editor
 *      doesn't have a slot for (urls / contacts / key entities / document
 *      type / language).
 *   3. Collapsible "File details" panel rendering the deterministic
 *      file-side metadata (page counts, EXIF, doc-info, etc.). Read-only.
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
    val lifeCategoryAvailable =
        suggestion.lifeCategory != null && suggestion.lifeCategory != LifeCategory.UNCATEGORIZED
    val estimatedDurationAvailable = suggestion.estimatedDurationMinutes != null &&
        suggestion.estimatedDurationMinutes > 0
    val locationAvailable = !suggestion.location.isNullOrBlank()
    val recurrenceHintAvailable = !suggestion.recurrenceHint.isNullOrBlank()
    val reminderOffsetAvailable = suggestion.reminderOffsetMinutes != null &&
        suggestion.reminderOffsetMinutes > 0

    var applyTitle by remember { mutableStateOf(titleAvailable) }
    var applyDescription by remember { mutableStateOf(descriptionAvailable) }
    var applyDueDate by remember { mutableStateOf(dueDateAvailable) }
    var applyPriority by remember { mutableStateOf(priorityAvailable) }
    var applyProject by remember { mutableStateOf(projectAvailable) }
    var applyTags by remember { mutableStateOf(tagsAvailable) }
    var applySubtasks by remember { mutableStateOf(subtasksAvailable) }
    var applyLifeCategory by remember { mutableStateOf(lifeCategoryAvailable) }
    var applyEstimatedDuration by remember { mutableStateOf(estimatedDurationAvailable) }
    var applyLocation by remember { mutableStateOf(locationAvailable) }
    var applyRecurrenceHint by remember { mutableStateOf(false) } // Default off — user must confirm recurrence.
    var applyReminderOffset by remember { mutableStateOf(reminderOffsetAvailable) }

    var detailsExpanded by remember { mutableStateOf(false) }

    val anyApplyable = titleAvailable || descriptionAvailable || dueDateAvailable ||
        priorityAvailable || projectAvailable || tagsAvailable || subtasksAvailable ||
        lifeCategoryAvailable || estimatedDurationAvailable || locationAvailable ||
        recurrenceHintAvailable || reminderOffsetAvailable
    val anySelected = applyTitle || applyDescription || applyDueDate ||
        applyPriority || applyProject || applyTags || applySubtasks ||
        applyLifeCategory || applyEstimatedDuration || applyLocation ||
        applyRecurrenceHint || applyReminderOffset

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

            // --- Task-shape group ---

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

            // --- Enrichment group ---

            if (lifeCategoryAvailable) {
                SuggestionCheckboxRow(
                    label = "Life Category",
                    value = lifeCategoryLabel(suggestion.lifeCategory!!),
                    checked = applyLifeCategory,
                    onCheckedChange = { applyLifeCategory = it }
                )
            }
            if (estimatedDurationAvailable) {
                SuggestionCheckboxRow(
                    label = "Estimated Duration",
                    value = formatDuration(suggestion.estimatedDurationMinutes!!),
                    checked = applyEstimatedDuration,
                    onCheckedChange = { applyEstimatedDuration = it }
                )
            }
            if (locationAvailable) {
                SuggestionCheckboxRow(
                    label = "Location",
                    value = suggestion.location!!,
                    checked = applyLocation,
                    onCheckedChange = { applyLocation = it }
                )
            }
            if (recurrenceHintAvailable) {
                SuggestionCheckboxRow(
                    label = "Recurrence Hint",
                    value = "${suggestion.recurrenceHint!!}\n(Adds to description — confirm before saving)",
                    checked = applyRecurrenceHint,
                    onCheckedChange = { applyRecurrenceHint = it }
                )
            }
            if (reminderOffsetAvailable) {
                SuggestionCheckboxRow(
                    label = "Reminder Offset",
                    value = formatReminderOffset(suggestion.reminderOffsetMinutes!!),
                    checked = applyReminderOffset,
                    onCheckedChange = { applyReminderOffset = it }
                )
            }

            // --- Read-only enrichment context ---

            if (!suggestion.documentType.isNullOrBlank() ||
                !suggestion.actionOrInfo.isNullOrBlank() ||
                !suggestion.language.isNullOrBlank()
            ) {
                ReadOnlyMetadataRow(
                    pairs = buildList {
                        suggestion.documentType?.let { add("Document Type" to documentTypeLabel(it)) }
                        suggestion.actionOrInfo?.let { add("Type" to actionOrInfoLabel(it)) }
                        suggestion.language?.let { add("Language" to it.uppercase()) }
                    }
                )
            }
            if (suggestion.urls.isNotEmpty()) {
                ReadOnlyBlockRow(
                    label = "Links Found (${suggestion.urls.size})",
                    value = suggestion.urls.joinToString("\n") { "• $it" }
                )
            }
            if (suggestion.contacts.isNotEmpty()) {
                ReadOnlyBlockRow(
                    label = "Contacts (${suggestion.contacts.size})",
                    value = suggestion.contacts.joinToString("\n") { contact ->
                        val name = contact.name ?: "Unnamed"
                        val detail = listOfNotNull(contact.email, contact.phone)
                            .joinToString(", ")
                        if (detail.isNotBlank()) "• $name — $detail" else "• $name"
                    }
                )
            }
            if (suggestion.keyEntities.isNotEmpty()) {
                ReadOnlyBlockRow(
                    label = "Mentioned",
                    value = suggestion.keyEntities.joinToString(", ")
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

            // --- Collapsible file-details panel ---

            val tech = suggestion.technicalMetadata
            if (tech != null && tech.hasRichDetails) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                        Text(if (detailsExpanded) "Hide File Details" else "Show File Details")
                    }
                }
                AnimatedVisibility(visible = detailsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        technicalMetadataPairs(tech).forEach { (label, value) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
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
                                applySubtasks = applySubtasks && subtasksAvailable,
                                applyLifeCategory = applyLifeCategory && lifeCategoryAvailable,
                                applyEstimatedDuration = applyEstimatedDuration && estimatedDurationAvailable,
                                applyLocation = applyLocation && locationAvailable,
                                applyRecurrenceHint = applyRecurrenceHint && recurrenceHintAvailable,
                                applyReminderOffset = applyReminderOffset && reminderOffsetAvailable
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

@Composable
private fun ReadOnlyMetadataRow(pairs: List<Pair<String, String>>) {
    if (pairs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pairs.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$label:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyBlockRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
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
    val applySubtasks: Boolean,
    val applyLifeCategory: Boolean = false,
    val applyEstimatedDuration: Boolean = false,
    val applyLocation: Boolean = false,
    val applyRecurrenceHint: Boolean = false,
    val applyReminderOffset: Boolean = false
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

private fun lifeCategoryLabel(category: LifeCategory): String = when (category) {
    LifeCategory.WORK -> "Work"
    LifeCategory.PERSONAL -> "Personal"
    LifeCategory.SELF_CARE -> "Self-Care"
    LifeCategory.HEALTH -> "Health"
    LifeCategory.UNCATEGORIZED -> "Uncategorized"
}

private fun formatDuration(minutes: Int): String = when {
    minutes >= 60 -> {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0) "${h}h" else "${h}h ${m}m"
    }
    else -> "${minutes}m"
}

private fun formatReminderOffset(minutes: Int): String =
    "${formatDuration(minutes)} before due"

private fun documentTypeLabel(raw: String): String =
    raw.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun actionOrInfoLabel(raw: String): String = when (raw) {
    "action" -> "Action Required"
    "info" -> "Informational"
    else -> raw
}

private fun technicalMetadataPairs(
    tech: FileExtractionSuggestion.TechnicalMetadata
): List<Pair<String, String>> = buildList {
    tech.fileSizeBytes?.let { add("Size" to humanFileSize(it)) }
    tech.pageCount?.let { add("Pages" to it.toString()) }
    tech.paragraphCount?.let { add("Paragraphs" to it.toString()) }
    tech.tableCount?.let { if (it > 0) add("Tables" to it.toString()) }
    if (tech.sheetNames.isNotEmpty()) {
        add("Sheets" to tech.sheetNames.joinToString(", "))
    }
    tech.rowCountTotal?.let { if (it > 0) add("Rows" to it.toString()) }
    if (tech.widthPx != null && tech.heightPx != null) {
        add("Dimensions" to "${tech.widthPx} × ${tech.heightPx} px")
    }
    tech.imageTakenAt?.let { add("Taken" to it) }
    if (!tech.cameraMake.isNullOrBlank() || !tech.cameraModel.isNullOrBlank()) {
        add(
            "Camera" to listOfNotNull(tech.cameraMake, tech.cameraModel)
                .joinToString(" ")
        )
    }
    if (tech.gpsLat != null && tech.gpsLon != null) {
        add(
            "GPS" to "%.5f, %.5f".format(tech.gpsLat, tech.gpsLon)
        )
    }
    tech.wordCount?.let { add("Words" to it.toString()) }
    tech.lineCount?.let { add("Lines" to it.toString()) }
    tech.docTitle?.let { add("Document Title" to it) }
    tech.docAuthor?.let { add("Author" to it) }
    tech.docSubject?.let { add("Subject" to it) }
    tech.docKeywords?.let { add("Keywords" to it) }
    tech.docLastModifiedBy?.let { add("Last Modified By" to it) }
    tech.docCreationDate?.let { add("Created" to it) }
    tech.docModificationDate?.let { add("Modified" to it) }
    tech.docRevision?.let { if (it > 0) add("Revision" to it.toString()) }
}

private fun humanFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
