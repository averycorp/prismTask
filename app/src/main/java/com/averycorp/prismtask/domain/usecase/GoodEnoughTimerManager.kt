package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.preferences.GoodEnoughEscalation
import com.averycorp.prismtask.data.preferences.GoodEnoughTimerConfig
import com.averycorp.prismtask.data.preferences.NdPreferences

/**
 * Manages Good Enough Timer state for a single task editing session.
 * Tracks cumulative editing time and determines when/how to escalate.
 */
class GoodEnoughTimerManager(timerConfig: GoodEnoughTimerConfig = GoodEnoughTimerConfig()) {
    private var sessionStartTimeMs: Long = 0L
    private var isPaused: Boolean = true
    private var accumulatedMs: Long = 0L
    private var lastNudgeAtMs: Long = 0L
    private var extensionGrantedMs: Long = 0L

    /** Grace period — no nudge in the first N minutes of editing. */
    private val gracePeriodMs = timerConfig.gracePeriodMinutes * 60 * 1000L

    /** Re-nudge cooldown after dismissing a NUDGE-level snackbar. */
    private val nudgeCooldownMs = timerConfig.nudgeCooldownMinutes * 60 * 1000L

    /** Re-nudge cooldown after dismissing a DIALOG-level dialog. */
    private val dialogCooldownMs = timerConfig.dialogCooldownMinutes * 60 * 1000L

    /** Extension time granted by the "more minutes" buttons. */
    private val extensionMs = timerConfig.extensionMinutes * 60 * 1000L

    fun startTracking(previousCumulativeMinutes: Int) {
        accumulatedMs = previousCumulativeMinutes * 60 * 1000L
        resume()
    }

    fun resume() {
        if (isPaused) {
            sessionStartTimeMs = System.currentTimeMillis()
            isPaused = false
        }
    }

    fun pause() {
        if (!isPaused) {
            accumulatedMs += System.currentTimeMillis() - sessionStartTimeMs
            isPaused = true
        }
    }

    fun getTotalEditingMinutes(): Int {
        val currentMs = if (isPaused) {
            accumulatedMs
        } else {
            accumulatedMs + (System.currentTimeMillis() - sessionStartTimeMs)
        }
        return (currentMs / 60_000).toInt()
    }

    /**
     * Check if the timer should fire based on the current editing state.
     * Returns the appropriate [TimerEvent] or null if no action needed.
     */
    fun check(
        ndPrefs: NdPreferences,
        taskGoodEnoughMinutesOverride: Int?
    ): TimerEvent? {
        if (!ndPrefs.focusReleaseModeEnabled) return null
        if (!ndPrefs.goodEnoughTimersEnabled) return null

        val thresholdMinutes = taskGoodEnoughMinutesOverride
            ?: ndPrefs.defaultGoodEnoughMinutes
        if (thresholdMinutes <= 0) return null // "No timer" for this task

        val currentMs = if (isPaused) {
            accumulatedMs
        } else {
            accumulatedMs + (System.currentTimeMillis() - sessionStartTimeMs)
        }

        val thresholdMs = (thresholdMinutes * 60 * 1000L) + extensionGrantedMs

        // Grace period
        if (currentMs < gracePeriodMs) return null

        // Not past threshold yet
        if (currentMs < thresholdMs) return null

        // Already nudged recently?
        val now = System.currentTimeMillis()
        val cooldown = when (ndPrefs.goodEnoughEscalation) {
            GoodEnoughEscalation.NUDGE -> nudgeCooldownMs
            GoodEnoughEscalation.DIALOG -> dialogCooldownMs
            GoodEnoughEscalation.LOCK -> 0L // Lock doesn't have cooldown
        }
        if (lastNudgeAtMs > 0 && (now - lastNudgeAtMs) < cooldown) return null

        lastNudgeAtMs = now
        return when (ndPrefs.goodEnoughEscalation) {
            GoodEnoughEscalation.NUDGE -> TimerEvent.Nudge(getTotalEditingMinutes())
            GoodEnoughEscalation.DIALOG -> TimerEvent.Dialog(getTotalEditingMinutes())
            GoodEnoughEscalation.LOCK -> TimerEvent.Lock(getTotalEditingMinutes())
        }
    }

    fun grantExtension() {
        extensionGrantedMs += extensionMs
        lastNudgeAtMs = 0L // Reset so it can fire again after extension
    }

    /**
     * Returns the progress fraction (0.0 to 1.0+) of the timer.
     * Used for the visual timer indicator color shift.
     */
    fun getProgress(ndPrefs: NdPreferences, taskOverrideMinutes: Int?): Float {
        val thresholdMinutes = taskOverrideMinutes
            ?: ndPrefs.defaultGoodEnoughMinutes
        if (thresholdMinutes <= 0) return 0f
        val totalMinutes = getTotalEditingMinutes()
        return (totalMinutes.toFloat() / thresholdMinutes).coerceIn(0f, 1.5f)
    }
}

sealed class TimerEvent {
    abstract val editingMinutes: Int

    data class Nudge(override val editingMinutes: Int) : TimerEvent()

    data class Dialog(override val editingMinutes: Int) : TimerEvent()

    data class Lock(override val editingMinutes: Int) : TimerEvent()
}
