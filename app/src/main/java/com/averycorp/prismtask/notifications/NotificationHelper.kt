package com.averycorp.prismtask.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.data.local.dao.NotificationProfileDao
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.domain.model.notifications.EscalationStepAction
import com.averycorp.prismtask.domain.model.notifications.LockScreenVisibility
import com.averycorp.prismtask.domain.model.notifications.NotificationDisplayMode
import com.averycorp.prismtask.domain.model.notifications.NotificationProfile
import com.averycorp.prismtask.domain.model.notifications.UrgencyTier
import com.averycorp.prismtask.domain.model.notifications.VibrationIntensity
import com.averycorp.prismtask.domain.usecase.NotificationProfileResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * Hilt entry point exposing the data needed to resolve the user's
 * currently-active [NotificationProfile] from the static
 * [NotificationHelper] object. The legacy reminder paths are not
 * profile-aware; this lets them fall back to the active profile's
 * vibration pattern when one is configured, satisfying the C6 fix
 * without rewriting every call site to be profile-aware.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NotificationHelperEntryPoint {
    fun notificationProfileDao(): NotificationProfileDao
    fun notificationPreferences(): NotificationPreferences
    fun notificationPauseGate(): NotificationPauseGate
}

/**
 * Look up the singleton [NotificationPauseGate] from the
 * [NotificationHelper] object context. Non-injectable callers (the
 * static `show*` helpers) use this to honor the MH-first G4 pause-all
 * toggle without needing Hilt construction. Medication call sites
 * deliberately do NOT call this — medication reminders are exempt.
 */
private fun pauseGateOf(context: Context): NotificationPauseGate =
    EntryPointAccessors
        .fromApplication(context.applicationContext, NotificationHelperEntryPoint::class.java)
        .notificationPauseGate()

object NotificationHelper {
    private const val BASE_CHANNEL_ID = "prismtask_reminders"
    private const val CHANNEL_NAME = "Task Reminders"
    private const val BASE_MED_CHANNEL_ID = "prismtask_medication_reminders"
    private const val MED_CHANNEL_NAME = "Medication Reminders"
    private const val BASE_TIMER_CHANNEL_ID = "prismtask_timer_alerts"
    private const val TIMER_CHANNEL_NAME = "Timer Alerts"
    private const val TIMER_NOTIFICATION_ID = 8_001
    private const val SLOT_INTERVAL_NOTIFICATION_OFFSET = 500_000
    private const val SLOT_CLOCK_NOTIFICATION_OFFSET = 700_000
    private const val MED_SLOT_CLOCK_NOTIFICATION_OFFSET = 800_000

    private const val LEGACY_CHANNEL_ID = "averytask_reminders"
    private const val LEGACY_MED_CHANNEL_ID = "averytask_medication_reminders"

    /**
     * Long, repeating-feel vibration used when the user enables "Buzz
     * Repeatedly". Android plays the channel pattern once per notification,
     * so we approximate a repeating buzz by laying a dense sequence of
     * pulses over ~10 seconds. Users dismissing or tapping the notification
     * stops it naturally.
     */
    private val REPEATING_VIBRATION_PATTERN = longArrayOf(
        0,
        500,
        300,
        500,
        300,
        500,
        300,
        500,
        300,
        500,
        300,
        500,
        300,
        500,
        300,
        500,
        300,
        500,
        300,
        500,
        300
    )

    /**
     * Short [wait, buzz, wait, buzz] cell played with `repeatIndex = 1` when
     * the user opts into "Buzz Until Dismissed" on timer completion. The
     * OS loops it indefinitely until [TimerBuzzerDismissReceiver] calls
     * [VibrationAdapter.cancel].
     */
    internal val CONTINUOUS_BUZZ_PATTERN = longArrayOf(0, 600, 400, 600, 400)

