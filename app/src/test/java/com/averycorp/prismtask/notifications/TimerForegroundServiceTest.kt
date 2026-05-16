package com.averycorp.prismtask.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins [TimerForegroundService] action-routing semantics. The full
 * service lifecycle (foreground notification posting, sound playback,
 * vibration) requires a real Android runtime, so this suite focuses on
 * the contract the service exposes to its callers:
 *
 *  - Every public `start`/`pause`/`resume`/`stop`/`skipBreak` companion
 *    method enqueues a service-start intent on the calling Context.
 *  - The Intent carries the correct action + extras.
 *  - The action namespace is disjoint from [PomodoroTimerService], so
 *    Timer-tab and Smart-Pomodoro flows can't collide on a shared
 *    BroadcastReceiver filter.
 *  - The wire-stable action strings the [com.averycorp.prismtask.ui.screens.timer.TimerViewModel]
 *    BroadcastReceiver filters on are pinned literally so a rename
 *    breaks this test instead of silently desyncing UI from the
 *    service.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TimerForegroundServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `action namespace is disjoint from PomodoroTimerService`() {
        // Catches a regression where a careless copy-paste re-uses the
        // pomodoro action string for the timer service. If that ever
        // happens, both services' BroadcastReceiver filters will catch
        // each other's intents and bleed state across the Timer-tab and
        // Smart-Pomodoro flows.
        val timerActions = setOf(
            TimerForegroundService.ACTION_START,
            TimerForegroundService.ACTION_PAUSE,
            TimerForegroundService.ACTION_RESUME,
            TimerForegroundService.ACTION_STOP,
            TimerForegroundService.ACTION_SKIP_BREAK,
            TimerForegroundService.ACTION_TICK,
            TimerForegroundService.ACTION_PAUSED,
            TimerForegroundService.ACTION_RESUMED,
            TimerForegroundService.ACTION_STOPPED,
            TimerForegroundService.ACTION_SKIPPED,
            TimerForegroundService.ACTION_COMPLETE
        )
        val pomodoroActions = setOf(
            PomodoroTimerService.ACTION_START,
            PomodoroTimerService.ACTION_PAUSE,
            PomodoroTimerService.ACTION_RESUME,
            PomodoroTimerService.ACTION_STOP,
            PomodoroTimerService.ACTION_TICK,
            PomodoroTimerService.ACTION_PAUSED,
            PomodoroTimerService.ACTION_RESUMED,
            PomodoroTimerService.ACTION_STOPPED,
            PomodoroTimerService.ACTION_COMPLETE
        )
        val overlap = timerActions.intersect(pomodoroActions)
        assertTrue(
            "TimerForegroundService and PomodoroTimerService share action strings: $overlap",
            overlap.isEmpty()
        )
    }

    @Test
    fun `start companion method enqueues ACTION_START with extras`() {
        TimerForegroundService.start(
            context = context,
            durationSeconds = 1500,
            sessionIndex = 0,
            totalSessions = 4,
            sessionType = TimerForegroundService.SESSION_TYPE_WORK,
            isLongBreak = false
        )
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull("start() did not enqueue a service intent", started)
        assertEquals(TimerForegroundService.ACTION_START, started!!.action)
        assertEquals(1500, started.getIntExtra(TimerForegroundService.EXTRA_DURATION_SEC, -1))
        assertEquals(0, started.getIntExtra(TimerForegroundService.EXTRA_SESSION_INDEX, -1))
        assertEquals(4, started.getIntExtra(TimerForegroundService.EXTRA_TOTAL_SESSIONS, -1))
        assertEquals(
            TimerForegroundService.SESSION_TYPE_WORK,
            started.getStringExtra(TimerForegroundService.EXTRA_SESSION_TYPE)
        )
        assertFalse(started.getBooleanExtra(TimerForegroundService.EXTRA_IS_LONG_BREAK, true))
    }

    @Test
    fun `start with long break flag carries the boolean through`() {
        TimerForegroundService.start(
            context = context,
            durationSeconds = 900,
            sessionIndex = 3,
            totalSessions = 4,
            sessionType = TimerForegroundService.SESSION_TYPE_LONG_BREAK,
            isLongBreak = true
        )
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(TimerForegroundService.ACTION_START, started!!.action)
        assertEquals(900, started.getIntExtra(TimerForegroundService.EXTRA_DURATION_SEC, -1))
        assertEquals(3, started.getIntExtra(TimerForegroundService.EXTRA_SESSION_INDEX, -1))
        assertEquals(
            TimerForegroundService.SESSION_TYPE_LONG_BREAK,
            started.getStringExtra(TimerForegroundService.EXTRA_SESSION_TYPE)
        )
        assertTrue(started.getBooleanExtra(TimerForegroundService.EXTRA_IS_LONG_BREAK, false))
    }

    @Test
    fun `pause companion method enqueues ACTION_PAUSE`() {
        TimerForegroundService.pause(context)
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(TimerForegroundService.ACTION_PAUSE, started!!.action)
    }

    @Test
    fun `resume companion method enqueues ACTION_RESUME`() {
        TimerForegroundService.resume(context)
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(TimerForegroundService.ACTION_RESUME, started!!.action)
    }

    @Test
    fun `stop companion method enqueues ACTION_STOP`() {
        TimerForegroundService.stop(context)
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(TimerForegroundService.ACTION_STOP, started!!.action)
    }

    @Test
    fun `skipBreak companion method enqueues ACTION_SKIP_BREAK`() {
        TimerForegroundService.skipBreak(context)
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(TimerForegroundService.ACTION_SKIP_BREAK, started!!.action)
    }

    @Test
    fun `companion start with negative duration still enqueues a service intent`() {
        // We don't filter pathological durations at the companion level —
        // the service body coerces and stops self if no time remains.
        // Tests pin the contract that the companion is fire-and-forget.
        TimerForegroundService.start(
            context = context,
            durationSeconds = -1,
            sessionIndex = 0,
            totalSessions = 4,
            sessionType = TimerForegroundService.SESSION_TYPE_WORK,
            isLongBreak = false
        )
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(TimerForegroundService.ACTION_START, started!!.action)
    }

    @Test
    fun `action constants are wire-stable strings`() {
        // BroadcastReceiver IntentFilters in TimerViewModel match on
        // these literal strings. Renaming any constant must update the
        // ViewModel filter in lockstep; if this test fails after a
        // rename, double-check the receiver filter.
        assertEquals("com.averycorp.prismtask.timer.START", TimerForegroundService.ACTION_START)
        assertEquals("com.averycorp.prismtask.timer.PAUSE", TimerForegroundService.ACTION_PAUSE)
        assertEquals("com.averycorp.prismtask.timer.RESUME", TimerForegroundService.ACTION_RESUME)
        assertEquals("com.averycorp.prismtask.timer.STOP", TimerForegroundService.ACTION_STOP)
        assertEquals(
            "com.averycorp.prismtask.timer.SKIP_BREAK",
            TimerForegroundService.ACTION_SKIP_BREAK
        )
        assertEquals("com.averycorp.prismtask.timer.TICK", TimerForegroundService.ACTION_TICK)
        assertEquals(
            "com.averycorp.prismtask.timer.PAUSED",
            TimerForegroundService.ACTION_PAUSED
        )
        assertEquals(
            "com.averycorp.prismtask.timer.RESUMED",
            TimerForegroundService.ACTION_RESUMED
        )
        assertEquals(
            "com.averycorp.prismtask.timer.STOPPED",
            TimerForegroundService.ACTION_STOPPED
        )
        assertEquals(
            "com.averycorp.prismtask.timer.SKIPPED",
            TimerForegroundService.ACTION_SKIPPED
        )
        assertEquals(
            "com.averycorp.prismtask.timer.COMPLETE",
            TimerForegroundService.ACTION_COMPLETE
        )
    }

    @Test
    fun `session types align with PomodoroTimerService values`() {
        // The structural session-type wire format is shared with
        // PomodoroTimerService — automation handlers (SimpleActionHandlers)
        // and existing notifications use the same WORK/BREAK/LONG_BREAK
        // discriminator. Keep them in sync so a future refactor that
        // merges the two services again doesn't need a migration.
        assertEquals("WORK", TimerForegroundService.SESSION_TYPE_WORK)
        assertEquals("BREAK", TimerForegroundService.SESSION_TYPE_BREAK)
        assertEquals("LONG_BREAK", TimerForegroundService.SESSION_TYPE_LONG_BREAK)
    }

    @Test
    fun `consecutive companion dispatches each enqueue a fresh intent`() {
        // Pause then resume — verify the started-service queue gets
        // both intents in order rather than collapsing them.
        TimerForegroundService.pause(context)
        TimerForegroundService.resume(context)
        val app = context as android.app.Application
        val first = shadowOf(app).nextStartedService
        val second = shadowOf(app).nextStartedService
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(TimerForegroundService.ACTION_PAUSE, first!!.action)
        assertEquals(TimerForegroundService.ACTION_RESUME, second!!.action)
        // Drain — confirm we don't have a third leftover intent.
        val third = shadowOf(app).nextStartedService
        assertNull(third)
    }

    @Test
    fun `extra keys are wire-stable strings`() {
        // The widget Start action and the companion `start()` both build
        // intents using these keys; if a rename breaks alignment, the
        // service will read zero/null defaults and the countdown will
        // start with bogus state. Pin them.
        assertEquals("duration_sec", TimerForegroundService.EXTRA_DURATION_SEC)
        assertEquals("session_index", TimerForegroundService.EXTRA_SESSION_INDEX)
        assertEquals("total_sessions", TimerForegroundService.EXTRA_TOTAL_SESSIONS)
        assertEquals("session_type", TimerForegroundService.EXTRA_SESSION_TYPE)
        assertEquals("is_long_break", TimerForegroundService.EXTRA_IS_LONG_BREAK)
        assertEquals("seconds_remaining", TimerForegroundService.EXTRA_SECONDS_REMAINING)
    }
}
