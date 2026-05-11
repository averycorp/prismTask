package com.averycorp.prismtask.notifications

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.averycorp.prismtask.domain.model.notifications.NotificationProfile
import com.averycorp.prismtask.domain.model.notifications.VibrationIntensity
import com.averycorp.prismtask.domain.model.notifications.VibrationPatterns
import com.averycorp.prismtask.domain.model.notifications.VibrationPreset

/**
 * Turns a [NotificationProfile]'s vibration-preset metadata into the
 * concrete `LongArray` + optional amplitude array consumed by the
 * platform [Vibrator] or attached to a [android.app.NotificationChannel].
 *
 * Separate from [SoundResolver] because vibration has a different
 * lifecycle: it's played directly from [NotificationHelper] on API 26+
 * via the channel, but can also be triggered synchronously from the
 * preview/test UI without going through the notification manager.
 */
object VibrationAdapter {
    /**
     * Resolves the effective pattern for [profile], honoring:
     *   - [VibrationPreset.NONE] → null (no vibration)
     *   - [VibrationPreset.CUSTOM] → parsed CSV from the profile
     *   - Built-in presets → lookup + optional repeat / continuous expansion
     *
     * Returns null when the user has chosen "no vibration" so the caller
     * can skip [Vibrator.vibrate] entirely.
     */
    fun patternFor(profile: NotificationProfile): LongArray? {
        if (profile.vibrationPreset == VibrationPreset.NONE) return null
        val base = if (profile.vibrationPreset == VibrationPreset.CUSTOM) {
            val parsed = VibrationPatterns.decodeCsv(profile.customVibrationPatternCsv)
            if (parsed.isEmpty()) null else parsed
        } else {
            VibrationPatterns.patternFor(profile.vibrationPreset)
        } ?: return null

        val repeatCount = if (profile.vibrationContinuous) 10 else profile.vibrationRepeatCount
        return VibrationPatterns.repeat(base, repeatCount)
    }

    /**
     * Plays [pattern] immediately using the system vibrator. Used by the
     * Preview/Test button in Settings — does not schedule any alarm.
     *
     * Returns false when the device has no vibrator or [pattern] is
     * empty. No-op safe on devices without haptics.
     */
    fun playNow(
        context: Context,
        pattern: LongArray,
        intensity: VibrationIntensity,
        continuous: Boolean = false
    ): Boolean {
        if (pattern.isEmpty()) return false
        val vibrator = resolveVibrator(context) ?: return false
        if (!vibrator.hasVibrator()) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = IntArray(pattern.size) { i ->
                // Index 0 is the initial wait (no vibration); even indices
                // thereafter are also waits, odd indices are vibrations.
                if (i == 0 || i % 2 == 0) 0 else intensity.amplitude
            }
            val repeatIndex = if (continuous) 1 else -1
            val effect = VibrationEffect.createWaveform(pattern, amplitudes, repeatIndex)
            vibrator.vibrate(effect)
            true
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, if (continuous) 0 else -1)
            true
        }
    }

    /** Cancels any in-flight vibration started by [playNow]. */
    fun cancel(context: Context) {
        resolveVibrator(context)?.cancel()
    }

    private fun resolveVibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
}
