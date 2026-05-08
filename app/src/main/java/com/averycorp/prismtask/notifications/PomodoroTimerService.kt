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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.data.preferences.TimerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
        tickJob = serviceScope.launch {
            // Emit an initial tick so the UI picks up the starting value even
            // if the user backgrounded the app before the first second
            // elapsed.
            broadcastTick(secondsRemaining)
            while (secondsRemaining > 0) {
                delay(1000)
                if (isPaused) break
                secondsRemaining -= 1
                updateOngoingNotification(secondsRemaining)
                broadcastTick(secondsRemaining)
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
    }

    private fun onCountdownComplete() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID_ONGOING)

        val buzzUntilDismissed = runBlocking {
            TimerPreferences(this@PomodoroTimerService)
                .getBuzzUntilDismissed()
                .first()
        }
        val completion = buildCompletionNotification(buzzUntilDismissed)
        manager?.notify(NOTIFICATION_ID_COMPLETE, completion)

        if (buzzUntilDismissed) {
            runBlocking { NotificationHelper.startContinuousBuzz(this@PomodoroTimerService) }
        }

        sendBroadcast(
            Intent(ACTION_COMPLETE).apply {
                setPackage(packageName)
                putExtra(EXTRA_SESSION_INDEX, sessionIndex)
                putExtra(EXTRA_SESSION_TYPE, sessionType)
                putExtra(EXTRA_OWNER, owner)
            }
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun buildCompletionNotification(buzzUntilDismissed: Boolean): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this,
            2,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (sessionType) {
            SESSION_TYPE_BREAK, SESSION_TYPE_LONG_BREAK -> "Break Complete!"
            else -> "Session Complete!"
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

        if (buzzUntilDismissed) {
            val tapDismissPending = TimerBuzzerDismissReceiver
                .pendingIntent(this, NOTIFICATION_ID_COMPLETE, launchApp = true)
            val swipeDismissPending = TimerBuzzerDismissReceiver
                .pendingIntent(this, NOTIFICATION_ID_COMPLETE, launchApp = false)
            builder.setContentIntent(tapDismissPending)
            builder.setDeleteIntent(swipeDismissPending)
            builder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                "Stop",
                swipeDismissPending
            )
        } else {
            builder.setContentIntent(tapPending)
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
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
