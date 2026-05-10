package com.averycorp.prismtask.ui.rating

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Thin wrapper around Google Play's in-app review API. Returns `true` when
 * the prompt was actually launched, `false` when Play Services is
 * unavailable (sideloaded / F-Droid build) or Google rate-limited the
 * request — both surface as exceptions from `requestReviewFlow` /
 * `launchReviewFlow` which are silently absorbed here.
 *
 * Google enforces ~5 prompts/year per user inside the SDK; the helper's
 * 90-day client-side cooldown is a separate "we-don't-want-to-ask-Google
 * -too-often" guard.
 */
@Singleton
class PlayReviewLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun launch(activity: Activity): Boolean {
        return try {
            val manager = ReviewManagerFactory.create(context)
            val info = suspendCancellableCoroutine { cont ->
                manager.requestReviewFlow().addOnCompleteListener { task ->
                    if (task.isSuccessful) cont.resume(task.result)
                    else cont.resume(null)
                }
            } ?: return false
            suspendCancellableCoroutine { cont ->
                manager.launchReviewFlow(activity, info).addOnCompleteListener {
                    cont.resume(Unit)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Play review launch failed", e)
            false
        }
    }

    private companion object {
        const val TAG = "PlayReviewLauncher"
    }
}