    /**
     * Bundle of user-configurable delivery-style flags. Channels are
     * immutable for sound / vibration / importance, so each unique
     * combination gets its own channel ID via [channelSuffix].
     */
    private data class Style(
        val importance: String,
        val fullScreen: Boolean,
        val overrideVolume: Boolean,
        val repeatingVibration: Boolean
    )

    private suspend fun currentStyle(context: Context): Style {
        val prefs = NotificationPreferences.from(context)
        return Style(
            importance = prefs.getImportanceOnce(),
            fullScreen = prefs.getFullScreenNotificationsEnabledOnce(),
            overrideVolume = prefs.getOverrideVolumeEnabledOnce(),
            repeatingVibration = prefs.getRepeatingVibrationEnabledOnce()
        )
    }

    private suspend fun recordImportance(context: Context, importance: String) {
        NotificationPreferences.from(context).setPreviousImportance(importance)
    }

    private fun channelSuffix(style: Style): String = buildString {
        append('_').append(style.importance)
        if (style.fullScreen) append("_fsi")
        if (style.overrideVolume) append("_alrm")
        if (style.repeatingVibration) append("_rvib")
    }

    fun channelIdFor(base: String, importance: String): String = "${base}_$importance"

    private fun channelIdFor(base: String, style: Style): String = base + channelSuffix(style)

