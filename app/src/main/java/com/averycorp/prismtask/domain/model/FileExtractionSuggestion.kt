package com.averycorp.prismtask.domain.model

/**
 * Domain-side mirror of the backend's `FileExtractionResponse` — the
 * structured suggestion derived from an uploaded file. Kept separate from
 * the Retrofit DTO so UI/tests don't pull in `Gson`/`Retrofit` types and
 * dates are typed (epoch millis) rather than ISO strings.
 *
 * Build via [FileExtractionSuggestion.fromResponse].
 */
data class FileExtractionSuggestion(
    val title: String,
    val description: String?,
    val suggestedDueDateMillis: Long?,
    val suggestedPriority: Int,
    val suggestedProject: String?,
    val tags: List<String>,
    val subtasks: List<Subtask>,
    val detectedDateMillis: List<Long>,
    val confidence: Float,
    val notes: String?,
    val sourceFileName: String?,
    val sourceMimeType: String?
) {
    val hasAnyContent: Boolean
        get() = title.isNotBlank() ||
            !description.isNullOrBlank() ||
            suggestedDueDateMillis != null ||
            suggestedPriority > 0 ||
            !suggestedProject.isNullOrBlank() ||
            tags.isNotEmpty() ||
            subtasks.isNotEmpty()

    data class Subtask(
        val title: String,
        val suggestedDueDateMillis: Long?
    )

    companion object
}
