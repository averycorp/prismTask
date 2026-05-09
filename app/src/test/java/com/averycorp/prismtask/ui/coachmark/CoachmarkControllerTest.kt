package com.averycorp.prismtask.ui.coachmark

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.preferences.TourCardPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * State-machine tests for [CoachmarkController].
 *
 * Coverage:
 *  - Eligibility: ineligible when not flipped; eligible after flip.
 *  - Happy path: tryStart → ShowingStep(0) → next → ShowingStep(1) → ... → Completed.
 *  - Skip semantics: skipThis (advance), dismiss (terminal Dismissed).
 *  - Persistence: stepIndex written on every advance.
 *  - Resume: tryStart honors stored stepIndex.
 *  - Cross-screen: Navigate action → Navigating, then onArrived → ShowingStep.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class CoachmarkControllerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var prefs: TourCardPreferences

    private val tour = listOf(
        CoachmarkStep(
            anchorId = "step0",
            title = "Step 0",
            body = "First",
            action = CoachmarkAction.Next
        ),
        CoachmarkStep(
            anchorId = "step1",
            title = "Step 1",
            body = "Second",
            action = CoachmarkAction.Navigate("route:tasks")
        ),
        CoachmarkStep(
            anchorId = "step2",
            title = "Step 2",
            body = "Third",
            action = CoachmarkAction.Next
        ),
        CoachmarkStep(
            anchorId = "step3",
            title = "Step 3",
            body = "Last",
            action = CoachmarkAction.Finish
        )
    )

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(testDispatcher)
        prefs = TourCardPreferences(ApplicationProvider.getApplicationContext())
        prefs.resetTourCard()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controller(): CoachmarkController =
        CoachmarkController(
            tourCardPreferences = prefs,
            tour = tour,
            scope = TestScope(testDispatcher)
        )

    @Test
    fun tryStart_no_op_when_ineligible() = runTest(testDispatcher) {
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        assertEquals(CoachmarkState.Idle, c.state.value)
    }

    @Test
    fun tryStart_shows_step_zero_when_eligible() = runTest(testDispatcher) {
        prefs.markEligible()
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        val s = c.state.value as CoachmarkState.ShowingStep
        assertEquals(0, s.stepIndex)
        assertEquals("step0", s.step.anchorId)
    }

    @Test
    fun next_advances_when_action_is_Next() = runTest(testDispatcher) {
        prefs.markEligible()
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        // Step 0 has Next action → ShowingStep(1).
        c.next()
        advanceUntilIdle()
        val s = c.state.value as CoachmarkState.ShowingStep
        assertEquals(1, s.stepIndex)
    }

    @Test
    fun next_emits_Navigating_when_action_is_Navigate() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.setCoachmarkStepIndex(1) // start on Navigate step
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.next()
        advanceUntilIdle()
        val s = c.state.value as CoachmarkState.Navigating
        assertEquals(2, s.pendingStepIndex)
        assertEquals("route:tasks", s.routeKey)
    }

    @Test
    fun onArrived_after_navigate_shows_target_step() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.setCoachmarkStepIndex(1)
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.next() // step 1 Navigate → Navigating(2)
        advanceUntilIdle()
        c.onArrived() // → ShowingStep(2)
        advanceUntilIdle()
        val s = c.state.value as CoachmarkState.ShowingStep
        assertEquals(2, s.stepIndex)
    }

    @Test
    fun skipThis_advances_to_next_step() = runTest(testDispatcher) {
        prefs.markEligible()
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.skipThis() // 0 → 1
        advanceUntilIdle()
        val s = c.state.value as CoachmarkState.ShowingStep
        assertEquals(1, s.stepIndex)
    }

    @Test
    fun dismiss_marks_dismissed_and_persists() = runTest(testDispatcher) {
        prefs.markEligible()
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.dismiss()
        advanceUntilIdle()
        assertEquals(CoachmarkState.Dismissed, c.state.value)
        assertTrue(prefs.coachmarkDismissed().first())
    }

    @Test
    fun finish_action_marks_completed() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.setCoachmarkStepIndex(3) // start on Finish step
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.next() // Finish action → Completed
        advanceUntilIdle()
        assertEquals(CoachmarkState.Completed, c.state.value)
        assertTrue(prefs.coachmarkCompleted().first())
    }

    @Test
    fun next_past_last_step_completes() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.setCoachmarkStepIndex(3)
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.next()
        advanceUntilIdle()
        assertEquals(CoachmarkState.Completed, c.state.value)
    }

    @Test
    fun tryStart_resumes_at_persisted_step() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.setCoachmarkStepIndex(2)
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        val s = c.state.value as CoachmarkState.ShowingStep
        assertEquals(2, s.stepIndex)
    }

    @Test
    fun tryStart_no_op_when_completed() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.markCoachmarkCompleted()
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        assertEquals(CoachmarkState.Idle, c.state.value)
    }

    @Test
    fun tryStart_no_op_when_dismissed() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.markCoachmarkDismissed()
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        assertEquals(CoachmarkState.Idle, c.state.value)
    }

    @Test
    fun next_emits_host_event_on_navigate_action() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.setCoachmarkStepIndex(1) // Navigate step
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.next()
        advanceUntilIdle()
        val ev = c.hostEvent.value
        assertNotNull(ev)
        assertTrue(ev is CoachmarkHostEvent.Navigate)
        assertEquals("route:tasks", (ev as CoachmarkHostEvent.Navigate).routeKey)
    }

    @Test
    fun consume_pending_nav_clears_event() = runTest(testDispatcher) {
        prefs.markEligible()
        prefs.setCoachmarkStepIndex(1)
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.next()
        advanceUntilIdle()
        c.consumePendingNav()
        assertNull(c.hostEvent.value)
    }

    @Test
    fun next_on_terminal_state_is_no_op() = runTest(testDispatcher) {
        prefs.markEligible()
        val c = controller()
        c.tryStart()
        advanceUntilIdle()
        c.dismiss()
        advanceUntilIdle()
        c.next() // should not crash, no transition
        advanceUntilIdle()
        assertEquals(CoachmarkState.Dismissed, c.state.value)
    }

    @Test
    fun stepIndex_persists_on_advance() = runTest(testDispatcher) {
        prefs.markEligible()
        val controllerScope = TestScope(testDispatcher)
        val c = CoachmarkController(
            tourCardPreferences = prefs,
            tour = tour,
            scope = controllerScope
        )
        c.tryStart()
        advanceUntilIdle()
        c.next() // 0 → 1 (Next action persists)
        advanceUntilIdle()
        // c.next() persists via scope.launch on the controller's scope;
        // advanceUntilIdle drains the test scheduler but the launched
        // coroutine ultimately suspends on DataStore's IO actor, which
        // completes off-scheduler. Join the controller scope's in-flight
        // children so the write is observed before we read it back.
        controllerScope.coroutineContext.job.children.toList().joinAll()
        assertEquals(1, prefs.coachmarkStepIndex().first())
    }
}
