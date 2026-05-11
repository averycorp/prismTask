package com.averycorp.prismtask.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.SyllabusConfirmRequest
import com.averycorp.prismtask.data.remote.api.SyllabusConfirmResponse
import com.averycorp.prismtask.data.remote.api.SyllabusParseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyllabusRepository
@Inject
constructor(private val api: PrismTaskApi) {
    suspend fun parseSyllabus(uri: Uri, context: Context): SyllabusParseResponse {
        val bytes = withContext(Dispatchers.IO) {
            // Check declared size before reading to reject oversized files early
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        val size = cursor.getLong(sizeIndex)
                        if (size > MAX_FILE_SIZE) throw FileTooLargeException()
                    }
                }
            }

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error("Could not open file")
            // Stream with a hard cap for providers that don't report size
            inputStream.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var totalRead = 0L
                while (true) {
                    val n = stream.read(chunk)
                    if (n == -1) break
                    totalRead += n
                    if (totalRead > MAX_FILE_SIZE) throw FileTooLargeException()
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
        }

        val requestBody = bytes.toRequestBody("application/pdf".toMediaType())
        val part = MultipartBody.Part.createFormData("file", "syllabus.pdf", requestBody)
        return api.parseSyllabus(part)
    }

    suspend fun confirmSyllabus(request: SyllabusConfirmRequest): SyllabusConfirmResponse = api.confirmSyllabus(request)

    companion object {
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10 MB
    }
}

class FileTooLargeException : Exception("PDF must be under 10MB")
