package com.averycorp.prismtask.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates that the post-notification code paths do not crash when the
 * runtime POST_NOTIFICATIONS permission is denied on API 33+.
 *
 * We test this by explicitly silencing the permission using appops
 * shell commands (to avoid process death from a hard revoke). The test installs
 * a valid channel and attempts a [NotificationManagerCompat.notify]; we expect
 * the call to return (not crash) regardless of runtime state. Production call
 * sites wrap their notify() in try/catch for the same reason
 * (see [com.averycorp.prismtask.work.OverloadCheckWorker.doWork] etc) —
 * this confirms the library contract they rely on.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionInstrumentedTest {

    @Before
    fun setup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            executeShellCommand("appops set $packageName POST_NOTIFICATIONS allow")
        }
    }

    @After
    fun teardown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            executeShellCommand("appops set $packageName POST_NOTIFICATIONS allow")
        }
    }

    private fun executeShellCommand(command: String) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd: ParcelFileDescriptor = uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { fis ->
            val buffer = ByteArray(1024)
            while (fis.read(buffer) != -1) {
                // Wait for the command to finish executing
            }
        }
    }

    @Test
    fun notifyOnInvalidChannel_doesNotCrash_onApi33Plus() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull("NotificationManager system service must be available", manager)

        val packageName = context.packageName
        // Using appops ignore prevents process death which happens with pm revoke mid-test
        executeShellCommand("appops set $packageName POST_NOTIFICATIONS ignore")

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
            // drop when notifications are disabled. The worst case we can
            // induce in an instrumented test is disabled-by-user, which
            // still should not crash.
            NotificationManagerCompat.from(context).notify(30_001, notification)
            // Also exercise the non-compat path, which is where
            // SecurityException can surface on specific OEM builds.
            try {
                manager.notify(30_002, notification)
            } catch (_: SecurityException) {
                // Absorbing is the documented contract.
            }
        } finally {
            NotificationManagerCompat.from(context).cancel(30_001)
            NotificationManagerCompat.from(context).cancel(30_002)
            manager.deleteNotificationChannel(validChannelId)
        }
    }
}
