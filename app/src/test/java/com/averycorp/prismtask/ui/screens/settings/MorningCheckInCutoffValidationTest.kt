package com.averycorp.prismtask.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorningCheckInCutoffValidationTest {
    @Test
    fun `cutoff equal to SoD is invalid`() {
        val v = validateMorningCheckInCutoff(sodHour = 4, cutoffHour = 4)
        assertTrue(v is MorningCheckInCutoffValidation.Invalid)
    }

    @Test
    fun `cutoff equal to zero SoD is invalid`() {
        val v = validateMorningCheckInCutoff(sodHour = 0, cutoffHour = 0)
        assertTrue(v is MorningCheckInCutoffValidation.Invalid)
    }

    @Test
    fun `cutoff before small SoD is invalid`() {
        val v = validateMorningCheckInCutoff(sodHour = 4, cutoffHour = 3)
        assertTrue(v is MorningCheckInCutoffValidation.Invalid)
    }

    @Test
    fun `wrap-around case with late SoD and early cutoff is valid`() {
        val v = validateMorningCheckInCutoff(sodHour = 22, cutoffHour = 2)
        assertEquals(MorningCheckInCutoffValidation.Valid, v)
    }

    @Test
    fun `normal forward window is valid`() {
        val v = validateMorningCheckInCutoff(sodHour = 4, cutoffHour = 11)
        assertEquals(MorningCheckInCutoffValidation.Valid, v)
    }

    @Test
    fun `cutoff one hour after SoD is valid`() {
        val v = validateMorningCheckInCutoff(sodHour = 4, cutoffHour = 5)
        assertEquals(MorningCheckInCutoffValidation.Valid, v)
    }

    @Test
    fun `cutoff well after early SoD is valid`() {
        val v = validateMorningCheckInCutoff(sodHour = 0, cutoffHour = 23)
        assertEquals(MorningCheckInCutoffValidation.Valid, v)
    }

    @Test
    fun `wrap-around with noon SoD is valid`() {
        val v = validateMorningCheckInCutoff(sodHour = 12, cutoffHour = 11)
        assertEquals(MorningCheckInCutoffValidation.Valid, v)
    }

    @Test
    fun `invalid reason is non-empty`() {
        val v = validateMorningCheckInCutoff(sodHour = 4, cutoffHour = 4)
        val invalid = v as MorningCheckInCutoffValidation.Invalid
        assertTrue(invalid.reason.isNotBlank())
    }
}
