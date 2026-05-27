package com.averycorp.prismtask.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationUtilsTest {

    @Test
    fun `validateTaskTitle returns null for valid title`() {
        assertNull(ValidationUtils.validateTaskTitle("Buy groceries"))
    }

    @Test
    fun `validateTaskTitle returns error for blank title`() {
        assertNotNull(ValidationUtils.validateTaskTitle(""))
        assertNotNull(ValidationUtils.validateTaskTitle("   "))
    }

    @Test
    fun `validateTaskTitle returns error for too long title`() {
        val longTitle = "a".repeat(ValidationUtils.MAX_TASK_TITLE_LENGTH + 1)
        assertNotNull(ValidationUtils.validateTaskTitle(longTitle))
    }

    @Test
    fun `validateTaskDescription returns null for null description`() {
        assertNull(ValidationUtils.validateTaskDescription(null))
    }

    @Test
    fun `validateTaskDescription returns null for short description`() {
        assertNull(ValidationUtils.validateTaskDescription("Some notes here"))
    }

    @Test
    fun `validateTaskDescription returns error for too long description`() {
        val longDesc = "x".repeat(ValidationUtils.MAX_TASK_DESCRIPTION_LENGTH + 1)
        assertNotNull(ValidationUtils.validateTaskDescription(longDesc))
    }

    @Test
    fun `validatePriority returns true for valid priorities`() {
        (0..4).forEach { p -> assertTrue(ValidationUtils.validatePriority(p)) }
    }

    @Test
    fun `validatePriority returns false for out-of-range values`() {
        assertFalse(ValidationUtils.validatePriority(-1))
        assertFalse(ValidationUtils.validatePriority(5))
    }

    @Test
    fun `sanitizeTitle trims whitespace`() {
        assertEquals("Hello World", ValidationUtils.sanitizeTitle("  Hello World  "))
    }

    @Test
    fun `validateTagName returns error for blank name`() {
        assertNotNull(ValidationUtils.validateTagName(""))
    }

    @Test
    fun `validateTagName returns null for valid name`() {
        assertNull(ValidationUtils.validateTagName("work"))
    }
}
