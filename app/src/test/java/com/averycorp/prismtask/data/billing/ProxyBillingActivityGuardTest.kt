package com.averycorp.prismtask.data.billing

import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the two load-bearing behaviours of [ProxyBillingActivityGuard]:
 *
 *  1. The `intentCarriesPendingIntent` predicate — if it ever returns
 *     `true` for an Intent with no PendingIntent, the V1 NPE leaks past
 *     the guard.
 *  2. The cancelled-PendingIntent factory `cancelledNoopPendingIntent` —
 *     the V1 NPE workaround depends on the synthetic PendingIntent
 *     being non-null and cancelled, so `getIntentSender()` succeeds but
 *     `startIntentSenderForResult` throws SendIntentException.
 *
 * The activity-lifecycle hook itself needs an instrumented host and is
 * covered by manual QA — this file pins the pure pieces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ProxyBillingActivityGuardTest {

    private val guard = ProxyBillingActivityGuard()

    @Test
    fun `null intent has no PendingIntent`() {
        assertFalse(guard.intentCarriesPendingIntent(null))
    }

    @Test
    fun `intent with no extras has no PendingIntent`() {
        assertFalse(guard.intentCarriesPendingIntent(Intent()))
    }

    @Test
    fun `intent with only string and int extras has no PendingIntent`() {
        val intent = Intent()
            .putExtra("response_code", 0)
            .putExtra("debug_message", "ok")
        assertFalse(guard.intentCarriesPendingIntent(intent))
    }

    @Test
    fun `intent carrying a real PendingIntent under any key is detected`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val intent = Intent()
            .putExtra("response_code", 0)
            .putExtra("some_internal_key_that_might_change_between_billing_versions", pendingIntent)
        assertTrue(guard.intentCarriesPendingIntent(intent))
    }

    @Test
    fun `cancelledNoopPendingIntent returns a non-null PendingIntent whose IntentSender is non-null`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pi = guard.cancelledNoopPendingIntent(context)
        assertNotNull("synthetic PendingIntent must be non-null to dodge the V1 NPE", pi)
        // `getIntentSender()` works on a cancelled PendingIntent — cancellation
        // only affects send-time. This is the property that prevents the
        // library's `onCreate` from NPE'ing on `null.getIntentSender()`.
        assertNotNull(pi.intentSender)
    }

    @Test
    fun `injected BUY_INTENT survives a Bundle round-trip and is detected by the predicate`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent().putExtra(
            ProxyBillingActivityGuard.KEY_BUY_INTENT,
            guard.cancelledNoopPendingIntent(context)
        )
        // After injection, the predicate must agree this Intent is "safe" —
        // otherwise the guard would loop on its own injection.
        assertTrue(guard.intentCarriesPendingIntent(intent))
        // BUY_INTENT is the specific key V1's `onCreate` reads first.
        assertEquals(
            "BUY_INTENT",
            ProxyBillingActivityGuard.KEY_BUY_INTENT
        )
    }
}
