package com.averycorp.prismtask.data.billing

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Defensive runtime guard for Google Play Billing's `ProxyBillingActivity`
 * NPE. The library's `onCreate` reads the launch Intent's PendingIntent
 * extra and calls `getIntentSender()` on it without a null check; when the
 * extra is missing the activity crashes with
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
 * This guard finishes the activity *before* its `onCreate` runs whenever
 * the launch Intent has no `PendingIntent` extra, breaking the crash path.
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
            "Finishing $className before onCreate — Intent has no PendingIntent extra"
        )
        try {
            FirebaseCrashlytics.getInstance().log(
                "ProxyBillingActivityGuard finished $className with missing PendingIntent extra"
            )
        } catch (_: Throwable) {
            // Crashlytics not available — log to logcat only.
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
    }
}
