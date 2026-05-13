package com.averycorp.prismtask.data.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.remote.api.FileContactResponse
import com.averycorp.prismtask.data.remote.api.FileExtractedSubtaskResponse
import com.averycorp.prismtask.data.remote.api.FileExtractionResponse
import com.averycorp.prismtask.data.remote.api.FileTechnicalMetadataResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.model.FileExtractionSuggestion
import com.averycorp.prismtask.domain.model.LifeCategory
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts an arbitrary file to `/api/v1/ai/files/extract` and maps the
 * backend's `FileExtractionResponse` into a UI-ready
 * [FileExtractionSuggestion].
 *
 * Returns a [Result] so callers can distinguish "nothing actionable in
 * the file" (success with [FileExtractionSuggestion.hasAnyContent] = false)
 * from "we never reached the backend" (failure). Failure causes:
 *   - not signed in (`Result.failure(NotAuthenticatedException)`),
 *   - backend rejected the file (413 too large, 422 unparseable),
 *   - generic IO / network errors.
 */
@Singleton
class FileExtractionService
@Inject
constructor(
    private val api: PrismTaskApi,
    private val authTokenPreferences: AuthTokenPreferences
) {
    /**
     * The backend caps uploads at 10 MB; mirror that here so callers can
     * short-circuit before reading the whole file into memory.
     */
    private val maxBytes: Long = 10L * 1024 * 1024

    /**
     * Read the file at [sourceUri] and POST it for extraction.
     *
     * `displayName` and `mimeType` overrides exist for callers that have
     * already resolved them (e.g. the AddEditTask flow that just attached
     * the file). Otherwise we query ContentResolver.
     */
    suspend fun extract(
        context: Context,
        sourceUri: Uri,
        displayName: String? = null,
        mimeType: String? = null
    ): Result<FileExtractionSuggestion> {
        val token = authTokenPreferences.getAccessToken()
        if (token.isNullOrBlank()) {
            return Result.failure(NotAuthenticatedException())
        }

        val resolver = context.contentResolver
        val resolvedMime = mimeType
            ?: resolver.getType(sourceUri)
            ?: "application/octet-stream"
        val resolvedName = displayName ?: queryDisplayName(context, sourceUri) ?: "uploaded-file"

        val bytes = try {
            resolver.openInputStream(sourceUri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > maxBytes) {
                        return Result.failure(
                            FileTooLargeException(maxBytes)
                        )
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return Result.failure(IllegalStateException("Could not open $sourceUri"))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to read $sourceUri", e)
            return Result.failure(e)
        }

        val mediaType = resolvedMime.toMediaTypeOrNull()
        val body: RequestBody = bytes.toRequestBody(mediaType, 0, bytes.size)
        val part = MultipartBody.Part.createFormData("file", resolvedName, body)

        return try {
            val response = api.extractFromFile(part)
            Result.success(FileExtractionSuggestion.fromResponse(response))
        } catch (e: HttpException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "extractFromFile HTTP ${e.code()}", e)
            Result.failure(
                when (e.code()) {
                    413 -> FileTooLargeException(maxBytes)
                    422 -> ExtractionFailedException("File contents could not be parsed")
                    503 -> ExtractionFailedException("File extraction is temporarily unavailable")
                    else -> e
                }
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "extractFromFile failed", e)
            Result.failure(e)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()

    class NotAuthenticatedException :
        IllegalStateException("Sign in to extract task details from files")

    class FileTooLargeException(val maxBytes: Long) :
        IllegalArgumentException("File exceeds the ${maxBytes / (1024 * 1024)} MB limit")

    class ExtractionFailedException(message: String) : RuntimeException(message)

    private companion object {
        const val TAG = "FileExtractionService"
    }
}

/**
 * Map a backend [FileExtractionResponse] into the UI-ready
 * [FileExtractionSuggestion]. ISO date strings are parsed to epoch
 * millis at end-of-day local time (matching how the rest of the app
 * stores due dates — see `RecurrenceEngine` and `parseDateString` in
 * `ClaudeParserService`).
 */
fun FileExtractionSuggestion.Companion.fromResponse(
    response: FileExtractionResponse
): FileExtractionSuggestion = FileExtractionSuggestion(
    title = response.title.trim(),
    description = response.description?.takeIf { it.isNotBlank() },
    suggestedDueDateMillis = response.suggestedDueDate?.let { parseIsoDateToEndOfDay(it) },
    suggestedPriority = response.suggestedPriority.coerceIn(0, 4),
    suggestedProject = response.suggestedProject?.takeIf { it.isNotBlank() },
    tags = response.tags
        .map { it.trim().trimStart('#') }
        .filter { it.isNotBlank() }
        .distinct(),
    subtasks = response.subtasks
        .mapNotNull { it.toDomainOrNull() },
    detectedDateMillis = response.detectedDates.mapNotNull { parseIsoDateToEndOfDay(it) },
    confidence = response.confidence.coerceIn(0f, 1f),
    notes = response.notes?.takeIf { it.isNotBlank() },
    sourceFileName = response.sourceFileName,
    sourceMimeType = response.sourceMimeType,
    lifeCategory = response.lifeCategory
        ?.takeIf { it.isNotBlank() }
        ?.let { raw ->
            val parsed = LifeCategory.fromStorage(raw)
            // fromStorage returns UNCATEGORIZED for unknown inputs — drop
            // that signal unless the LLM explicitly returned UNCATEGORIZED,
            // so the UI doesn't surface a misleading apply toggle.
            if (parsed == LifeCategory.UNCATEGORIZED && raw != "UNCATEGORIZED") null
            else parsed
        },
    estimatedDurationMinutes = response.estimatedDurationMinutes?.takeIf { it in 0..(24 * 60) },
    recurrenceHint = response.recurrenceHint?.trim()?.takeIf { it.isNotBlank() },
    location = response.location?.trim()?.takeIf { it.isNotBlank() },
    reminderOffsetMinutes = response.reminderOffsetMinutes?.takeIf { it in 0..(30 * 24 * 60) },
    urls = response.urls
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct(),
    contacts = response.contacts.mapNotNull { it.toDomainOrNull() },
    keyEntities = response.keyEntities
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(10),
    documentType = response.documentType?.takeIf { it.isNotBlank() },
    actionOrInfo = response.actionOrInfo?.takeIf { it == "action" || it == "info" },
    language = response.language?.trim()?.takeIf { it.isNotBlank() },
    technicalMetadata = response.technicalMetadata?.toDomain()
)

private fun FileExtractedSubtaskResponse.toDomainOrNull(): FileExtractionSuggestion.Subtask? {
    val cleanTitle = title.trim()
    if (cleanTitle.isEmpty()) return null
    return FileExtractionSuggestion.Subtask(
        title = cleanTitle,
        suggestedDueDateMillis = suggestedDueDate?.let { parseIsoDateToEndOfDay(it) }
    )
}

private fun FileContactResponse.toDomainOrNull(): FileExtractionSuggestion.Contact? {
    val cleanName = name?.trim()?.takeIf { it.isNotBlank() }
    val cleanEmail = email?.trim()?.takeIf { it.isNotBlank() && '@' in it }
    val cleanPhone = phone?.trim()?.takeIf { it.isNotBlank() }
    // Skip entries with no actual contact details — the LLM is asked to
    // omit these but we belt-and-brace here to match the prompt's contract.
    if (cleanEmail == null && cleanPhone == null) return null
    return FileExtractionSuggestion.Contact(
        name = cleanName,
        email = cleanEmail,
        phone = cleanPhone
    )
}

private fun FileTechnicalMetadataResponse.toDomain(): FileExtractionSuggestion.TechnicalMetadata =
    FileExtractionSuggestion.TechnicalMetadata(
        fileSizeBytes = fileSizeBytes?.takeIf { it >= 0 },
        pageCount = pageCount?.takeIf { it >= 0 },
        docTitle = docTitle?.takeIf { it.isNotBlank() },
        docAuthor = docAuthor?.takeIf { it.isNotBlank() },
        docSubject = docSubject?.takeIf { it.isNotBlank() },
        docKeywords = docKeywords?.takeIf { it.isNotBlank() },
        docCreationDate = docCreationDate?.takeIf { it.isNotBlank() },
        docModificationDate = docModificationDate?.takeIf { it.isNotBlank() },
        docLastModifiedBy = docLastModifiedBy?.takeIf { it.isNotBlank() },
        docRevision = docRevision?.takeIf { it >= 0 },
        paragraphCount = paragraphCount?.takeIf { it >= 0 },
        tableCount = tableCount?.takeIf { it >= 0 },
        sheetNames = sheetNames.filter { it.isNotBlank() },
        sheetCount = sheetCount?.takeIf { it >= 0 },
        rowCountTotal = rowCountTotal?.takeIf { it >= 0 },
        widthPx = widthPx?.takeIf { it >= 0 },
        heightPx = heightPx?.takeIf { it >= 0 },
        imageTakenAt = imageTakenAt?.takeIf { it.isNotBlank() },
        cameraMake = cameraMake?.takeIf { it.isNotBlank() },
        cameraModel = cameraModel?.takeIf { it.isNotBlank() },
        gpsLat = gpsLat?.takeIf { it in -90.0..90.0 },
        gpsLon = gpsLon?.takeIf { it in -180.0..180.0 },
        lineCount = lineCount?.takeIf { it >= 0 },
        wordCount = wordCount?.takeIf { it >= 0 },
        charCount = charCount?.takeIf { it >= 0 }
    )

/**
 * Parse a leading `YYYY-MM-DD` from an arbitrary string and resolve it to
 * end-of-day local time (23:59:00.000), matching `ClaudeParserService.kt`.
 * Returns null if the date isn't valid.
 */
internal fun parseIsoDateToEndOfDay(input: String): Long? {
    val match = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(input) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull()?.let { it - 1 } ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    if (month !in 0..11 || day !in 1..31) return null
    val cal = Calendar.getInstance()
    cal.isLenient = false
    cal.clear()
    cal.set(year, month, day, 23, 59, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return try {
        cal.timeInMillis
    } catch (_: IllegalArgumentException) {
        null
    }
}
