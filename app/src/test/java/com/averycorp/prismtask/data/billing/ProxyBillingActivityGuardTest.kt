package com.averycorp.prismtask.data.billing

import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the predicate that decides whether [ProxyBillingActivityGuard]
 * finishes the proxy activity. The full lifecycle path needs an
 * instrumented host, but the predicate is the load-bearing piece — if
 * it ever returns `true` for an Intent with no PendingIntent, the
 * library's NPE leaks straight through the guard.
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
}
