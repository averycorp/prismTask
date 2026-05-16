package com.averycorp.prismtask.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TimerWidgetState] data class defaults and transformations.
 * Actual DataStore persistence requires an Android Context; these tests
 * validate the pure model layer and the service-write contract (the
 * field shape the foreground service writes to in lifecycle pushes).
 */
class TimerStateDataStoreTest {
    @Test
    fun `default state is not running`() {
        val state = TimerWidgetState()
        assertFalse(state.isRunning)
        assertFalse(state.isPaused)
        assertEquals(0, state.remainingSeconds)
        assertEquals(0, state.totalSeconds)
        assertEquals("work", state.sessionType)
        assertEquals(0, state.currentSession)
        assertEquals(4, state.totalSessions)
        assertNull(state.currentTaskTitle)
    }

    @Test
    fun `running work state`() {
        val state = TimerWidgetState(
            isRunning = true,
            isPaused = false,
            currentTaskTitle = "Write tests",
            remainingSeconds = 1500,
            totalSeconds = 1500,
            sessionType = "work",
            currentSession = 1,
            totalSessions = 4
        )
        assertTrue(state.isRunning)
        assertFalse(state.isPaused)
        assertEquals("Write tests", state.currentTaskTitle)
        assertEquals(1500, state.remainingSeconds)
        assertEquals("work", state.sessionType)
        assertEquals(1, state.currentSession)
    }

    @Test
    fun `paused state`() {
        val state = TimerWidgetState(
            isRunning = false,
            isPaused = true,
            remainingSeconds = 750,
            totalSeconds = 1500,
            sessionType = "work",
            currentSession = 2,
            totalSessions = 4
        )
        assertFalse(state.isRunning)
        assertTrue(state.isPaused)
        assertEquals(750, state.remainingSeconds)
    }

    @Test
    fun `break state`() {
        val state = TimerWidgetState(
            isRunning = true,
            isPaused = false,
            remainingSeconds = 300,
            totalSeconds = 300,
            sessionType = "break",
            currentSession = 2,
            totalSessions = 4
        )
        assertEquals("break", state.sessionType)
        assertTrue(state.isRunning)
    }

    @Test
    fun `state copy preserves fields`() {
        val original = TimerWidgetState(
            isRunning = true,
            remainingSeconds = 1000,
            totalSeconds = 1500,
            sessionType = "work",
            currentSession = 3,
            totalSessions = 4
        )
        val paused = original.copy(isRunning = false, isPaused = true)
        assertFalse(paused.isRunning)
        assertTrue(paused.isPaused)
        assertEquals(1000, paused.remainingSeconds)
        assertEquals(3, paused.currentSession)
    }

    /**
     * Service-write contract: TimerForegroundService's lifecycle pushes
     * only touch run flags + deadline + remaining seconds. Structural
     * fields (mode, session counts, pomodoro flag, task title) stay as
     * the ViewModel last wrote them. The data class default constructor
     * encodes that contract — a "running snapshot built from service
     * writes alone" looks like this.
     */
    @Test
    fun `service-write contract — running snapshot only touches run flags + deadline`() {
        val deadline = 12345L
        val state = TimerWidgetState(
            isRunning = true,
            isPaused = false,
            remainingSeconds = 1450,
            sessionEndElapsedRealtime = deadline
        )
        assertTrue(state.isRunning)
        assertFalse(state.isPaused)
        assertEquals(1450, state.remainingSeconds)
        assertEquals(deadline, state.sessionEndElapsedRealtime)
        // Structural fields land on defaults — caller (ViewModel) is
        // responsible for seeding these via a full write.
        assertEquals(0, state.currentSession)
        assertEquals(4, state.totalSessions)
        assertEquals("work", state.sessionType)
        assertFalse(state.pomodoroEnabled)
        assertFalse(state.isLongBreak)
    }

    /**
     * Pause snapshot: isRunning=false, isPaused=true, deadline zeroed so
     * the widget switches off the self-ticking countdown and renders a
     * frozen remaining-seconds text.
     */
    @Test
    fun `service-write contract — pause snapshot zeros the deadline`() {
        val state = TimerWidgetState(
            isRunning = false,
            isPaused = true,
            remainingSeconds = 720,
            sessionEndElapsedRealtime = 0L
        )
        assertFalse(state.isRunning)
        assertTrue(state.isPaused)
        assertEquals(720, state.remainingSeconds)
        assertEquals(0L, state.sessionEndElapsedRealtime)
    }

    /**
     * Stop / skip / complete snapshot: both flags false, deadline
     * zeroed. The ViewModel then follows up with a fresh totalSeconds
     * write on ACTION_STOPPED so the idle widget shows the right number
     * for the upcoming session.
     */
    @Test
    fun `service-write contract — stop snapshot clears running and paused`() {
        val state = TimerWidgetState(
            isRunning = false,
            isPaused = false,
            remainingSeconds = 0,
            sessionEndElapsedRealtime = 0L
        )
        assertFalse(state.isRunning)
        assertFalse(state.isPaused)
        assertEquals(0L, state.sessionEndElapsedRealtime)
    }

    /**
     * Long-break flag survives copy: the widget reads `isLongBreak` to
     * render the "Long Break" label in Pomodoro mode, so the boolean
     * has to round-trip cleanly through service writes.
     */
    @Test
    fun `long break flag survives copy`() {
        val state = TimerWidgetState(
            isRunning = true,
            sessionType = "break",
            isLongBreak = true,
            pomodoroEnabled = true
        )
        val paused = state.copy(isRunning = false, isPaused = true)
        assertTrue(paused.isLongBreak)
        assertTrue(paused.pomodoroEnabled)
        assertEquals("break", paused.sessionType)
    }
}
