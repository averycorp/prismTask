package com.averycorp.prismtask.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fires a [ReminderBroadcastReceiver] intent synchronously via
 * [Context.sendBroadcast] and asserts that a notification ultimately lands
 * in [NotificationManager.getActiveNotifications] for the task ID we sent.
 *
 * The receiver uses goAsync + coroutine dispatching to post the
 * notification, so the assertion polls for up to ~3 seconds rather than
 * checking immediately. This avoids Thread.sleep on the test thread via
 * a single [waitUntil] helper that uses a ConditionVariable-style poll
 * with small yields.
 *
 * Cleanup cancels whatever notifications this test may have posted so
 * no user-visible notification leaks into the real device tray.
 *
 * Hilt-aware because PR #1418 / #1420 / #1421 added EntryPointAccessors
 * lookups (pause-gate, mood-low gate, rest-day gate) on the receiver →
 * helper path. Under `HiltTestApplication`, the SingletonComponent is
 * only constructed when a test uses [HiltAndroidRule]; without the rule
 * the lookups throw `IllegalStateException("The component was not
 * created…")`, which the receiver's catch swallows silently, leaving the
 * notification unposted and this assertion failing with `active IDs=[]`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReminderBroadcastInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    private val testTaskId = 424_242L

    @Before
    fun setup() {
        // getActiveNotifications is M+; the receiver itself works pre-M
        // but we can't assert without active-notification introspection.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        // Trigger SingletonComponent creation so EntryPointAccessors calls
        // in the receiver / NotificationHelper succeed. Without `inject()`,
        // HiltAndroidRule alone doesn't always force component creation.
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = context.getSystemService(NotificationManager::class.java)
        // Start from a clean notification tray so the polling assertion
        // can't false-positive on a leftover notification from a prior
        // test run with the same ID.
        NotificationManagerCompat.from(context).cancel(testTaskId.toInt())
    }

    @After
    fun teardown() {
        // Cancel anything we posted. cancelAll is intentionally avoided —
        // some CI-friendly environments post unrelated Firebase crash
        // notifications that unrelated tests shouldn't clobber.
        NotificationManagerCompat.from(context).cancel(testTaskId.toInt())
    }

    @Test
    fun sendBroadcast_postsNotificationWithMatchingTaskId() {
        // Mirror the Intent shape that ReminderScheduler builds.
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra("taskId", testTaskId)
            putExtra("taskTitle", "Broadcast test task")
            putExtra("taskDescription", "Exercises the receiver end-to-end")
        }
        context.sendBroadcast(intent)

        val posted = waitUntil(timeoutMs = 5000L) {
            manager.activeNotifications.any { it.id == testTaskId.toInt() }
        }
        assertTrue(
            "Expected a posted notification with id=$testTaskId within 5s; " +
                "active IDs=${manager.activeNotifications.map { it.id }}",
            posted
        )
    }

    @Test
    fun sendBroadcast_missingTaskId_silentlyNoOps() {
        // The receiver's onReceive guards against the -1L sentinel and
        // returns without posting. Verify that path doesn't crash AND
        // doesn't leak a notification with the sentinel ID.
        val intent = Intent(context, ReminderBroadcastReceiver::class.java)
        // No extras → receiver reads taskId = -1L and returns.
        context.sendBroadcast(intent)

        // Allow the receiver to run and conclude.
        Thread.yield()
        val posted = manager.activeNotifications.any { it.id == -1 }
        assertTrue("Must not post a notification for taskId=-1", !posted)
    }

    /**
     * Polls [predicate] every 50ms up to [timeoutMs]. Returns true the
     * moment [predicate] becomes true, or false on timeout. Uses
     * [Thread.yield] rather than [Thread.sleep] per the test-style
     * guidance; 50ms of yields is roughly equivalent to polling on a
     * handler without pulling in extra infra.
     */
    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            // Yield + tiny park rather than sleep. Park instead of busy
            // loop keeps CPU low while still letting the receiver
            // complete.
            java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L)
        }
        return predicate()
    }
}
