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
import com.averycorp.prismtask.data.repository.LeisureBudgetRepository
import com.averycorp.prismtask.domain.model.LeisureCategory
import com.averycorp.prismtask.domain.model.LeisureSessionSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Leisure Budget v2.0 — Item 4. Foreground service that runs a leisure
 * countdown and inserts a [com.averycorp.prismtask.data.local.entity.LeisureSessionEntity]
 * on natural completion or user-initiated stop.
 *
 * Mirrors [PomodoroTimerService] structurally but is intentionally
 * simpler: no session-index/long-break/break-type machinery, no widget
 * mirroring (Item 4 doesn't ship a widget — that's v2.2 scope). The
 * insertion path is the only side-effect on STOP / COMPLETE so the
 * service can be killed mid-session without losing all data — a session
 * is only logged when minutes ≥ 1.
 *
 * The Android client elapsed-minutes calculation uses `(initialSeconds -
 * secondsRemaining) / 60`. A 30-second tap-stop logs 0 minutes (and so
 * no session row is inserted) which matches the spec's "duration_minutes
 * > 0" CHECK on the server.
 */
@AndroidEntryPoint
class LeisureTimerService : Service() {
    @Inject lateinit var repository: LeisureBudgetRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private var secondsRemaining: Int = 0
    private var initialSeconds: Int = 0
    private var activityId: Long? = null
    private var activityName: String = ""
    private var category: String = LeisureCategory.PASSIVE.name
    private var isPaused: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startCountdown(intent)
            ACTION_PAUSE -> pauseCountdown()
            ACTION_RESUME -> resumeCountdown()
            ACTION_STOP -> stopAndLog(natural = false)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCountdown(intent: Intent) {
        createChannel(this)
        secondsRemaining = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0)
        initialSeconds = secondsRemaining
        activityId = intent.getLongExtra(EXTRA_ACTIVITY_ID, -1L)
            .takeIf { it > 0L }
        activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME) ?: "Leisure"
        category = intent.getStringExtra(EXTRA_CATEGORY) ?: LeisureCategory.PASSIVE.name
        isPaused = false

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
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED", e)
        }
        runTickLoop()
    }

    private fun runTickLoop() {
        tickJob?.cancel()
        tickJob = serviceScope.launch {
            broadcastTick(secondsRemaining)
            while (secondsRemaining > 0) {
                delay(1000)
                if (isPaused) break
                secondsRemaining -= 1
                updateOngoingNotification(secondsRemaining)
                broadcastTick(secondsRemaining)
            }
            if (!isPaused && secondsRemaining <= 0) {
                stopAndLog(natural = true)
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
            }
        )
        runTickLoop()
    }

    private fun stopAndLog(natural: Boolean) {
        tickJob?.cancel()
        tickJob = null
        val elapsedSec = (initialSeconds - secondsRemaining).coerceAtLeast(0)
        val elapsedMin = elapsedSec / 60
        serviceScope.launch {
            if (elapsedMin >= 1) {
                val categoryId = category.takeIf { it.isNotBlank() }
                    ?: LeisureCategory.PASSIVE.name
                runCatching {
                    repository.logSessionByCategoryId(
                        activityId = activityId,
                        categoryId = categoryId,
                        durationMinutes = elapsedMin,
                        loggedAt = System.currentTimeMillis(),
                        source = LeisureSessionSource.TIMER
                    )
                }.onFailure {
                    Log.e(TAG, "Failed to log leisure session", it)
                }
            }
            sendBroadcast(
                Intent(if (natural) ACTION_COMPLETE else ACTION_STOPPED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_ACTIVITY_ID, activityId ?: -1L)
                    putExtra(EXTRA_MINUTES_LOGGED, elapsedMin)
                    putExtra(EXTRA_NATURAL_COMPLETION, natural)
                }
            )
            if (natural) {
                val mgr = getSystemService(NotificationManager::class.java)
                mgr?.notify(NOTIFICATION_ID_COMPLETE, buildCompletionNotification(elapsedMin))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun broadcastTick(seconds: Int) {
        sendBroadcast(
            Intent(ACTION_TICK).apply {
                setPackage(packageName)
                putExtra(EXTRA_SECONDS_REMAINING, seconds)
                putExtra(EXTRA_ACTIVITY_ID, activityId ?: -1L)
            }
        )
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
        val stopIntent = Intent(this, LeisureTimerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseResumeIntent = Intent(this, LeisureTimerService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePending = PendingIntent.getService(
            this,
            3,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val content = if (isPaused) {
            "$activityName — Paused at ${formatRemaining(seconds)}"
        } else {
            "$activityName — ${formatRemaining(seconds)} remaining"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID_ONGOING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Leisure timer")
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumePending
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun buildCompletionNotification(elapsedMin: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Leisure session complete")
            .setContentText("Logged $elapsedMin min of $activityName")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    private fun formatRemaining(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "LeisureTimerService"

        const val ACTION_START = "com.averycorp.prismtask.leisure.action.START"
        const val ACTION_PAUSE = "com.averycorp.prismtask.leisure.action.PAUSE"
        const val ACTION_RESUME = "com.averycorp.prismtask.leisure.action.RESUME"
        const val ACTION_STOP = "com.averycorp.prismtask.leisure.action.STOP"
        const val ACTION_TICK = "com.averycorp.prismtask.leisure.action.TICK"
        const val ACTION_PAUSED = "com.averycorp.prismtask.leisure.action.PAUSED"
        const val ACTION_RESUMED = "com.averycorp.prismtask.leisure.action.RESUMED"
        const val ACTION_STOPPED = "com.averycorp.prismtask.leisure.action.STOPPED"
        const val ACTION_COMPLETE = "com.averycorp.prismtask.leisure.action.COMPLETE"

        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_SECONDS_REMAINING = "seconds_remaining"
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_ACTIVITY_NAME = "activity_name"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_MINUTES_LOGGED = "minutes_logged"
        const val EXTRA_NATURAL_COMPLETION = "natural_completion"

        const val CHANNEL_ID_ONGOING = "leisure_timer_ongoing"
        const val CHANNEL_ID_COMPLETE = "leisure_timer_complete"
        private const val NOTIFICATION_ID_ONGOING = 4501
        private const val NOTIFICATION_ID_COMPLETE = 4502

        fun start(
            context: Context,
            durationSeconds: Int,
            activityId: Long?,
            activityName: String,
            category: LeisureCategory
        ) {
            val intent = Intent(context, LeisureTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_SECONDS, durationSeconds)
                putExtra(EXTRA_ACTIVITY_ID, activityId ?: -1L)
                putExtra(EXTRA_ACTIVITY_NAME, activityName)
                putExtra(EXTRA_CATEGORY, category.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LeisureTimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (manager.getNotificationChannel(CHANNEL_ID_ONGOING) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_ONGOING,
                        "Leisure timer (ongoing)",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Shows the running leisure timer"
                        setSound(null, null)
                    }
                )
            }
            if (manager.getNotificationChannel(CHANNEL_ID_COMPLETE) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_COMPLETE,
                        "Leisure timer (complete)",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Fires when a leisure timer completes"
                    }
                )
            }
        }
    }
}
