package com.averycorp.prismtask.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.averycorp.prismtask.domain.model.notifications.EscalationStepAction
import com.averycorp.prismtask.domain.model.notifications.UrgencyTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires each queued step of an escalation chain. Completely stateless —
 * the intent carries everything needed to repost the notification with
 * the correct delivery style.
 *
 * Triggered via the alarm registered by [EscalationScheduler.schedule].
 */
class EscalationBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EscalationScheduler.EXTRA_TASK_ID, -1L)
        if (taskId < 0) return
        val title = intent.getStringExtra(EscalationScheduler.EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EscalationScheduler.EXTRA_DESCRIPTION)
        val actionKey = intent.getStringExtra(EscalationScheduler.EXTRA_STEP_ACTION)
        val tierKey = intent.getStringExtra(EscalationScheduler.EXTRA_TIER)
        val stepIndex = intent.getIntExtra(EscalationScheduler.EXTRA_STEP_INDEX, 0)

        val action = EscalationStepAction.fromKey(actionKey) ?: EscalationStepAction.STANDARD_ALERT
        val tier = UrgencyTier.fromKey(tierKey)

        Log.d(
            TAG,
            "Escalation step $stepIndex fired for task=$taskId action=${action.key} tier=${tier.key}"
        )

        // Rest-day suppression (MH-First audit § G3). Escalation steps
        // walk a task reminder up in urgency — but the whole reminder
        // pause applies to the underlying task path. goAsync() keeps the
        // receiver alive while the suspend gate check runs.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                if (RestDayGate.shouldSuppress(context)) {
                    Log.d(TAG, "Rest day — suppressing escalation step taskId=$taskId step=$stepIndex")
                    return@launch
                }
                NotificationHelper.showEscalatedTaskReminder(
                    context = context,
                    taskId = taskId,
                    taskTitle = title,
                    taskDescription = description,
                    stepAction = action,
                    tier = tier,
                    stepIndex = stepIndex
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "EscalationReceiver"
    }
}
