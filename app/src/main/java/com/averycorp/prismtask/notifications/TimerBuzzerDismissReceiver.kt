package com.averycorp.prismtask.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.averycorp.prismtask.MainActivity

/**
 * Stops the looping "buzz until dismissed" vibration started by the timer
 * completion notification. Bound to the notification's delete intent (swipe
 * away / clear-all), its "Stop" action, and the content tap so any dismissal
 * path silences the buzz. When [EXTRA_LAUNCH_APP] is true, also opens
 * [MainActivity] so tapping the notification still brings the app forward.
 */
class TimerBuzzerDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VibrationAdapter.cancel(context)
        TimerAlarmPlayer.stop()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(notificationId)
        }
        if (intent.getBooleanExtra(EXTRA_LAUNCH_APP, false)) {
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launch)
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.averycorp.prismtask.timer.BUZZER_DISMISS"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_LAUNCH_APP = "launch_app"

        /** Shared label used in notification bodies and the "Stop" flow. */
        const val BUZZ_BODY_TEXT = "Buzzing until dismissed — tap to stop."

        /**
         * Builds a PendingIntent that fires this receiver. Callers use two
         * flavors per notification — `launchApp = true` for the content tap
         * (so tapping still opens the app) and `launchApp = false` for the
         * delete intent and the "Stop" action. The two flavors need distinct
         * [requestCode]s so `FLAG_UPDATE_CURRENT` doesn't collapse them.
         */
        fun pendingIntent(
            context: Context,
            notificationId: Int,
            launchApp: Boolean,
            requestCode: Int = if (launchApp) notificationId + 1 else notificationId
        ): PendingIntent {
            val intent = Intent(context, TimerBuzzerDismissReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_LAUNCH_APP, launchApp)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
