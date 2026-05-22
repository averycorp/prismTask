package com.averycorp.prismtask.notifications

import android.app.Instrumentation
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Validates that the post-notification code paths do not crash when the
 * runtime POST_NOTIFICATIONS permission is denied on API 33+.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionInstrumentedTest {

    private fun executeShellCommandSync(instrumentation: Instrumentation, command: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        // Reading the stream to EOF ensures the command finishes executing
        // before we proceed, preventing race conditions. The 'use' block
        // also properly closes the ParcelFileDescriptor.
        FileInputStream(pfd.fileDescriptor).use { fis ->
            fis.readBytes()
        }
    }

    @Test
    fun notifyWithRevokedPermission_doesNotCrash_onApi33Plus() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull("NotificationManager system service must be available", manager)

        // Revoke the POST_NOTIFICATIONS permission via UiAutomator shell command
        executeShellCommandSync(instrumentation, "pm revoke $packageName android.permission.POST_NOTIFICATIONS")

        // Make sure at least one valid channel exists for the test package —
        // notify() on API 26+ silently drops if the channel is missing, not
        // throws, so we want a real channel to prove the happy path works
        // before checking the off-channel path.
        val validChannelId = "__permission_test_channel__"
        val validChannel = NotificationChannel(
            validChannelId,
            "permission test channel",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(validChannel)

        try {
            val notification = NotificationCompat.Builder(context, validChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("perm test")
                .setContentText("Must not crash under any permission state")
                .setAutoCancel(true)
                .build()

            // NotificationManagerCompat.notify is documented to silently
            // drop when notifications are disabled.
            NotificationManagerCompat.from(context).notify(30_001, notification)

            // Also exercise the non-compat path, which throws SecurityException
            // when permissions are revoked.
            try {
                manager.notify(30_002, notification)
            } catch (_: SecurityException) {
                // Absorbing is the documented contract for production call sites.
            }
        } finally {
            // Restore the permission
            executeShellCommandSync(instrumentation, "pm grant $packageName android.permission.POST_NOTIFICATIONS")

            NotificationManagerCompat.from(context).cancel(30_001)
            NotificationManagerCompat.from(context).cancel(30_002)
            manager.deleteNotificationChannel(validChannelId)
        }
    }
}
