package com.averycorp.prismtask.data.billing

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Runtime guard for Google Play Billing's `ProxyBillingActivity` NPE.
 *
 * The V1 library's `onCreate` reads `BUY_INTENT` (or `IN_APP_MESSAGE_INTENT`)
 * as a Parcelable PendingIntent and then unconditionally calls
 * `getIntentSender()` on it — when neither key is present (or both values
 * are null), the local variable holding the PendingIntent stays null and
 * the activity crashes with
 *
 *     NullPointerException: Attempt to invoke virtual method
 *     'android.content.IntentSender android.app.PendingIntent.getIntentSender()'
 *     on a null object reference
 *     at com.android.billingclient.api.ProxyBillingActivity.onCreate
 *
 * The Intent can arrive without the PendingIntent in three known cases the
 * manifest hardening (`noHistory` + `excludeFromRecents`, PR #1205 / #1461)
 * does not cover:
 *
 *  - Process-death restoration while the proxy was foreground — the task is
 *    rebuilt on next launch and the original extra was never persisted.
 *  - A malformed launch response from Google Play (rare, observed in the
 *    wild).
 *  - Configuration-change paths the library's own `android:configChanges`
 *    absorber does not mask.
 *
 * **Why the previous `finish()`-only approach didn't work:** an earlier
 * version of this guard called `activity.finish()` from
 * `onActivityPreCreated` and assumed that skipped `onCreate`. It does
 * not — `finish()` here only marks the activity for teardown *after*
 * `onCreate` returns, so the library's `onCreate` still ran and still
 * NPE'd. Crashlytics events from v1.9.39 confirmed this: the guard's log
 * message ("ProxyBillingActivityGuard finished … with missing PendingIntent
 * extra") appears immediately before the crash, in the same session.
 *
 * The current guard repairs the Intent so the library's `onCreate` reaches
 * a code path it knows how to recover from:
 *
 *  - **V1 `ProxyBillingActivity`** — inject a cancelled `PendingIntent`
 *    under the `BUY_INTENT` key. `getIntentSender()` returns a valid
 *    sender (cancellation only affects dispatch), so the NPE is dodged;
 *    `startIntentSenderForResult` then throws
 *    `IntentSender.SendIntentException`, which the library catches inside
 *    `onCreate` and turns into a graceful `finish()` with a
 *    cancel-broadcast.
 *  - **V2 `ProxyBillingActivityV2`** — `onCreate` no-ops via early
 *    `return` when none of its four PendingIntent extras are present, so
 *    the original `finish()` call is sufficient. No crashes from V2 are
 *    observed in production.
 *
 * Coverage is API 29+ via `onActivityPreCreated`; older devices fall back
 * to the manifest hardening. Activity-class matching is by fully qualified
 * name so the file has no compile-time dependency on library internals.
 */
internal class ProxyBillingActivityGuard : Application.ActivityLifecycleCallbacks {

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        val className = activity.javaClass.name
        if (className != PROXY_V1 && className != PROXY_V2) return
        if (intentCarriesPendingIntent(activity.intent)) return
        Log.w(
            TAG,
            "Hardening $className before onCreate — Intent has no PendingIntent extra"
        )
        try {
            FirebaseCrashlytics.getInstance().log(
                "ProxyBillingActivityGuard hardened $className with missing PendingIntent extra"
            )
        } catch (_: Throwable) {
            // Crashlytics not available — log to logcat only.
        }
        if (className == PROXY_V1) {
            injectCancelledBuyIntent(activity)
        }
        activity.finish()
    }

    internal fun intentCarriesPendingIntent(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            if (extras.get(key) is PendingIntent) return true
        }
        return false
    }

    /**
     * Adds a cancelled `BUY_INTENT` PendingIntent to the activity's Intent
     * so the library's `onCreate` takes the BUY_INTENT branch and exits via
     * the `IntentSender.SendIntentException` catch block instead of NPE'ing
     * on `null.getIntentSender()`. The dummy target is a guard-internal
     * action with no registered receiver — even if cancellation did not
     * apply, the sender would resolve to nothing.
     */
    private fun injectCancelledBuyIntent(activity: Activity) {
        val intent = activity.intent ?: Intent().also { activity.intent = it }
        intent.putExtra(KEY_BUY_INTENT, cancelledNoopPendingIntent(activity.applicationContext))
    }

    internal fun cancelledNoopPendingIntent(context: Context): PendingIntent {
        val noop = Intent(NOOP_ACTION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_GUARD_NOOP,
            noop,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        pi.cancel()
        return pi
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    internal companion object {
        const val TAG = "ProxyBillingGuard"
        const val PROXY_V1 = "com.android.billingclient.api.ProxyBillingActivity"
        const val PROXY_V2 = "com.android.billingclient.api.ProxyBillingActivityV2"
        const val KEY_BUY_INTENT = "BUY_INTENT"
        const val NOOP_ACTION = "com.averycorp.prismtask.PROXY_BILLING_GUARD_NOOP"
        // Stable, unlikely-to-collide request code for our sentinel PendingIntent.
        const val REQUEST_CODE_GUARD_NOOP = 0xBADC0DE
    }
}
