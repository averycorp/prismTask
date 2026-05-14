package com.averycorp.prismtask.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.domain.model.notifications.BuiltInSound
import com.averycorp.prismtask.domain.model.notifications.VibrationIntensity
import com.averycorp.prismtask.widget.TimerStateDataStore
import com.averycorp.prismtask.widget.TimerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that runs a Pomodoro focus/break countdown. A plain
 * `viewModelScope` coroutine is cancelled as soon as the app is backgrounded,
 * which means the ViewModel's old timer loop never fired the completion
 * notification. Running the countdown inside a foreground service keeps the
 * process alive and guarantees the ongoing + completion notifications are
 * delivered even if the user switches apps or locks the screen.
 *
 * The service emits four kinds of broadcast to the in-app UI so the
 * ViewModel layer can keep its state in sync:
 *  - [ACTION_TICK] every second with [EXTRA_SECONDS_REMAINING]
 *  - [ACTION_PAUSED] when a [ACTION_PAUSE] command is honored
 *  - [ACTION_RESUMED] when an [ACTION_RESUME] command is honored
 *  - [ACTION_COMPLETE] once when the countdown reaches zero
 *
 * Every outbound broadcast carries [EXTRA_OWNER] so multiple consumers
 * (TimerViewModel + SmartPomodoroViewModel) can filter on the start
 * intent's owner and avoid cross-talk when both VMs happen to be alive at
 * once.
 */
class PomodoroTimerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private var secondsRemaining: Int = 0
    private var sessionIndex: Int = 0
    private var sessionType: String = SESSION_TYPE_WORK
    private var owner: String = OWNER_TIMER
    private var isPaused: Boolean = false

    // Absolute SystemClock.elapsedRealtime() at which the current session
    // hits zero. Recomputed on start/resume so RemoteViews' Chronometer
    // can self-tick the countdown in the launcher process.
    private var sessionEndElapsedRealtime: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("PomodoroService", "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startCountdown(intent)
            ACTION_PAUSE -> pauseCountdown()
            ACTION_RESUME -> resumeCountdown()
            ACTION_STOP -> {
                stopCountdown()
                sendBroadcast(
                    Intent(ACTION_STOPPED).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_SECONDS_REMAINING, secondsRemaining)
                        putExtra(EXTRA_SESSION_INDEX, sessionIndex)
                        putExtra(EXTRA_SESSION_TYPE, sessionType)
                        putExtra(EXTRA_OWNER, owner)
                    }
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCountdown(intent: Intent) {
        createChannels(this)
        secondsRemaining = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0)
        sessionIndex = intent.getIntExtra(EXTRA_SESSION_INDEX, 0)
        sessionType = intent.getStringExtra(EXTRA_SESSION_TYPE) ?: SESSION_TYPE_WORK
        owner = intent.getStringExtra(EXTRA_OWNER) ?: OWNER_TIMER
        isPaused = false
        Log.d(
            "PomodoroService",
            "startCountdown: seconds=$secondsRemaining type=$sessionType owner=$owner"
        )

        val notification = buildOngoingNotification(secondsRemaining)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_ONGOING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID_ONGOING, notification)
            }
            Log.d("PomodoroService", "startForeground succeeded")
        } catch (e: Exception) {
            Log.e("PomodoroService", "startForeground FAILED", e)
        }

        runTickLoop()
    }

    private fun runTickLoop() {
        tickJob?.cancel()
        // Stamp the absolute deadline once. The widget reads this on each
        // composition and renders `(deadline - now) / 1000` so the displayed
        // time tracks the wall clock — no per-tick DataStore writes needed
        // for the time itself.
        sessionEndElapsedRealtime = SystemClock.elapsedRealtime() + secondsRemaining * 1000L
        // Fire-and-forget: the lifecycle write goes onto serviceScope so the
        // tick loop below isn't gated on Glance / DataStore work.
        serviceScope.launch {
            pushWidgetRunState(running = true, paused = false)
        }
        tickJob = serviceScope.launch {
            // Emit an initial tick so the in-app UI picks up the starting
            // value even if the user backgrounded the app before the first
            // second elapsed.
            broadcastTick(secondsRemaining)
            while (secondsRemaining > 0) {
                delay(1000)
                if (isPaused) break
                secondsRemaining -= 1
                updateOngoingNotification(secondsRemaining)
                broadcastTick(secondsRemaining)
                // Fire-and-forget the widget refresh on every tick so the
                // home-screen countdown stays in lockstep with the in-app
                // timer. Launching on serviceScope (instead of awaiting
                // inline) keeps this loop's cadence at a true 1Hz: a slow
                // Glance recomposition or DataStore write no longer
                // stretches the next `delay(1000)` out by hundreds of ms.
                // Glance serializes per-widget updates internally so pushes
                // can't pile up.
                serviceScope.launch { pushWidgetTick() }
            }
            if (!isPaused && secondsRemaining <= 0) {
                onCountdownComplete()
            }
        }
    }

    private fun pauseCountdown() {
        if (isPaused || tickJob == null) return
        isPaused = true
        tickJob?.cancel()
        tickJob = null
        updateOngoingNotification(secondsRemaining)
        sendBroadcast(
            Intent(ACTION_PAUSED).apply {
                setPackage(packageName)
                putExtra(EXTRA_SECONDS_REMAINING, secondsRemaining)
                putExtra(EXTRA_SESSION_INDEX, sessionIndex)
                putExtra(EXTRA_SESSION_TYPE, sessionType)
                putExtra(EXTRA_OWNER, owner)
            }
        )
        // Zero the Chronometer base — the widget switches back to a static
        // Glance Text frozen at the paused remaining time, no more ticking.
        serviceScope.launch { pushWidgetRunState(running = false, paused = true) }
    }

    private fun resumeCountdown() {
        if (!isPaused) return
        isPaused = false
        updateOngoingNotification(secondsRemaining)
        sendBroadcast(
            Intent(ACTION_RESUMED).apply {
                setPackage(packageName)
                putExtra(EXTRA_SECONDS_REMAINING, secondsRemaining)
                putExtra(EXTRA_SESSION_INDEX, sessionIndex)
                putExtra(EXTRA_SESSION_TYPE, sessionType)
                putExtra(EXTRA_OWNER, owner)
            }
        )
        runTickLoop()
    }

    private fun stopCountdown() {
        tickJob?.cancel()
        tickJob = null
        isPaused = false
        // Push a final live snapshot so the widget flips out of the running
        // pill immediately. The ViewModel's onServiceStopped will follow up
        // with a full write that resets remainingSeconds to totalSeconds, but
        // that path requires the ViewModel to be alive.
        serviceScope.launch { pushWidgetRunState(running = false, paused = false) }
    }

    private fun onCountdownComplete() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID_ONGOING)

        // Hoist the (suspend) preference read + optional continuous-buzz onto
        // serviceScope so the service never blocks its dispatcher to await
        // DataStore / VibrationAdapter work. The completion notification and
        // stop sequence now happen inside the coroutine to preserve ordering
        // (notification must post BEFORE startContinuousBuzz arms its
        // dismiss path).
        serviceScope.launch {
            val timerPrefs = TimerPreferences(this@PomodoroTimerService)
            val buzzUntilDismissed = timerPrefs.getBuzzUntilDismissed().first()
            val overrideVolume = timerPrefs.getOverrideVolume().first()
            val volumePercent = timerPrefs.getAlarmVolumePercent().first()
            val soundId = timerPrefs.getAlarmSoundId().first()
            val ringSeconds = timerPrefs.getRingDurationSeconds().first()
            val vibrateEnabled = timerPrefs.getVibrateEnabled().first()
            val vibrationSeconds = timerPrefs.getVibrationDurationSeconds().first()
            val isSilentSound = soundId == BuiltInSound.SILENT_ID

            // We drive sound and vibration ourselves now that both are
            // user-configurable, so the channel never fires either — this
            // keeps the chosen ring/vibration duration authoritative and
            // avoids a double ring with the channel default sound.
            val completion = buildCompletionNotification(
                buzzUntilDismissed = buzzUntilDismissed,
                silenceChannelSound = true
            )
            manager?.notify(NOTIFICATION_ID_COMPLETE, completion)

            if (!isSilentSound) {
                val resolved = SoundResolver.resolve(this@PomodoroTimerService, soundId)
                val uri = when (resolved) {
                    is SoundResolver.UriChoice -> resolved.uri
                    SoundResolver.SilentChoice -> null
                }
                if (uri != null) {
                    TimerAlarmPlayer.play(
                        context = this@PomodoroTimerService,
                        soundUri = uri,
                        ringSeconds = ringSeconds,
                        targetPercent = volumePercent,
                        pinVolume = overrideVolume
                    )
                }
            }

            if (vibrateEnabled) {
                if (buzzUntilDismissed) {
                    NotificationHelper.startContinuousBuzz(this@PomodoroTimerService)
                } else {
                    startFiniteVibration(vibrationSeconds)
                }
            }

            sendBroadcast(
                Intent(ACTION_COMPLETE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_SESSION_INDEX, sessionIndex)
                    putExtra(EXTRA_SESSION_TYPE, sessionType)
                    putExtra(EXTRA_OWNER, owner)
                }
            )
            pushWidgetRunState(running = false, paused = false)

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Vibrates the device for [seconds], looping the continuous-buzz
     * pattern and cancelling on the configured deadline. Launched on
     * [serviceScope] so the service can finish its tear-down sequence
     * immediately — the cancel still runs after the service stops because
     * the supervisor scope is cancelled only in [onDestroy].
     */
    private fun startFiniteVibration(seconds: Int) {
        VibrationAdapter.playNow(
            context = this,
            pattern = NotificationHelper.CONTINUOUS_BUZZ_PATTERN,
            intensity = VibrationIntensity.STRONG,
            continuous = true
        )
        serviceScope.launch {
            delay(seconds.coerceAtLeast(1) * 1000L)
            VibrationAdapter.cancel(this@PomodoroTimerService)
        }
    }

    /**
     * Lifecycle-event widget refresh. Called on start / pause / resume /
     * stop / complete to push the run flags + countdown deadline.
     * Structural fields (mode, session counts, pomodoro flag) stay as the
     * in-app ViewModel last wrote them — DataStore edits are atomic so the
     * two writers don't race.
     *
     * Fires for both [OWNER_TIMER] and [OWNER_SMART_POMODORO] so the widget
     * reflects whichever Pomodoro flow the user is actually running. The
     * displayed mode/session count may be stale during a Smart Pomodoro
     * session (those fields come from the regular Timer ViewModel) — that's
     * an acceptable trade for the widget showing a live countdown instead
     * of staying frozen on "Ready to Focus".
     *
     * Errors are swallowed so a flaky DataStore disk or an unplaced widget
     * never crashes the foreground service.
     */
    private suspend fun pushWidgetRunState(running: Boolean, paused: Boolean) {
        if (!BuildConfig.WIDGETS_ENABLED) return
        try {
            TimerStateDataStore.writeRunState(
                context = this,
                remainingSeconds = secondsRemaining,
                isRunning = running,
                isPaused = paused,
                sessionEndElapsedRealtime = if (running) sessionEndElapsedRealtime else 0L
            )
            TimerWidget().updateAll(this)
        } catch (e: Exception) {
            Log.w("PomodoroService", "Widget run-state update failed: ${e.message}")
        }
    }

    /**
     * Per-tick widget refresh. Skips the DataStore write — the deadline
     * stored at start is the only field that needs to be fresh, and the
     * widget computes displayed seconds from it. Just calls `updateAll` so
     * Glance re-composes against the cached state. Errors are swallowed.
     */
    private suspend fun pushWidgetTick() {
        if (!BuildConfig.WIDGETS_ENABLED) return
        try {
            TimerWidget().updateAll(this)
        } catch (e: Exception) {
            Log.w("PomodoroService", "Widget tick update failed: ${e.message}")
        }
    }

    private fun updateOngoingNotification(seconds: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID_ONGOING, buildOngoingNotification(seconds))
    }

    private fun buildOngoingNotification(seconds: Int): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PomodoroTimerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, PomodoroTimerService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePending = PendingIntent.getService(
            this,
            3,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (sessionType) {
            SESSION_TYPE_BREAK -> "Break"
            SESSION_TYPE_LONG_BREAK -> "Long Break"
            else -> "Focus Session"
        }
        val content = if (isPaused) {
            "$title — Paused at ${formatRemaining(seconds)}"
        } else {
            "$title — ${formatRemaining(seconds)} remaining"
        }

        return NotificationCompat
            .Builder(this, CHANNEL_ID_ONGOING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(tapPending)
            .addAction(
                if (isPaused) {
                    android.R.drawable.ic_media_play
                } else {
                    android.R.drawable.ic_media_pause
                },
                if (isPaused) "Resume" else "Pause",
                pauseResumePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending
            ).build()
    }

    private fun buildCompletionNotification(
        buzzUntilDismissed: Boolean,
        silenceChannelSound: Boolean = false
    ): Notification {
        val title = when (sessionType) {
            SESSION_TYPE_BREAK, SESSION_TYPE_LONG_BREAK -> "Break complete"
            else -> "Session complete"
        }
        val body = when {
            buzzUntilDismissed -> TimerBuzzerDismissReceiver.BUZZ_BODY_TEXT
            sessionType == SESSION_TYPE_BREAK ||
                sessionType == SESSION_TYPE_LONG_BREAK -> "Ready to get back to focus?"
            else -> "Nice work — time for a break."
        }

        val builder = NotificationCompat
            .Builder(this, CHANNEL_ID_COMPLETE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (silenceChannelSound) {
            // TimerAlarmPlayer + VibrationAdapter drive sound and vibration
            // directly so they honor the chosen tone, ring duration, and
            // vibration duration. Suppress the channel's own defaults to
            // avoid a double ring or an extra vibration burst.
            builder.setDefaults(0)
            builder.setSilent(true)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        // Always route dismissal through TimerBuzzerDismissReceiver so any
        // active alarm sound / vibration is cancelled when the user taps,
        // swipes, or hits Stop — even for a finite ring duration. The
        // receiver is a cheap no-op when nothing is active.
        val tapDismissPending = TimerBuzzerDismissReceiver
            .pendingIntent(this, NOTIFICATION_ID_COMPLETE, launchApp = true)
        val swipeDismissPending = TimerBuzzerDismissReceiver
            .pendingIntent(this, NOTIFICATION_ID_COMPLETE, launchApp = false)
        builder.setContentIntent(tapDismissPending)
        builder.setDeleteIntent(swipeDismissPending)
        if (buzzUntilDismissed) {
            builder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                "Stop",
                swipeDismissPending
            )
        }

        return builder.build()
    }

    private fun broadcastTick(seconds: Int) {
        sendBroadcast(
            Intent(ACTION_TICK).apply {
                setPackage(packageName)
                putExtra(EXTRA_SECONDS_REMAINING, seconds)
                putExtra(EXTRA_SESSION_INDEX, sessionIndex)
                putExtra(EXTRA_SESSION_TYPE, sessionType)
                putExtra(EXTRA_OWNER, owner)
            }
        )
    }

    override fun onDestroy() {
        tickJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.averycorp.prismtask.pomodoro.START"
        const val ACTION_PAUSE = "com.averycorp.prismtask.pomodoro.PAUSE"
        const val ACTION_RESUME = "com.averycorp.prismtask.pomodoro.RESUME"
        const val ACTION_STOP = "com.averycorp.prismtask.pomodoro.STOP"
        const val ACTION_TICK = "com.averycorp.prismtask.pomodoro.TICK"
        const val ACTION_PAUSED = "com.averycorp.prismtask.pomodoro.PAUSED"
        const val ACTION_RESUMED = "com.averycorp.prismtask.pomodoro.RESUMED"
        const val ACTION_STOPPED = "com.averycorp.prismtask.pomodoro.STOPPED"
        const val ACTION_COMPLETE = "com.averycorp.prismtask.pomodoro.COMPLETE"

        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_SESSION_INDEX = "session_index"
        const val EXTRA_SESSION_TYPE = "session_type"
        const val EXTRA_SECONDS_REMAINING = "seconds_remaining"
        const val EXTRA_OWNER = "owner"

        const val SESSION_TYPE_WORK = "WORK"
        const val SESSION_TYPE_BREAK = "BREAK"
        const val SESSION_TYPE_LONG_BREAK = "LONG_BREAK"

        const val OWNER_TIMER = "TIMER"
        const val OWNER_SMART_POMODORO = "SMART_POMODORO"

        const val CHANNEL_ID_ONGOING = "pomodoro_timer"
        private const val CHANNEL_NAME_ONGOING = "Pomodoro Timer"
        const val CHANNEL_ID_COMPLETE = "pomodoro_timer_alerts"
        private const val CHANNEL_NAME_COMPLETE = "Pomodoro Alerts"

        private const val NOTIFICATION_ID_ONGOING = 9_001
        private const val NOTIFICATION_ID_COMPLETE = 9_002

        fun start(
            context: Context,
            durationSeconds: Int,
            sessionIndex: Int,
            sessionType: String,
            owner: String = OWNER_SMART_POMODORO
        ) {
            // Wrap the full body so plain-JVM unit tests (which back Context
            // with MockK and resolve Android framework calls against android.jar
            // stubs) don't blow up at createChannels(), Intent(...), or the
            // service-start call itself. On-device this is a no-op happy path.
            try {
                createChannels(context)
                val intent = Intent(context, PomodoroTimerService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_DURATION_SECONDS, durationSeconds)
                    putExtra(EXTRA_SESSION_INDEX, sessionIndex)
                    putExtra(EXTRA_SESSION_TYPE, sessionType)
                    putExtra(EXTRA_OWNER, owner)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                // The fallback diagnostics must themselves be guarded because
                // Log.e is a stub that throws under plain JVM unit tests.
                try {
                    Log.e("PomodoroService", "Failed to start foreground service", e)
                } catch (_: Throwable) {
                    // No-op: unit-test environment without Android framework.
                }
            }
        }

        fun pause(context: Context) {
            dispatchControl(context, ACTION_PAUSE)
        }

        fun resume(context: Context) {
            dispatchControl(context, ACTION_RESUME)
        }

        fun stop(context: Context) {
            dispatchControl(context, ACTION_STOP)
        }

        private fun dispatchControl(context: Context, controlAction: String) {
            try {
                val intent = Intent(context, PomodoroTimerService::class.java).apply {
                    action = controlAction
                }
                context.startService(intent)
            } catch (_: Exception) {
                // Service may already be stopped, or context unavailable.
            }
        }

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val ongoing = NotificationChannel(
                CHANNEL_ID_ONGOING,
                CHANNEL_NAME_ONGOING,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing Pomodoro session countdown"
                setSound(null, null)
                enableVibration(false)
            }
            val complete = NotificationChannel(
                CHANNEL_ID_COMPLETE,
                CHANNEL_NAME_COMPLETE,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a Pomodoro session or break ends"
            }
            manager.createNotificationChannel(ongoing)
            manager.createNotificationChannel(complete)
        }

        private fun formatRemaining(totalSeconds: Int): String {
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return "%02d:%02d".format(mins, secs)
        }
    }
}
