package com.averycorp.prismtask.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays the user-chosen timer alarm sound, looped for a configurable
 * duration, optionally pinning the alarm stream volume so the timer rings
 * at the user's target level regardless of where the rocker is sitting.
 *
 * Lifecycle:
 *  - [play] is fire-and-forget. It tears down any previous in-flight
 *    playback first so a back-to-back timer completion doesn't pile up two
 *    MediaPlayers on top of each other.
 *  - The player loops the source URI until either (a) [ringSeconds]
 *    elapses, scheduled via a single main-thread [Handler] post; or
 *    (b) [stop] is invoked (notification dismissed / "Stop" tapped).
 *  - When playback ends the saved alarm-stream volume is restored
 *    best-effort. Restore failures are logged and swallowed — leaving the
 *    user's volume slightly off is preferable to crashing the service.
 *
 * Errors at every step (no MediaPlayer, no resolvable URI, missing
 * permission) are swallowed: the timer completion notification still posts
 * via the regular channel, the user just loses the audible alarm.
 */
internal object TimerAlarmPlayer {

    private const val TAG = "TimerAlarmPlayer"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var activePlayer: MediaPlayer? = null
    private var pendingStop: Runnable? = null
    private var pendingRestore: Runnable? = null

    fun play(
        context: Context,
        soundUri: Uri,
        ringSeconds: Int,
        targetPercent: Int,
        pinVolume: Boolean
    ) {
        stop()

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            Log.w(TAG, "AudioManager unavailable; skipping playback")
            return
        }

        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val savedVol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val targetVol = targetVolumeLevel(targetPercent, maxVol)
        val needsRestore = pinVolume && savedVol != targetVol

        if (needsRestore) {
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
        val restore = Runnable {
            if (needsRestore) {
                try {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0)
                } catch (_: Throwable) {
                    // best-effort restore; nothing we can do if perms revoked mid-play
                }
            }
        }

        try {
            player.setAudioAttributes(attrs)
            player.setDataSource(context, soundUri)
            player.isLooping = true
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                stop()
                true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            Log.w(TAG, "Alarm playback failed: ${e.message}")
            try {
                player.release()
            } catch (_: Throwable) {
                // ignore
            }
            restore.run()
            return
        }

        // Schedule the auto-stop on the main thread so a single [stop] call
        // can synchronously cancel it and the player release together.
        val ringMs = ringSeconds.coerceAtLeast(1).toLong() * 1000L
        val stopRunnable = Runnable {
            // Re-entrant from the handler — clear our refs first so [stop]
            // (called from inside the runnable) doesn't try to cancel its
            // own post.
            synchronized(lock) {
                if (activePlayer === player) {
                    activePlayer = null
                    pendingStop = null
                    pendingRestore = null
                }
            }
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Throwable) {
                // ignore — player may already be released
            }
            try {
                player.release()
            } catch (_: Throwable) {
                // ignore
            }
            restore.run()
        }

        synchronized(lock) {
            activePlayer = player
            pendingStop = stopRunnable
            pendingRestore = restore
        }
        mainHandler.postDelayed(stopRunnable, ringMs)
    }

    /**
     * Cancels any active playback started by [play]. Safe to call from any
     * thread and idempotent — extra calls after playback has already ended
     * are no-ops.
     */
    fun stop() {
        val (player, stopRunnable, restore) = synchronized(lock) {
            val triple = Triple(activePlayer, pendingStop, pendingRestore)
            activePlayer = null
            pendingStop = null
            pendingRestore = null
            triple
        }
        if (stopRunnable != null) {
            mainHandler.removeCallbacks(stopRunnable)
        }
        if (player != null) {
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Throwable) {
                // ignore
            }
            try {
                player.release()
            } catch (_: Throwable) {
                // ignore
            }
        }
        restore?.run()
    }

    /**
     * Fallback URI for the timer alarm when a chosen sound cannot be
     * resolved. Prefers the system alarm tone over the notification tone
     * since the timer is alarm-like by intent.
     */
    fun defaultAlarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

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
