package com.averycorp.prismtask.domain.model

/**
 * Domain-side mirror of the backend's `FileExtractionResponse` — the
 * structured suggestion derived from an uploaded file. Kept separate from
 * the Retrofit DTO so UI/tests don't pull in `Gson`/`Retrofit` types and
 * dates are typed (epoch millis) rather than ISO strings.
 *
 * Build via [FileExtractionSuggestion.fromResponse].
 *
 * Field groupings:
 *  - Task-shape: [title], [description], [suggestedDueDateMillis],
 *    [suggestedPriority], [suggestedProject], [tags], [subtasks].
 *    Directly applied to the editor draft on user accept.
 *  - Enrichment (LLM-extracted): [lifeCategory], [estimatedDurationMinutes],
 *    [recurrenceHint], [location], [reminderOffsetMinutes], [urls],
 *    [contacts], [keyEntities], [documentType], [actionOrInfo], [language].
 *    Surfaced with their own apply toggles where the field maps onto a task
 *    property, otherwise shown as read-only context.
 *  - File-side: [technicalMetadata] — deterministic metadata captured from
 *    the bytes / parser output before the LLM call. Always informational,
 *    never applied.
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
    val sourceMimeType: String?,
    val lifeCategory: LifeCategory?,
    val estimatedDurationMinutes: Int?,
    val recurrenceHint: String?,
    val location: String?,
    val reminderOffsetMinutes: Int?,
    val urls: List<String>,
    val contacts: List<Contact>,
    val keyEntities: List<String>,
    val documentType: String?,
    val actionOrInfo: String?,
    val language: String?,
    val technicalMetadata: TechnicalMetadata?
) {
    /**
     * True when there is anything in the suggestion the user could
     * meaningfully apply to the task draft. Used by the host screen to
     * decide whether to even show the suggestion sheet.
     */
    val hasAnyContent: Boolean
        get() = title.isNotBlank() ||
            !description.isNullOrBlank() ||
            suggestedDueDateMillis != null ||
            suggestedPriority > 0 ||
            !suggestedProject.isNullOrBlank() ||
            tags.isNotEmpty() ||
            subtasks.isNotEmpty() ||
            lifeCategory != null ||
            estimatedDurationMinutes != null ||
            !recurrenceHint.isNullOrBlank() ||
            !location.isNullOrBlank() ||
            reminderOffsetMinutes != null

    data class Subtask(
        val title: String,
        val suggestedDueDateMillis: Long?
    )

    data class Contact(
        val name: String?,
        val email: String?,
        val phone: String?
    )

    /**
     * Deterministic file-side metadata block. All fields nullable because
     * the answer depends on the file type — only PDFs carry [pageCount],
     * only XLSX carry [sheetNames], only images carry [widthPx]/EXIF.
     */
    data class TechnicalMetadata(
        val fileSizeBytes: Long?,
        val pageCount: Int?,
        val docTitle: String?,
        val docAuthor: String?,
        val docSubject: String?,
        val docKeywords: String?,
        val docCreationDate: String?,
        val docModificationDate: String?,
        val docLastModifiedBy: String?,
        val docRevision: Int?,
        val paragraphCount: Int?,
        val tableCount: Int?,
        val sheetNames: List<String>,
        val sheetCount: Int?,
        val rowCountTotal: Int?,
        val widthPx: Int?,
        val heightPx: Int?,
        val imageTakenAt: String?,
        val cameraMake: String?,
        val cameraModel: String?,
        val gpsLat: Double?,
        val gpsLon: Double?,
        val lineCount: Int?,
        val wordCount: Int?,
        val charCount: Int?
    ) {
        /** True when the block carries anything beyond a bare file size — drives "show File Details panel" gating. */
        val hasRichDetails: Boolean
            get() = pageCount != null ||
                !docTitle.isNullOrBlank() ||
                !docAuthor.isNullOrBlank() ||
                paragraphCount != null ||
                tableCount != null ||
                sheetNames.isNotEmpty() ||
                widthPx != null ||
                !imageTakenAt.isNullOrBlank() ||
                !cameraMake.isNullOrBlank() ||
                gpsLat != null ||
                wordCount != null
    }

    companion object
}
