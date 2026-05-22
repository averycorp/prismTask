package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.remote.api.ExtractFromTextRequest
import com.averycorp.prismtask.data.remote.api.ExtractFromTextResponse
import com.averycorp.prismtask.data.remote.api.ExtractedTaskCandidateResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [NaturalLanguageParser.extractFromText] (Phase B / PR-B
 * of the multi-task creation audit).
 *
 * Covers the 6 cases the audit's PR-B test surface enumerates:
 *  1. Success path returns N parsed candidates with mapped fields.
 *  2. Network failure → regex fallback (still returns something).
 *  3. Malformed response (parse error / empty result) → regex fallback.
 *  4. Empty input → empty list (no API call).
 *  5. Pro gate off → regex fallback (no API call).
 *  6. ≥10K char input is truncated to match the backend Pydantic cap.
 */
class NaturalLanguageParserExtractFromTextTest {

    companion object {
        private const val TODO_PREFIX = "TODO: "
    }

    private val api: PrismTaskApi = mockk()
    private lateinit var parser: NaturalLanguageParser

    @Before
    fun setup() {
        parser = NaturalLanguageParser(api)
    }

    @Test
    fun extractFromText_success_returnsMappedCandidates() = runTest {
        coEvery { api.extractTasksFromText(any()) } returns ExtractFromTextResponse(
            tasks = listOf(
                ExtractedTaskCandidateResponse(
                    title = "Email Bob",
                    suggestedDueDate = "2026-05-01",
                    suggestedPriority = 2,
                    suggestedProject = "Work",
                    confidence = 0.92f
                ),
                ExtractedTaskCandidateResponse(
                    title = "Call Mary",
                    suggestedDueDate = null,
                    suggestedPriority = 0,
                    suggestedProject = null,
                    confidence = 0.81f
                )
            )
        )

        val results = parser.extractFromText("email Bob today, call Mary")

        assertEquals(2, results.size)
        assertEquals("Email Bob", results[0].title)
        assertEquals(2, results[0].suggestedPriority)
        assertEquals("Work", results[0].suggestedProject)
        assertEquals(0.92f, results[0].confidence, 0.0001f)
        val expectedMillis = LocalDate.of(2026, 5, 1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedMillis, results[0].suggestedDueDate)

        assertEquals("Call Mary", results[1].title)
        assertNull(results[1].suggestedDueDate)
        assertNull(results[1].suggestedProject)
        assertEquals(0, results[1].suggestedPriority)
    }

    @Test
    fun extractFromText_networkFailure_fallsBackToRegex() = runTest {
        coEvery { api.extractTasksFromText(any()) } throws IOException("boom")

        val results = parser.extractFromText(
            "${TODO_PREFIX}write the docs\nI'll review the PR tomorrow"
        )

        // Regex extractor matches both "TODO: ..." and "I'll ..." patterns.
        assertTrue(
            "expected regex fallback to extract at least one task, got $results",
            results.isNotEmpty()
        )
        assertTrue(results.any { it.title.contains("docs", ignoreCase = true) })
    }

    @Test
    fun extractFromText_emptyServerResponse_fallsBackToRegex() = runTest {
        // Server returned a well-formed but empty list (e.g. Haiku produced
        // a result that failed validation server-side, or returned 0 tasks).
        // Treat as a soft failure and try regex so the user sees something.
        coEvery { api.extractTasksFromText(any()) } returns ExtractFromTextResponse(
            tasks = emptyList()
        )

        val results = parser.extractFromText("${TODO_PREFIX}ship the feature")

        assertTrue(results.isNotEmpty())
        assertTrue(results[0].title.contains("ship", ignoreCase = true))
    }

    @Test
    fun extractFromText_emptyInput_returnsEmptyAndSkipsApi() = runTest {
        val results = parser.extractFromText("   ")

        assertTrue(results.isEmpty())
        coVerify(exactly = 0) { api.extractTasksFromText(any()) }
    }

    @Test
    fun extractFromText_freeTier_skipsApiAndUsesRegex() = runTest {
        val results = parser.extractFromText(
            input = "${TODO_PREFIX}file the report",
            isProEnabled = { false }
        )

        coVerify(exactly = 0) { api.extractTasksFromText(any()) }
        assertTrue(results.isNotEmpty())
        assertEquals("File the report", results[0].title)
    }

    @Test
    fun extractFromText_truncatesInputAtBackendCap() = runTest {
        val longText = "a".repeat(NaturalLanguageParser.EXTRACT_INPUT_MAX_CHARS + 500)
        var capturedRequest: ExtractFromTextRequest? = null
        coEvery { api.extractTasksFromText(any()) } answers {
            capturedRequest = firstArg()
            ExtractFromTextResponse(
                tasks = listOf(
                    ExtractedTaskCandidateResponse(
                        title = "Task",
                        suggestedDueDate = null,
                        suggestedPriority = 0,
                        suggestedProject = null,
                        confidence = 0.5f
                    )
                )
            )
        }

        parser.extractFromText(longText)

        assertNotNull(capturedRequest)
        assertEquals(
            NaturalLanguageParser.EXTRACT_INPUT_MAX_CHARS,
            capturedRequest!!.text.length
        )
    }
}
