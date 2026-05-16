package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.TitleLengthLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLengthEnforcerTest {
    @Test
    fun enforce_passesThroughWhenLimitIsNull() {
        val long = "x".repeat(5_000)
        assertEquals(long, TitleLengthEnforcer.enforce(long, limit = null))
    }

    @Test
    fun enforce_passesThroughWhenUnderLimit() {
        assertEquals("abc", TitleLengthEnforcer.enforce("abc", limit = 100))
    }

    @Test
    fun enforce_passesThroughAtExactlyLimit() {
        val atCap = "x".repeat(100)
        assertEquals(atCap, TitleLengthEnforcer.enforce(atCap, limit = 100))
    }

    @Test
    fun enforce_truncatesAboveLimit() {
        val input = "x".repeat(150)
        val capped = TitleLengthEnforcer.enforce(input, limit = 100)
        assertEquals(100, capped.length)
    }

    @Test
    fun enforce_emptyStringIsAlwaysSafe() {
        assertEquals("", TitleLengthEnforcer.enforce("", limit = 100))
        assertEquals("", TitleLengthEnforcer.enforce("", limit = null))
    }

    @Test
    fun extensionFn_delegatesToEnforce() {
        val limit = TitleLengthLimit(50)
        val capped = limit.enforce("y".repeat(80))
        assertEquals(50, capped.length)
    }

    @Test
    fun extensionFn_unlimitedPassesThrough() {
        val unlimited = TitleLengthLimit(null)
        val input = "z".repeat(1_000)
        assertEquals(input, unlimited.enforce(input))
    }

    @Test
    fun shouldShowCounter_falseWhenLimitNull() {
        assertFalse(TitleLengthEnforcer.shouldShowCounter(currentLength = 99, limit = null))
    }

    @Test
    fun shouldShowCounter_falseWhenFarFromLimit() {
        assertFalse(TitleLengthEnforcer.shouldShowCounter(currentLength = 50, limit = 100))
    }

    @Test
    fun shouldShowCounter_trueAtThresholdBoundary() {
        // Within 10 chars of limit (limit - threshold = 90)
        assertTrue(TitleLengthEnforcer.shouldShowCounter(currentLength = 90, limit = 100))
    }

    @Test
    fun shouldShowCounter_trueAboveLimit() {
        assertTrue(TitleLengthEnforcer.shouldShowCounter(currentLength = 100, limit = 100))
    }

    @Test
    fun shouldShowCounter_respectsCustomThreshold() {
        assertFalse(
            TitleLengthEnforcer.shouldShowCounter(
                currentLength = 90,
                limit = 100,
                warningThreshold = 5
            )
        )
        assertTrue(
            TitleLengthEnforcer.shouldShowCounter(
                currentLength = 96,
                limit = 100,
                warningThreshold = 5
            )
        )
    }
}
