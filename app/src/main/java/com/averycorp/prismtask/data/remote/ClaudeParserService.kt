package com.averycorp.prismtask.data.remote

import android.util.Log
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.remote.api.ParseImportRequest
import com.averycorp.prismtask.data.remote.api.ParsedImportItemResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.usecase.ParsedTodoItem
import com.averycorp.prismtask.domain.usecase.ParsedTodoList
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses raw todo-list / JSX content by sending it to the PrismTask backend,
 * which calls Claude Haiku on the server side using the server's API key.
 *
 * Users are never asked to supply their own Claude API key.
 *
 * Returns null (triggering regex fallback in [com.averycorp.prismtask.domain.usecase.TodoListParser])
 * when the user is not logged in or if the backend call fails.
 */
@Singleton
class ClaudeParserService
@Inject
constructor(private val api: PrismTaskApi, private val authTokenPreferences: AuthTokenPreferences) {
    suspend fun parse(content: String): ParsedTodoList? {
        // Require an active session; offline / logged-out users fall back to regex.
        val token = authTokenPreferences.getAccessToken()
        if (token.isNullOrBlank()) return null

        return try {
            val response = api.parseImport(ParseImportRequest(content = content))
            if (response.items.isEmpty()) {
                null
            } else {
                ParsedTodoList(
                    name = response.name,
                    items = response.items.map { it.toParsedTodoItem() }
                )
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("ClaudeParser", "Backend parse failed", e)
            null
        }
    }
}

private fun ParsedImportItemResponse.toParsedTodoItem(): ParsedTodoItem = ParsedTodoItem(
    title = title,
    description = description,
    dueDate = dueDate?.let { parseDateString(it) },
    priority = priority.coerceIn(0, 4),
    completed = completed,
    subtasks = subtasks.map { it.toParsedTodoItem() }
)

private fun parseDateString(dateStr: String): Long? {
    val isoPattern = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    val match = isoPattern.find(dateStr) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = (match.groupValues[2].toIntOrNull() ?: return null) - 1
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val cal = Calendar.getInstance()
    cal.set(year, month, day, 23, 59, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
