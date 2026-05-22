package com.averycorp.prismtask.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTaskExtractorTest {
    private val extractor = ConversationTaskExtractor()

    @Test
    fun `empty input returns empty list`() {
        assertTrue(extractor.extract("").isEmpty())
    }

    @Test
    fun `over-sized input returns empty list`() {
        val huge = "TODO: something\n".repeat(2000)
        assertTrue(extractor.extract(huge).isEmpty())
    }

    @Test
    fun `explicit TODO marker is extracted`() {
        val result = extractor.extract("TODO: fix the login bug")
        assertEquals(1, result.size)
        assertEquals("Fix the login bug", result.first().title)
        assertTrue(result.first().confidence > 0.9f)
    }

    @Test
    fun `various conversational patterns are extracted`() {
        val testCases = mapOf(
            "Action item: send the report to Alice" to "Send the report to Alice",
            "I should call the dentist tomorrow." to "Call the dentist tomorrow",
            "I need to prepare slides for Monday." to "Prepare slides for Monday",
            "I will review the PR this afternoon." to "Review the PR this afternoon",
            "Let's schedule a follow-up meeting next week." to "Schedule a follow-up meeting next week",
            "Can you review the design mocks?" to "Review the design mocks"
        )

        for ((input, expectedTitle) in testCases) {
            val result = extractor.extract(input)
            assertEquals("Failed on input: $input", 1, result.size)
            assertEquals("Failed on input: $input", expectedTitle, result.first().title)
        }
    }

    @Test
    fun `multiple patterns in same input all extracted`() {
        val text =
            """
            TODO: fix the login bug
            I should call the dentist
            Action item: draft the email
            """.trimIndent()
        val result = extractor.extract(text)
        assertEquals(3, result.size)
        // Results should be sorted by confidence, TODO and action item first.
        assertTrue(result[0].confidence >= result[2].confidence)
    }

    @Test
    fun `duplicate action items are deduped by lowercase`() {
        val text =
            """
            TODO: fix the bug
            I should fix the bug
            """.trimIndent()
        val result = extractor.extract(text)
        // Both resolve to "Fix the bug"; dedupe leaves one.
        assertEquals(1, result.size)
        assertEquals("Fix the bug", result.first().title)
    }

    @Test
    fun `very short title is rejected`() {
        assertTrue(extractor.extract("TODO: x").isEmpty())
    }

    @Test
    fun `conversation without any action items returns empty`() {
        val text = "Hi team, just sharing some thoughts on the design. No pressure."
        assertTrue(extractor.extract(text).isEmpty())
    }

    @Test
    fun `source label propagates to results`() {
        val result = extractor.extract("TODO: ship it", source = "claude")
        assertEquals("claude", result.first().source)
    }
}
