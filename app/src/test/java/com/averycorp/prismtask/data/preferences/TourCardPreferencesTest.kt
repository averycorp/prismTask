package com.averycorp.prismtask.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class TourCardPreferencesTest {
    private lateinit var prefs: TourCardPreferences

    @Before
    fun setUp() = runBlocking {
        prefs = TourCardPreferences(ApplicationProvider.getApplicationContext())
        prefs.resetTourCard()
    }

    @Test
    fun eligible_defaults_to_false() = runTest {
        assertFalse(prefs.eligible().first())
    }

    @Test
    fun dismissed_defaults_to_false() = runTest {
        assertFalse(prefs.dismissed().first())
    }

    @Test
    fun stepIndex_defaults_to_zero() = runTest {
        assertEquals(0, prefs.stepIndex().first())
    }

    @Test
    fun markEligible_flipsFlag() = runTest {
        prefs.markEligible()
        assertTrue(prefs.eligible().first())
    }

    @Test
    fun markDismissed_flipsFlag() = runTest {
        prefs.markDismissed()
        assertTrue(prefs.dismissed().first())
    }

    @Test
    fun setStepIndex_roundTrips() = runTest {
        prefs.setStepIndex(3)
        assertEquals(3, prefs.stepIndex().first())
    }

    @Test
    fun setStepIndex_clamps_negative_to_zero() = runTest {
        prefs.setStepIndex(-5)
        assertEquals(0, prefs.stepIndex().first())
    }

    @Test
    fun resetTourCard_clears_all_three_keys() = runTest {
        prefs.markEligible()
        prefs.markDismissed()
        prefs.setStepIndex(4)

        prefs.resetTourCard()

        assertFalse(prefs.eligible().first())
        assertFalse(prefs.dismissed().first())
        assertEquals(0, prefs.stepIndex().first())
    }

    @Test
    fun eligible_and_dismissed_are_independent() = runTest {
        prefs.markEligible()
        prefs.markDismissed()
        assertTrue(prefs.eligible().first())
        assertTrue(prefs.dismissed().first())
    }

    @Test
    fun stepIndex_writes_persist_independently_of_dismissal() = runTest {
        prefs.setStepIndex(2)
        prefs.markDismissed()
        assertEquals(2, prefs.stepIndex().first())
        assertTrue(prefs.dismissed().first())
    }
}
