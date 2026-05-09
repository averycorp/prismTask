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

    // ─── Coachmark tour keys (post-onboarding 13-surface walkthrough) ──────

    @Test
    fun coachmark_completed_defaults_to_false() = runTest {
        assertFalse(prefs.coachmarkCompleted().first())
    }

    @Test
    fun coachmark_dismissed_defaults_to_false() = runTest {
        assertFalse(prefs.coachmarkDismissed().first())
    }

    @Test
    fun coachmark_step_index_defaults_to_zero() = runTest {
        assertEquals(0, prefs.coachmarkStepIndex().first())
    }

    @Test
    fun mark_coachmark_completed_flips_flag() = runTest {
        prefs.markCoachmarkCompleted()
        assertTrue(prefs.coachmarkCompleted().first())
    }

    @Test
    fun mark_coachmark_dismissed_flips_flag() = runTest {
        prefs.markCoachmarkDismissed()
        assertTrue(prefs.coachmarkDismissed().first())
    }

    @Test
    fun set_coachmark_step_index_round_trips() = runTest {
        prefs.setCoachmarkStepIndex(7)
        assertEquals(7, prefs.coachmarkStepIndex().first())
    }

    @Test
    fun set_coachmark_step_index_clamps_negative_to_zero() = runTest {
        prefs.setCoachmarkStepIndex(-3)
        assertEquals(0, prefs.coachmarkStepIndex().first())
    }

    @Test
    fun reset_clears_coachmark_keys_too() = runTest {
        prefs.markCoachmarkCompleted()
        prefs.markCoachmarkDismissed()
        prefs.setCoachmarkStepIndex(5)
        prefs.resetTourCard()
        assertFalse(prefs.coachmarkCompleted().first())
        assertFalse(prefs.coachmarkDismissed().first())
        assertEquals(0, prefs.coachmarkStepIndex().first())
    }

    @Test
    fun reset_clears_both_tour_card_and_coachmark_state() = runTest {
        prefs.markEligible()
        prefs.setStepIndex(3)
        prefs.markCoachmarkCompleted()
        prefs.setCoachmarkStepIndex(8)
        prefs.resetTourCard()
        // Existing 5-step Today card keys
        assertFalse(prefs.eligible().first())
        assertEquals(0, prefs.stepIndex().first())
        // New coachmark keys
        assertFalse(prefs.coachmarkCompleted().first())
        assertEquals(0, prefs.coachmarkStepIndex().first())
    }
}
