package com.averycorp.prismtask.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.averycorp.prismtask.domain.model.notifications.EscalationChain
import com.averycorp.prismtask.domain.model.notifications.UrgencyTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the sequence of escalating re-alerts described by an
 * [EscalationChain] for a given task.
 *
 * Each step is registered as a separate exact alarm fired via
 * [EscalationBroadcastReceiver]; the step action (gentle / standard /
 * loud / full-screen) is carried in the intent extras so the receiver
 * can ask [NotificationHelper] for the right delivery style.
 *
 * Request codes use a base offset + step index so cancellation can
 * target the specific alarm even after the app is backgrounded.
 */
@Singleton
class EscalationScheduler
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    /**
     * Registers all applicable escalation steps. Pre-existing alarms for
     * the same [taskId] are cleared first so a reschedule doesn't stack.
     */
    fun schedule(
        taskId: Long,
        taskTitle: String,
        taskDescription: String?,
        initialFireAt: Long,
        tier: UrgencyTier,
        chain: EscalationChain
    ): Int {
        cancel(taskId)
        if (!chain.enabled) return 0

        val offsets = chain.absoluteOffsets(tier)
        offsets.forEachIndexed { index, offsetMs ->
            val fireAt = initialFireAt + offsetMs
            val stepAction = chain.steps.getOrNull(index)?.action ?: return@forEachIndexed
            val intent = Intent(context, EscalationBroadcastReceiver::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TITLE, taskTitle)
                putExtra(EXTRA_DESCRIPTION, taskDescription)
                putExtra(EXTRA_STEP_ACTION, stepAction.key)
                putExtra(EXTRA_STEP_INDEX, index)
                putExtra(EXTRA_TIER, tier.key)
            }
            val requestCode = requestCodeFor(taskId, index)
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            ExactAlarmHelper.scheduleExact(context, fireAt, pending)
        }
        return offsets.size
    }

    /** Cancels every queued escalation alarm for [taskId]. */
    fun cancel(taskId: Long) {
        repeat(MAX_STEPS) { index ->
            val intent = Intent(context, EscalationBroadcastReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                requestCodeFor(taskId, index),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager?.cancel(pending)
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "taskTitle"
        const val EXTRA_DESCRIPTION = "taskDescription"
        const val EXTRA_STEP_ACTION = "stepAction"
        const val EXTRA_STEP_INDEX = "stepIndex"
        const val EXTRA_TIER = "tier"

        /** Offset range reserved for escalation alarms (per task). */
        private const val BASE_REQUEST_CODE = 900_000
        private const val MAX_STEPS = 10

        /**
         * Pure helper: the stable request code for a given task + step.
         *
         * Using (taskId * MAX_STEPS + index) + BASE so two tasks never
         * collide, and any given step of the same task always maps to
         * the same code (so cancel() actually cancels).
         */
        fun requestCodeFor(taskId: Long, stepIndex: Int): Int {
            val step = stepIndex.coerceIn(0, MAX_STEPS - 1)
            return BASE_REQUEST_CODE + (taskId.toInt() * MAX_STEPS + step)
        }
    }
}
