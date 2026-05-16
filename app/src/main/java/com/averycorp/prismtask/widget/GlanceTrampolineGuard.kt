package com.averycorp.prismtask.widget

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Runtime guard for the Glance app-widget action trampoline crash.
 *
 * Glance routes widget item taps through one of two trampoline activities:
 *
 *  - `androidx.glance.appwidget.action.InvisibleActionTrampolineActivity`
 *    (LazyColumn / LazyVerticalGrid item taps — uses
 *    `setPendingIntentTemplate` + per-item `fillInIntent`).
 *  - `androidx.glance.appwidget.action.ActionTrampolineActivity` (top-level
 *    widget surface taps).
 *
 * Both activities call `ActionTrampolineKt.launchTrampolineAction(this,
 * intent)`, which reads two extras off the launch Intent:
 *
 *  - `ACTION_INTENT` (the target `Intent` to dispatch).
 *  - `ACTION_TYPE` (the dispatch mode — `ACTIVITY`, `BROADCAST`,
 *    `CALLBACK`, `SERVICE`, or `FOREGROUND_SERVICE`).
 *
 * If `ACTION_INTENT` is missing the trampoline throws
 *
 *     IllegalArgumentException:
 *         List adapter activity trampoline invoked without specifying
 *         target intent.
 *
 * crashing at `ActionTrampoline.kt:93`. Production crash reports for
 * v1.9.39 (#1 top issue, 40 events / 24 users) all hit this path on
 * `InvisibleActionTrampolineActivity` — the per-item `fillInIntent` ships
 * without `ACTION_INTENT` in certain process-death / template-reuse
 * conditions Glance itself does not handle.
 *
 * **Why simply finishing the activity isn't enough:** calling
 * `Activity.finish()` from `Application.ActivityLifecycleCallbacks
 * .onActivityPreCreated` does not skip `onCreate` — it only marks the
 * activity for teardown after `onCreate` returns. The trampoline's
 * `onCreate` is a one-liner that calls `launchTrampolineAction`, which is
 * the crashing function, so `finish()` alone leaves the crash window
 * wide open.
 *
 * This guard repairs the launch Intent before `onCreate` runs:
 *
 *  - Injects `ACTION_INTENT = Intent()` (empty Intent with no component
 *    or action).
 *  - Injects `ACTION_TYPE = "BROADCAST"`.
 *
 * `launchTrampolineAction` then runs the BROADCAST branch, which calls
 * `activity.sendBroadcast(emptyIntent)` — a no-op when no receiver
 * matches the empty Intent. `finish()` then tears the (invisible)
 * trampoline down. The user sees nothing; the rogue tap is silently
 * dropped instead of crashing the process.
 *
 * Coverage is API 29+ via `onActivityPreCreated`. Activity-class matching
 * is by fully qualified name so the file has no compile-time dependency
 * on Glance internals.
 */
internal class GlanceTrampolineGuard : Application.ActivityLifecycleCallbacks {

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        val className = activity.javaClass.name
        if (className != INVISIBLE_TRAMPOLINE && className != VISIBLE_TRAMPOLINE) return
        if (intentCarriesTrampolineExtras(activity.intent)) return
        Log.w(
            TAG,
            "Hardening $className before onCreate — Intent missing ACTION_INTENT or ACTION_TYPE"
        )
        try {
            FirebaseCrashlytics.getInstance().log(
                "GlanceTrampolineGuard hardened $className with missing trampoline extras"
            )
        } catch (_: Throwable) {
            // Crashlytics not available — log to logcat only.
        }
        val intent = activity.intent ?: Intent().also { activity.intent = it }
        intent.putExtra(KEY_ACTION_INTENT, Intent() as Parcelable)
        intent.putExtra(KEY_ACTION_TYPE, ACTION_TYPE_BROADCAST)
        activity.finish()
    }

    internal fun intentCarriesTrampolineExtras(intent: Intent?): Boolean {
        if (intent == null) return false
        val hasTargetIntent = intent.getParcelableExtraCompat<Intent>(KEY_ACTION_INTENT) != null
        val hasType = intent.getStringExtra(KEY_ACTION_TYPE) != null
        return hasTargetIntent && hasType
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        getParcelableExtra(key) as? T

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    internal companion object {
        const val TAG = "GlanceTrampolineGuard"
        const val INVISIBLE_TRAMPOLINE =
            "androidx.glance.appwidget.action.InvisibleActionTrampolineActivity"
        const val VISIBLE_TRAMPOLINE =
            "androidx.glance.appwidget.action.ActionTrampolineActivity"
        const val KEY_ACTION_INTENT = "ACTION_INTENT"
        const val KEY_ACTION_TYPE = "ACTION_TYPE"
        const val ACTION_TYPE_BROADCAST = "BROADCAST"
    }
}
