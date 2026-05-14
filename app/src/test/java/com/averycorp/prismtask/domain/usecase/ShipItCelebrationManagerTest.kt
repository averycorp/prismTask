package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.CelebrationIntensity
import com.averycorp.prismtask.data.preferences.NdPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipItCelebrationManagerTest {
    // Explicit ADHD + Calm = off so these F&R-celebration tests don't lean on
    // the (now default-on) baseline that would force LOW intensity via
    // `effectiveCelebrationIntensity` and bias other branches.
    private val frPrefs = NdPreferences(
        adhdModeEnabled = false,
        calmModeEnabled = false,
        focusReleaseModeEnabled = true,
        shipItCelebrationsEnabled = true,
        celebrationIntensity = CelebrationIntensity.MEDIUM
    )

    @Test
    fun `returns null when FR mode is off`() {
        val prefs = frPrefs.copy(focusReleaseModeEnabled = false)
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            prefs
        )
        assertNull(result)
    }

    @Test
    fun `returns null when celebrations disabled`() {
        val prefs = frPrefs.copy(shipItCelebrationsEnabled = false)
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            prefs
        )
        assertNull(result)
    }

    @Test
    fun `normal completion uses MEDIUM intensity by default`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            frPrefs
        )
        assertNotNull(result)
        assertEquals(CelebrationIntensity.MEDIUM, result!!.intensity)
        assertEquals(CelebrationTrigger.NORMAL_COMPLETION, result.trigger)
    }

    @Test
    fun `good enough ship returns correct trigger`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.GOOD_ENOUGH_SHIP,
            frPrefs
        )
        assertNotNull(result)
        assertEquals(CelebrationTrigger.GOOD_ENOUGH_SHIP, result!!.trigger)
    }

    @Test
    fun `resisted rework returns correct trigger`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.RESISTED_REWORK,
            frPrefs
        )
        assertNotNull(result)
        assertEquals(CelebrationTrigger.RESISTED_REWORK, result!!.trigger)
    }

    @Test
    fun `locked at max revisions returns correct trigger`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.LOCKED_AT_MAX_REVISIONS,
            frPrefs
        )
        assertNotNull(result)
        assertEquals(CelebrationTrigger.LOCKED_AT_MAX_REVISIONS, result!!.trigger)
    }

    @Test
    fun `calm mode forces LOW intensity`() {
        val prefs = frPrefs.copy(calmModeEnabled = true, celebrationIntensity = CelebrationIntensity.HIGH)
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            prefs
        )
        assertNotNull(result)
        assertEquals(CelebrationIntensity.LOW, result!!.intensity)
    }

    @Test
    fun `messages rotate - not always the same`() {
        val messages = (1..20)
            .mapNotNull {
                ShipItCelebrationManager
                    .createCelebration(
                        CelebrationTrigger.NORMAL_COMPLETION,
                        frPrefs
                    )?.message
            }.toSet()
        // With 5 possible messages, over 20 iterations we should see at least 2 different ones
        assertTrue("Expected message rotation but got: $messages", messages.size >= 2)
    }

    @Test
    fun `streak milestone at 3 days`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            frPrefs,
            releaseStreakDays = 3
        )
        assertNotNull(result)
        assertTrue(result!!.isStreakMilestone)
        assertEquals(3, result.streakDays)
    }

    @Test
    fun `streak milestone at 7 days`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            frPrefs,
            releaseStreakDays = 7
        )
        assertTrue(result!!.isStreakMilestone)
    }

    @Test
    fun `streak milestone at 14 days`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            frPrefs,
            releaseStreakDays = 14
        )
        assertTrue(result!!.isStreakMilestone)
    }

    @Test
    fun `streak milestone at 30 days`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            frPrefs,
            releaseStreakDays = 30
        )
        assertTrue(result!!.isStreakMilestone)
    }

    @Test
    fun `non-milestone day is not a milestone`() {
        val result = ShipItCelebrationManager.createCelebration(
            CelebrationTrigger.NORMAL_COMPLETION,
            frPrefs,
            releaseStreakDays = 5
        )
        assertNotNull(result)
        assertFalse(result!!.isStreakMilestone)
    }

    @Test
    fun `shouldFireInsteadOfAdhd true when FR active with celebrations`() {
        assertTrue(ShipItCelebrationManager.shouldFireInsteadOfAdhd(frPrefs))
    }

    @Test
    fun `shouldFireInsteadOfAdhd false when FR off`() {
        val prefs = frPrefs.copy(focusReleaseModeEnabled = false)
        assertFalse(ShipItCelebrationManager.shouldFireInsteadOfAdhd(prefs))
    }

    @Test
    fun `shouldFireInsteadOfAdhd false when celebrations disabled`() {
        val prefs = frPrefs.copy(shipItCelebrationsEnabled = false)
        assertFalse(ShipItCelebrationManager.shouldFireInsteadOfAdhd(prefs))
    }

    @Test
    fun `each intensity level produces correct result`() {
        CelebrationIntensity.entries.forEach { intensity ->
            val prefs = frPrefs.copy(celebrationIntensity = intensity)
            val result = ShipItCelebrationManager.createCelebration(
                CelebrationTrigger.NORMAL_COMPLETION,
                prefs
            )
            assertNotNull(result)
            assertEquals(intensity, result!!.intensity)
        }
    }
}
