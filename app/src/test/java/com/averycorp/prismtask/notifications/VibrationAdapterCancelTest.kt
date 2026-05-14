package com.averycorp.prismtask.notifications

import android.content.Context
import android.os.Vibrator
import com.averycorp.prismtask.domain.model.notifications.VibrationIntensity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Regression for the "Buzz Until Dismissed" silent cancel: when the timer
 * completion buzz was started from [PomodoroTimerService] and dismissed via
 * [TimerBuzzerDismissReceiver], the vibration kept playing forever.
 *
 * Each Android [Context] has its own service cache, so resolving the
 * vibrator off the Service context vs. the receiver context yields different
 * `SystemVibratorManager` wrappers — each carrying its own `IBinder` token.
 * The platform scopes [Vibrator.cancel] to the calling instance's token,
 * which meant the receiver was cancelling a token that never registered the
 * buzz. The fix routes resolution through [Context.getApplicationContext]
 * so every call site shares the same wrapper.
 */
class VibrationAdapterCancelTest {

    @Test
    fun `cancel from a different Context still cancels the buzz started by playNow`() {
        // The shared "Application-side" vibrator — this is the one both call
        // sites must converge on once they route through applicationContext.
        val sharedVibrator = mockk<Vibrator>(relaxed = true)
        every { sharedVibrator.hasVibrator() } returns true

        val app = mockk<Context>(relaxed = true)
        every { app.getSystemService(Vibrator::class.java) } returns sharedVibrator
        every { app.applicationContext } returns app

        // Per-Context wrappers that would be returned if the adapter ever
        // reverted to resolving off the call-site Context directly. We seed
        // them with distinct vibrator instances so the test fails loudly if
        // the regression returns.
        val perServiceVibrator = mockk<Vibrator>(relaxed = true)
        every { perServiceVibrator.hasVibrator() } returns true
        val serviceContext = mockk<Context>(relaxed = true)
        every { serviceContext.applicationContext } returns app
        every { serviceContext.getSystemService(Vibrator::class.java) } returns perServiceVibrator

        val perReceiverVibrator = mockk<Vibrator>(relaxed = true)
        every { perReceiverVibrator.hasVibrator() } returns true
        val receiverContext = mockk<Context>(relaxed = true)
        every { receiverContext.applicationContext } returns app
        every { receiverContext.getSystemService(Vibrator::class.java) } returns perReceiverVibrator

        VibrationAdapter.playNow(
            context = serviceContext,
            pattern = longArrayOf(0, 100, 100),
            intensity = VibrationIntensity.STRONG,
            continuous = true
        )
        VibrationAdapter.cancel(receiverContext)

        verify { sharedVibrator.cancel() }
        verify(exactly = 0) { perServiceVibrator.cancel() }
        verify(exactly = 0) { perReceiverVibrator.cancel() }
    }
}
