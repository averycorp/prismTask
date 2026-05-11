package com.averycorp.prismtask.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.averycorp.prismtask.R
import com.averycorp.prismtask.data.preferences.ProductiveStreakPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surfaces the empathetic broken-streak notification when a productive
 * run resets. Copy comes from [ProductiveStreakPreferences] so the
 * forgiveness-first phrasing is reviewable in one place.
 */
@Singleton
class ProductiveStreakNotifier
@Inject
constructor(@ApplicationContext private val appContext: Context) {
    fun notifyBrokenStreak(brokenLength: Int) {
        if (brokenLength <= 0) return
        ensureChannel()
        val title = ProductiveStreakPreferences.BROKEN_STREAK_NOTIFICATION_TITLE
        val body = ProductiveStreakPreferences.BROKEN_STREAK_NOTIFICATION_BODY
        val notification = NotificationCompat
            .Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silent no-op.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Productive Streak",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Forgiveness-first nudges when a productive-day streak resets"
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "productive_streak"
        const val NOTIFICATION_ID = 9501
    }
}
