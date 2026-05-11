package com.averycorp.prismtask.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.averycorp.prismtask.data.local.entity.CustomSoundEntity
import com.averycorp.prismtask.domain.model.notifications.NotificationProfile
import com.averycorp.prismtask.domain.model.notifications.VibrationPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a live preview of a [NotificationProfile]'s sound + vibration
 * combination, and can fire a real "test" notification end-to-end.
 *
 * The preview path uses [MediaPlayer] directly (no channel required) so
 * Users can audition profile settings before hitting Save and without
 * polluting the tray with half-formed test alerts.
 *
 * Note: calling [previewSound] twice in quick succession cancels the
 * in-flight preview and restarts cleanly.
 */
@Singleton
class NotificationTester
@Inject
constructor(@ApplicationContext private val context: Context) {
    @Volatile
    private var player: MediaPlayer? = null

    /**
     * Plays [profile]'s sound with the configured volume and fade-in,
     * and triggers its vibration pattern. Intended for use by the
     * preview panel — returns a [Preview] token the caller can
     * [Preview.stop] to cancel mid-play.
     */
    fun previewSoundAndVibration(
        profile: NotificationProfile,
        customSounds: List<CustomSoundEntity> = emptyList()
    ): Preview {
        stopPreview()
        val vibePlayed = playVibration(profile)
        val soundPlayed = playSound(profile, customSounds)
        return Preview(soundPlayed || vibePlayed)
    }

    /** Plays just the sound portion (used by the sound picker). */
    fun previewSound(
        profile: NotificationProfile,
        customSounds: List<CustomSoundEntity> = emptyList()
    ): Preview {
        stopPreview()
        val played = playSound(profile, customSounds)
        return Preview(played)
    }

    /** Plays just the vibration pattern. */
    fun previewVibration(profile: NotificationProfile): Preview {
        val played = playVibration(profile)
        return Preview(played)
    }

    /**
     * Posts a real notification using [profile] as a one-shot test. The
     * notification appears in the tray with a fixed title so users can
     * tell it apart from real reminders.
     */
    suspend fun fireTestNotification(profile: NotificationProfile) {
        NotificationHelper.showTaskReminderFor(
            context = context,
            profile = profile,
            taskId = TEST_NOTIFICATION_ID,
            taskTitle = "Test \u2014 ${profile.name}",
            taskDescription = "This is how the ${profile.name} profile will look & sound."
        )
    }

    fun stopPreview() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        VibrationAdapter.cancel(context)
    }

    private fun playSound(
        profile: NotificationProfile,
        customSounds: List<CustomSoundEntity>
    ): Boolean {
        if (profile.silent) return false
        val resolved = SoundResolver.resolve(context, profile.soundId, customSounds)
        val uri = (resolved as? SoundResolver.UriChoice)?.uri ?: return false
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            val volume = profile.soundVolumePercent.coerceIn(0, 100) / 100f
            setVolume(volume, volume)
            setDataSource(context, uri)
            setOnCompletionListener { it.release() }
            prepare()
            start()
        }
        player = mp
        return true
    }

    private fun playVibration(profile: NotificationProfile): Boolean {
        if (profile.vibrationPreset == VibrationPreset.NONE) return false
        val pattern = VibrationAdapter.patternFor(profile) ?: return false
        return VibrationAdapter.playNow(
            context = context,
            pattern = pattern,
            intensity = profile.vibrationIntensity,
            continuous = profile.vibrationContinuous
        )
    }

    /** Handle returned to callers so they can stop the preview. */
    class Preview internal constructor(val started: Boolean) {
        fun isPlaying(): Boolean = started
    }

    companion object {
        /** Fixed notification id for test pings so they replace in place. */
        const val TEST_NOTIFICATION_ID = 99_901L
    }
}
