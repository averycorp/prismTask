package com.averycorp.prismtask.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

/**
 * Plays the system alarm sound at a fixed volume that the user pins in
 * Settings, regardless of where they left the volume rocker. The standard
 * notification channel honors `USAGE_ALARM` but still rides the user's live
 * alarm-stream level — this helper momentarily pins the alarm stream to the
 * configured target, plays the sound, then restores the saved volume when
 * playback finishes so the user's normal level isn't permanently altered.
 *
 * Errors at every step (no MediaPlayer, no default alarm URI, missing
 * permission) are swallowed: the timer completion notification still posts
 * via the regular channel, the user just loses the volume override.
 */
internal object TimerAlarmPlayer {

    private const val TAG = "TimerAlarmPlayer"

    fun play(context: Context, targetPercent: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            Log.w(TAG, "AudioManager unavailable; skipping volume override")
            return
        }

        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: run {
                Log.w(TAG, "No default alarm or notification sound; skipping playback")
                return
            }

        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val savedVol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val targetVol = targetVolumeLevel(targetPercent, maxVol)
        val needsRestore = savedVol != targetVol

        if (needsRestore) {
            // setStreamVolume requires MODIFY_AUDIO_SETTINGS; wrap in try/catch
            // since on devices with restricted volume policies the call can
            // throw a SecurityException at runtime even with the manifest entry.
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot adjust alarm volume: ${e.message}")
            }
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val player = MediaPlayer()
        val restoreAndRelease = Runnable {
            try {
                player.release()
            } catch (_: Throwable) {
                // already released; ignore
            }
            if (needsRestore) {
                try {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0)
                } catch (_: Throwable) {
                    // best-effort restore; nothing we can do if the user revoked perms mid-play
                }
            }
        }

        try {
            player.setAudioAttributes(attrs)
            player.setDataSource(context, uri)
            player.setOnCompletionListener { restoreAndRelease.run() }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                restoreAndRelease.run()
                true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            Log.w(TAG, "Alarm playback failed: ${e.message}")
            restoreAndRelease.run()
        }
    }

    /**
     * Maps a 1-100 user percent onto the device's STREAM_ALARM scale. A
     * percent at or below zero would silently mute the alarm — clamp to at
     * least 1/maxVol so the user always hears something even if they slid
     * the slider to zero.
     */
    internal fun targetVolumeLevel(percent: Int, maxVol: Int): Int {
        if (maxVol <= 0) return 0
        val clamped = percent.coerceIn(1, 100)
        val computed = (maxVol.toLong() * clamped / 100L).toInt()
        return computed.coerceAtLeast(1).coerceAtMost(maxVol)
    }
}