    fun importanceToChannelLevel(importance: String): Int = when (importance) {
        NotificationPreferences.IMPORTANCE_MINIMAL -> NotificationManager.IMPORTANCE_LOW
        NotificationPreferences.IMPORTANCE_URGENT -> NotificationManager.IMPORTANCE_HIGH
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun effectiveChannelImportance(style: Style): Int {
        // Full-screen intents and heads-up alarm behavior require HIGH.
        // When the user opts into either, bump the channel so the behavior
        // actually takes effect regardless of the "Importance" picker.
        val base = importanceToChannelLevel(style.importance)
        return if (style.fullScreen || style.overrideVolume) {
            maxOf(base, NotificationManager.IMPORTANCE_HIGH)
        } else {
            base
        }
    }

    fun importanceToBuilderPriority(importance: String): Int = when (importance) {
        NotificationPreferences.IMPORTANCE_MINIMAL -> NotificationCompat.PRIORITY_LOW
        NotificationPreferences.IMPORTANCE_URGENT -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    private fun effectiveBuilderPriority(style: Style): Int {
        val base = importanceToBuilderPriority(style.importance)
        return if (style.fullScreen || style.overrideVolume) {
            maxOf(base, NotificationCompat.PRIORITY_HIGH)
        } else {
            base
        }
    }

    /**
     * Drops every channel the app has previously created for [base] under
     * any style combination other than the current one, so a style change
     * wipes stale channels (whose sound/vibration/importance are immutable).
     */
    private fun deleteStaleChannels(context: Context, base: String, style: Style) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val currentId = channelIdFor(base, style)
        // Bare legacy channel (pre-importance-suffix scheme).
        manager.deleteNotificationChannel(base)
        for (importance in NotificationPreferences.ALL_IMPORTANCES) {
            for (fsi in listOf(false, true)) {
                for (alrm in listOf(false, true)) {
                    for (rvib in listOf(false, true)) {
                        val candidate = Style(importance, fsi, alrm, rvib)
                        val id = channelIdFor(base, candidate)
                        if (id != currentId) {
                            manager.deleteNotificationChannel(id)
                        }
                    }
                }
            }
        }
    }

    private suspend fun buildChannel(
        context: Context,
        id: String,
        name: String,
        description: String,
        style: Style
    ): NotificationChannel = NotificationChannel(
        id,
        name,
        effectiveChannelImportance(style)
    ).apply {
        this.description = description
        if (style.overrideVolume) {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(alarmUri, attrs)
            setBypassDnd(true)
        }
        if (style.repeatingVibration) {
            enableVibration(true)
            vibrationPattern = activeProfileVibrationPattern(context)
                ?: REPEATING_VIBRATION_PATTERN
        }
    }

    /**
     * Returns the vibration pattern associated with the user's currently-
     * active [NotificationProfile], or null if none is set or the profile
     * disables vibration. Used by the legacy non-profile-aware notification
     * paths to honor the active profile's haptics setting (C6).
     */
    private suspend fun activeProfileVibrationPattern(context: Context): LongArray? {
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                NotificationHelperEntryPoint::class.java
            )
        }.getOrNull() ?: return null
        val activeId = entryPoint.notificationPreferences().getActiveProfileIdOnce()
        if (activeId <= 0L) return null
        val entity = runCatching { entryPoint.notificationProfileDao().getById(activeId) }
            .getOrNull() ?: return null
        val profile = NotificationProfileResolver().resolve(entity)
        return VibrationAdapter.patternFor(profile)
    }

    suspend fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        migrateOldChannels(context)
        val style = currentStyle(context)
        deleteStaleChannels(context, BASE_CHANNEL_ID, style)
        val channel = buildChannel(
            context = context,
            id = channelIdFor(BASE_CHANNEL_ID, style),
            name = CHANNEL_NAME,
            description = "Reminders for upcoming tasks",
            style = style
        )
        manager.createNotificationChannel(channel)
        recordImportance(context, style.importance)
    }

    fun migrateOldChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(LEGACY_MED_CHANNEL_ID)
    }

    /**
     * Applies the user's delivery-style preferences (full-screen, alarm
     * stream, repeating vibration) to [builder]. Callers still set their
     * own content/actions — this only touches priority, category, sound,
     * vibration, and the full-screen intent.
     */
    private fun applyStyle(
        builder: NotificationCompat.Builder,
        style: Style,
        tapPending: PendingIntent
    ) {
        // API 26+ uses the channel's sound/vibration; builder-level calls
        // are kept only for flags that still have runtime effect
        // (priority, category, full-screen intent).
        builder.priority = effectiveBuilderPriority(style)
        if (style.overrideVolume) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        }
        if (style.fullScreen) {
            builder.setFullScreenIntent(tapPending, true)
        }
    }

    suspend fun showTaskReminder(
        context: Context,
        taskId: Long,
        taskTitle: String,
        taskDescription: String?
    ) {
        val prefs = NotificationPreferences.from(context)
        val enabled = prefs.taskRemindersEnabled.first()
        if (!enabled) {
            Log.d("NotificationHelper", "Task reminders disabled — skipping task=$taskId")
            return
        }
        // MH-first G4 pause-all gate. Medication exempt — only fires on the
        // task reminder path. Reads expiry at notify time so a stale (past)
        // pause auto-clears in effect.
        if (pauseGateOf(context).isPausedNow()) {
            Log.d("NotificationHelper", "Pause-all active — skipping task=$taskId")
            return
        }
        // Rest-day suppression (MH-First audit § G3). Non-medication
        // reminders pause until tomorrow's logical day. Medications still
        // fire — they have their own showMedication* fns that intentionally
        // do not call this gate.
        if (RestDayGate.shouldSuppress(context)) {
            Log.d("NotificationHelper", "Rest day in effect — suppressing task=$taskId")
            return
        }
        Log.d("NotificationHelper", "Showing notification for task=$taskId")
        createNotificationChannel(context)
        val style = currentStyle(context)
        val channelId = channelIdFor(BASE_CHANNEL_ID, style)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completeIntent = Intent(context, CompleteTaskReceiver::class.java).apply {
            putExtra("taskId", taskId)
        }
        val completePending = PendingIntent.getBroadcast(
            context,
            taskId.toInt() + 100_000,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$taskTitle is coming up")
            .setContentText(taskDescription ?: "Ready when you are.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Complete",
                completePending
            )
        applyStyle(builder, style, tapPending)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(taskId.toInt(), builder.build())
    }

    private suspend fun createMedicationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val style = currentStyle(context)
        deleteStaleChannels(context, BASE_MED_CHANNEL_ID, style)
        val channel = buildChannel(
            context = context,
            id = channelIdFor(BASE_MED_CHANNEL_ID, style),
            name = MED_CHANNEL_NAME,
            description = "Reminders for medication and timed habits",
            style = style
        )
        manager.createNotificationChannel(channel)
        recordImportance(context, style.importance)
    }

    suspend fun showMedicationReminder(
        context: Context,
        habitId: Long,
        habitName: String,
        habitDescription: String?,
        intervalMillis: Long,
        doseNumber: Int = 0,
        totalDoses: Int = 1
    ) {
        val prefs = NotificationPreferences.from(context)
        val enabled = prefs.medicationRemindersEnabled.first()
        if (!enabled) {
            Log.d("NotificationHelper", "Medication reminders disabled — skipping habit=$habitId")
            return
        }
        createMedicationChannel(context)
        val style = currentStyle(context)
        val channelId = channelIdFor(BASE_MED_CHANNEL_ID, style)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            habitId.toInt() + 200_000,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val logIntent = Intent(context, LogMedicationReceiver::class.java).apply {
            putExtra("habitId", habitId)
        }
        val logPending = PendingIntent.getBroadcast(
            context,
            habitId.toInt() + 300_000,
            logIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intervalText = formatInterval(intervalMillis)
        val contentText = habitDescription ?: "$habitName \u2014 whenever you're ready."

        val doseInfo = if (totalDoses > 1 && doseNumber > 0) " (dose $doseNumber of $totalDoses)" else ""
        val title = "$habitName$doseInfo"

        val bigText = if (totalDoses > 1 && doseNumber > 0 && doseNumber < totalDoses) {
            "$contentText\nDose $doseNumber of $totalDoses \u2022 next reminder $intervalText after logging."
        } else if (totalDoses > 1 && doseNumber >= totalDoses) {
            "$contentText\nFinal dose ($doseNumber of $totalDoses)."
        } else {
            "$contentText\nNext reminder $intervalText after logging."
        }

        val builder = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Log",
                logPending
            )
        applyStyle(builder, style, tapPending)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(habitId.toInt() + 200_000, builder.build())
    }

    /**
     * Notification for an INTERVAL-mode slot reminder fired by
     * [MedicationIntervalRescheduler]. Notification id offset of `500_000`
     * matches the rescheduler's request-code namespace and stays clear of
     * the medication / habit offsets (`200_000`).
     */
    suspend fun showSlotIntervalReminder(
        context: Context,
        slotId: Long,
        slotName: String
    ) = showSlotReminder(
        context = context,
        slotId = slotId,
        notificationId = slotId.toInt() + SLOT_INTERVAL_NOTIFICATION_OFFSET,
        title = "$slotName Medications",
        contentText = "Tap to log your $slotName dose."
    )

    /**
     * Notification for a CLOCK-mode slot reminder fired by
     * [MedicationClockRescheduler]. Notification id offset of `700_000`
     * matches that rescheduler's request-code namespace.
     */
    suspend fun showSlotClockReminder(
        context: Context,
        slotId: Long,
        slotName: String,
        idealTime: String
    ) = showSlotReminder(
        context = context,
        slotId = slotId,
        notificationId = slotId.toInt() + SLOT_CLOCK_NOTIFICATION_OFFSET,
        title = "$slotName Medications",
        contentText = "It's $idealTime — time for your $slotName dose."
    )

    /**
     * Notification for a per-(medication, slot) CLOCK alarm fired by
     * [MedicationClockRescheduler.registerAlarmForMedSlot]. Names the
     * medication explicitly so the user can distinguish this med-specific
     * reminder from the slot-level "Morning Medications" reminder when
     * both fire (e.g. when the user set an idealTime override). The
     * notification id is keyed off the (med, slot) pair to avoid
     * collisions with the slot-level offset (`700_000`) and other
     * notification namespaces.
     */
    suspend fun showMedSlotClockReminder(
        context: Context,
        medicationId: Long,
        slotId: Long,
        medName: String,
        slotName: String,
        idealTime: String
    ) {
        val notificationId = MED_SLOT_CLOCK_NOTIFICATION_OFFSET +
            (medicationId % 1000L).toInt() * 1000 +
            (slotId % 1000L).toInt()
        showSlotReminder(
            context = context,
            slotId = slotId,
            notificationId = notificationId,
            title = medName,
            contentText = "It's $idealTime — your $slotName dose of $medName."
        )
    }

    private suspend fun showSlotReminder(
        context: Context,
        slotId: Long,
        notificationId: Int,
        title: String,
        contentText: String
    ) {
        val prefs = NotificationPreferences.from(context)
        val enabled = prefs.medicationRemindersEnabled.first()
        if (!enabled) {
            Log.d("NotificationHelper", "Medication reminders disabled — skipping slot=$slotId")
            return
        }
        createMedicationChannel(context)
        val style = currentStyle(context)
        val channelId = channelIdFor(BASE_MED_CHANNEL_ID, style)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(contentText)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
        applyStyle(builder, style, tapPending)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, builder.build())
    }

    suspend fun showMedStepReminder(
        context: Context,
        stepId: String,
        medName: String,
        medNote: String
    ) {
        val prefs = NotificationPreferences.from(context)
        val enabled = prefs.medicationRemindersEnabled.first()
        if (!enabled) {
            Log.d("NotificationHelper", "Medication reminders disabled — skipping step=$stepId")
            return
        }
        createMedicationChannel(context)
        val style = currentStyle(context)
        val channelId = channelIdFor(BASE_MED_CHANNEL_ID, style)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            stepId.hashCode() + 400_000,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (medNote.isNotEmpty()) medNote else "$medName \u2014 whenever you're ready."

        val builder = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$medName \u2014 Heads Up")
            .setContentText(contentText)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
        applyStyle(builder, style, tapPending)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(stepId.hashCode() + 400_000, builder.build())
    }

    private suspend fun createTimerChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val style = currentStyle(context)
        deleteStaleChannels(context, BASE_TIMER_CHANNEL_ID, style)
        val channel = buildChannel(
            context = context,
            id = channelIdFor(BASE_TIMER_CHANNEL_ID, style),
            name = TIMER_CHANNEL_NAME,
            description = "Alerts when a Timer countdown completes",
            style = style
        )
        manager.createNotificationChannel(channel)
        recordImportance(context, style.importance)
    }

    suspend fun showTimerCompleteNotification(context: Context, mode: String) {
        val prefs = NotificationPreferences.from(context)
        val enabled = prefs.timerAlertsEnabled.first()
        if (!enabled) {
            Log.d("NotificationHelper", "Timer alerts disabled — skipping mode=$mode")
            return
        }
        createTimerChannel(context)
        val style = currentStyle(context)
        val channelId = channelIdFor(BASE_TIMER_CHANNEL_ID, style)

        val isBreak = mode.equals("BREAK", ignoreCase = true)
        val title = if (isBreak) "Break complete" else "Timer complete"
        val buzzUntilDismissed = TimerPreferences(context).getBuzzUntilDismissed().first()
        val body = when {
            buzzUntilDismissed -> TimerBuzzerDismissReceiver.BUZZ_BODY_TEXT
            isBreak -> "Ready to get back to focus?"
            else -> "Nice work \u2014 time for a break."
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            TIMER_NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)

        if (buzzUntilDismissed) {
            // setAutoCancel fires on tap but not the delete intent, so the
            // content intent must also dismiss the buzz — otherwise opening
            // the app leaves the vibration running.
            val tapDismissPending = TimerBuzzerDismissReceiver
                .pendingIntent(context, TIMER_NOTIFICATION_ID, launchApp = true)
            val swipeDismissPending = TimerBuzzerDismissReceiver
                .pendingIntent(context, TIMER_NOTIFICATION_ID, launchApp = false)
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
        applyStyle(builder, style, tapPending)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(TIMER_NOTIFICATION_ID, builder.build())

        if (buzzUntilDismissed) {
            startContinuousBuzz(context)
        }
    }

    /**
     * Starts a looping vibration that plays until
     * [TimerBuzzerDismissReceiver] cancels it. Intentionally separate from
     * the channel-level vibration, since channel patterns only fire once
     * per notification post.
     *
     * If the user has an active [NotificationProfile] with a custom
     * vibration pattern, that pattern is used; otherwise the default
     * [CONTINUOUS_BUZZ_PATTERN] is played (C6).
     */
    internal suspend fun startContinuousBuzz(context: Context) {
        val pattern = activeProfileVibrationPattern(context) ?: CONTINUOUS_BUZZ_PATTERN
        VibrationAdapter.playNow(
            context = context,
            pattern = pattern,
            intensity = VibrationIntensity.STRONG,
            continuous = true
        )
    }

    private fun formatInterval(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    // =========================================================================
    //                       Profile-aware notifications
    // =========================================================================

    /**
     * Posts a task reminder using the supplied [NotificationProfile]
     * rather than the global delivery style.
     *
     * This is the new entry point used by the customizable notification
     * system; the legacy [showTaskReminder] is preserved so un-migrated
     * callers keep working.
     *
     * - Lock-screen visibility, display mode, accent color, per-profile
     *   sound, vibration, and silent override are honored.
     * - The channel ID is namespaced by profile.id so two profiles can
     *   have distinct immutable delivery settings side-by-side.
     */
    suspend fun showTaskReminderFor(
        context: Context,
        profile: NotificationProfile,
        taskId: Long,
        taskTitle: String,
        taskDescription: String?
    ) {
        val prefs = NotificationPreferences.from(context)
        val enabled = prefs.taskRemindersEnabled.first()
        if (!enabled) {
            Log.d("NotificationHelper", "Task reminders disabled — skipping task=$taskId")
            return
        }
        // MH-first G4 pause-all gate. Profile-aware path still routes
        // through the same gate as the legacy [showTaskReminder].
        if (pauseGateOf(context).isPausedNow()) {
            Log.d("NotificationHelper", "Pause-all active — skipping task=$taskId")
            return
        }
        // Rest-day suppression (MH-First audit § G3). Profile-aware path
        // mirrors the legacy showTaskReminder gate so both routes stay
        // consistent.
        if (RestDayGate.shouldSuppress(context)) {
            Log.d("NotificationHelper", "Rest day in effect — suppressing task=$taskId (profile path)")
            return
        }
        val channelId = ensureProfileChannel(
            context = context,
            baseId = BASE_CHANNEL_ID,
            channelName = CHANNEL_NAME,
            description = "Reminders for upcoming tasks",
            profile = profile
        )

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completeIntent = Intent(context, CompleteTaskReceiver::class.java).apply {
            putExtra("taskId", taskId)
        }
        val completePending = PendingIntent.getBroadcast(
            context,
            taskId.toInt() + 100_000,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$taskTitle is coming up")
            .setContentText(taskDescription ?: "Ready when you are.")
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Complete",
                completePending
            )
        applyProfile(builder, profile, tapPending)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(taskId.toInt(), builder.build())
    }

    /**
     * Called by [EscalationBroadcastReceiver] when an escalation step
     * fires. Rebuilds the notification with a more intrusive style while
     * reusing the same notification ID so the heads-up just refreshes
     * in place.
     */
    fun showEscalatedTaskReminder(
        context: Context,
        taskId: Long,
        taskTitle: String,
        taskDescription: String?,
        stepAction: EscalationStepAction,
        tier: UrgencyTier,
        stepIndex: Int
    ) {
        val channelId = ensureEscalationChannel(context, stepAction, tier)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            taskId.toInt() + 500_000,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completeIntent = Intent(context, CompleteTaskReceiver::class.java).apply {
            putExtra("taskId", taskId)
        }
        val completePending = PendingIntent.getBroadcast(
            context,
            taskId.toInt() + 600_000 + stepIndex,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headline = when (stepAction) {
            EscalationStepAction.GENTLE_PING -> "Gentle nudge"
            EscalationStepAction.STANDARD_ALERT -> "Still pending"
            EscalationStepAction.LOUD_VIBRATE -> "Reminder"
            EscalationStepAction.FULL_SCREEN -> "Final reminder"
        }

        val builder = NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$headline \u2014 $taskTitle")
            .setContentText(taskDescription ?: "This task still needs your attention.")
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Complete",
                completePending
            )
            .setPriority(priorityForStep(stepAction))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (stepAction == EscalationStepAction.FULL_SCREEN) {
            builder.setFullScreenIntent(tapPending, true)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(taskId.toInt(), builder.build())
    }

    /**
     * Creates (or reuses) a [NotificationChannel] that matches the
     * profile's sound / vibration / importance / lock-screen visibility
     * signature. Channels are immutable after creation, so profile
     * changes create a new channel with an updated suffix and delete
     * the prior ones.
     */
    private fun ensureProfileChannel(
        context: Context,
        baseId: String,
        channelName: String,
        description: String,
        profile: NotificationProfile
    ): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = profileChannelId(baseId, profile)
        deleteStaleProfileChannels(context, baseId, profile, keepId = channelId)

        val importance = importanceForTier(profile.urgencyTier)
        val effective = if (profile.displayMode == NotificationDisplayMode.FULL_SCREEN || profile.volumeOverride) {
            maxOf(importance, NotificationManager.IMPORTANCE_HIGH)
        } else {
            importance
        }

        val channel = NotificationChannel(channelId, channelName, effective).apply {
            this.description = description

            // Sound
            if (profile.silent) {
                setSound(null, null)
            } else if (profile.volumeOverride) {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarmUri, attrs)
                setBypassDnd(true)
            } else {
                val sound = SoundResolver.resolve(context, profile.soundId)
                when (sound) {
                    is SoundResolver.SilentChoice -> setSound(null, null)
                    is SoundResolver.UriChoice -> {
                        val attrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        setSound(sound.uri, attrs)
                    }
                }
            }

            // Vibration
            val pattern = VibrationAdapter.patternFor(profile)
            enableVibration(pattern != null)
            if (pattern != null) {
                vibrationPattern = pattern
            }

            // Lock-screen visibility
            lockscreenVisibility = when (profile.lockScreenVisibility) {
                LockScreenVisibility.SHOW_ALL -> NotificationCompat.VISIBILITY_PUBLIC
                LockScreenVisibility.APP_NAME_ONLY -> NotificationCompat.VISIBILITY_PRIVATE
                LockScreenVisibility.HIDDEN -> NotificationCompat.VISIBILITY_SECRET
            }

            // Accent color
            profile.accentColorHex?.let { hex ->
                runCatching { android.graphics.Color.parseColor(hex) }
                    .getOrNull()
                    ?.let {
                        lightColor = it
                        enableLights(true)
                    }
            }

            // Badge behavior
            setShowBadge(profile.badgeMode != com.averycorp.prismtask.domain.model.notifications.BadgeMode.OFF)
        }
        manager.createNotificationChannel(channel)
        return channelId
    }

    private fun ensureEscalationChannel(
        context: Context,
        action: EscalationStepAction,
        tier: UrgencyTier
    ): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = "prismtask_escalation_${action.key}_${tier.key}"
        val importance = when (action) {
            EscalationStepAction.GENTLE_PING -> NotificationManager.IMPORTANCE_LOW
            EscalationStepAction.STANDARD_ALERT -> NotificationManager.IMPORTANCE_DEFAULT
            EscalationStepAction.LOUD_VIBRATE -> NotificationManager.IMPORTANCE_HIGH
            EscalationStepAction.FULL_SCREEN -> NotificationManager.IMPORTANCE_HIGH
        }
        val channel = NotificationChannel(id, "Escalation — ${action.label}", importance).apply {
            description = "Escalation step for ${tier.label} tier"
            enableVibration(action != EscalationStepAction.GENTLE_PING)
            if (action == EscalationStepAction.LOUD_VIBRATE || action == EscalationStepAction.FULL_SCREEN) {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarmUri, attrs)
            }
        }
        manager.createNotificationChannel(channel)
        return id
    }

    private fun applyProfile(
        builder: NotificationCompat.Builder,
        profile: NotificationProfile,
        tapPending: PendingIntent
    ) {
        builder.priority = when (profile.urgencyTier) {
            UrgencyTier.LOW -> NotificationCompat.PRIORITY_LOW
            UrgencyTier.MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
            UrgencyTier.HIGH -> NotificationCompat.PRIORITY_HIGH
            UrgencyTier.CRITICAL -> NotificationCompat.PRIORITY_MAX
        }
        builder.setVisibility(
            when (profile.lockScreenVisibility) {
                LockScreenVisibility.SHOW_ALL -> NotificationCompat.VISIBILITY_PUBLIC
                LockScreenVisibility.APP_NAME_ONLY -> NotificationCompat.VISIBILITY_PRIVATE
                LockScreenVisibility.HIDDEN -> NotificationCompat.VISIBILITY_SECRET
            }
        )
        if (profile.volumeOverride) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        }
        if (profile.displayMode == NotificationDisplayMode.FULL_SCREEN) {
            builder.setFullScreenIntent(tapPending, true)
        }
        if (profile.displayMode == NotificationDisplayMode.PERSISTENT_BANNER) {
            builder.setOngoing(true)
            builder.setAutoCancel(false)
        }
        profile.accentColorHex?.let { hex ->
            runCatching { android.graphics.Color.parseColor(hex) }
                .getOrNull()
                ?.let { builder.color = it }
        }
    }

    private fun importanceForTier(tier: UrgencyTier): Int = when (tier) {
        UrgencyTier.LOW -> NotificationManager.IMPORTANCE_LOW
        UrgencyTier.MEDIUM -> NotificationManager.IMPORTANCE_DEFAULT
        UrgencyTier.HIGH -> NotificationManager.IMPORTANCE_HIGH
        UrgencyTier.CRITICAL -> NotificationManager.IMPORTANCE_HIGH
    }

    private fun priorityForStep(action: EscalationStepAction): Int = when (action) {
        EscalationStepAction.GENTLE_PING -> NotificationCompat.PRIORITY_LOW
        EscalationStepAction.STANDARD_ALERT -> NotificationCompat.PRIORITY_DEFAULT
        EscalationStepAction.LOUD_VIBRATE -> NotificationCompat.PRIORITY_HIGH
        EscalationStepAction.FULL_SCREEN -> NotificationCompat.PRIORITY_MAX
    }

    /** Deterministic channel id per profile so signature changes create a new channel. */
    fun profileChannelId(base: String, profile: NotificationProfile): String {
        val signature = buildString {
            append('_').append(profile.id)
            append('_').append(profile.urgencyTier.key)
            append('_').append(profile.soundId.hashCode())
            append('_').append(profile.vibrationPreset.key)
            append('_').append(profile.vibrationRepeatCount)
            if (profile.silent) append("_silent")
            if (profile.volumeOverride) append("_alrm")
            if (profile.displayMode == NotificationDisplayMode.FULL_SCREEN) append("_fsi")
            append('_').append(profile.lockScreenVisibility.key)
        }
        return base + signature
    }

    private fun deleteStaleProfileChannels(
        context: Context,
        base: String,
        profile: NotificationProfile,
        keepId: String
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val prefix = "${base}_${profile.id}_"
        manager.notificationChannels
            .filter { it.id.startsWith(prefix) && it.id != keepId }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }
}
