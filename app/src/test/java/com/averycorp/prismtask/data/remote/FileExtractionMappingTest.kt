package com.averycorp.prismtask.data.remote

import com.averycorp.prismtask.data.remote.api.FileExtractedSubtaskResponse
import com.averycorp.prismtask.data.remote.api.FileExtractionResponse
import com.averycorp.prismtask.domain.model.FileExtractionSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for the Retrofit DTO -> domain mapping in
 * [FileExtractionService] (the `FileExtractionSuggestion.fromResponse`
 * companion extension lives in the same file).
 *
 * The service itself is untested at this layer because it does ContentResolver
 * I/O — instrumentation tests cover that. The mapping is pure logic and is
 * the bit most likely to silently corrupt data, so it gets the unit-test
 * surface.
 */
class FileExtractionMappingTest {

    @Test
    fun `parses iso date to end-of-day local time`() {
        val response = FileExtractionResponse(
            title = "Pay Bill",
            suggestedDueDate = "2026-05-20"
        )
        val mapped = FileExtractionSuggestion.fromResponse(response)
        assertNotNull(mapped.suggestedDueDateMillis)
        val cal = Calendar.getInstance().apply { timeInMillis = mapped.suggestedDueDateMillis!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, cal.get(Calendar.MONTH))
        assertEquals(20, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `null date stays null`() {
        val mapped = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(title = "X", suggestedDueDate = null)
        )
        assertNull(mapped.suggestedDueDateMillis)
    }

    @Test
    fun `non-iso date string returns null millis`() {
        val mapped = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(title = "X", suggestedDueDate = "tomorrow afternoon")
        )
        assertNull(mapped.suggestedDueDateMillis)
    }

    @Test
    fun `priority is clamped to 0_4`() {
        val low = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(title = "X", suggestedPriority = -3)
        )
        val high = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(title = "X", suggestedPriority = 99)
        )
        assertEquals(0, low.suggestedPriority)
        assertEquals(4, high.suggestedPriority)
    }

    @Test
    fun `tags are stripped lowercased and de-duplicated`() {
        val mapped = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(
                title = "X",
                tags = listOf("#work", " work ", "  ", "personal")
            )
        )
        // De-dup is case-sensitive on the trimmed value (matches backend output).
        assertEquals(listOf("work", "personal"), mapped.tags)
    }

    @Test
    fun `subtasks with blank titles are dropped`() {
        val mapped = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(
                title = "X",
                subtasks = listOf(
                    FileExtractedSubtaskResponse(title = "Real"),
                    FileExtractedSubtaskResponse(title = "  "),
                    FileExtractedSubtaskResponse(title = "Another", suggestedDueDate = "2026-05-15")
                )
            )
        )
        assertEquals(2, mapped.subtasks.size)
        assertEquals("Real", mapped.subtasks[0].title)
        assertEquals("Another", mapped.subtasks[1].title)
        assertNotNull(mapped.subtasks[1].suggestedDueDateMillis)
    }

    @Test
    fun `detected_dates parse all valid iso dates`() {
        val mapped = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(
                title = "X",
                detectedDates = listOf("2026-05-15", "garbage", "2026-06-01")
            )
        )
        assertEquals(2, mapped.detectedDateMillis.size)
    }

    @Test
    fun `description and project are blank-safe`() {
        val mapped = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(
                title = "X",
                description = "   ",
                suggestedProject = "  "
            )
        )
        assertNull(mapped.description)
        assertNull(mapped.suggestedProject)
    }

    @Test
    fun `confidence is clamped to 0_1`() {
        val negative = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(title = "X", confidence = -0.5f)
        )
        val over = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(title = "X", confidence = 5f)
        )
        assertEquals(0f, negative.confidence)
        assertEquals(1f, over.confidence)
    }

    @Test
    fun `hasAnyContent is false for empty suggestion`() {
        val empty = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(title = "")
        )
        assertFalse(empty.hasAnyContent)
    }

    @Test
    fun `hasAnyContent is true when only subtasks are present`() {
        val mapped = FileExtractionSuggestion.fromResponse(
            FileExtractionResponse(
                title = "",
                subtasks = listOf(FileExtractedSubtaskResponse(title = "Do The Thing"))
            )
        )
        assertTrue(mapped.hasAnyContent)
    }

    @Test
    fun `parseIsoDateToEndOfDay rejects invalid month`() {
        assertNull(parseIsoDateToEndOfDay("2026-13-01"))
        assertNull(parseIsoDateToEndOfDay("2026-00-15"))
        assertNull(parseIsoDateToEndOfDay("2026-05-32"))
        assertNull(parseIsoDateToEndOfDay("2026-05-00"))
    }
}
