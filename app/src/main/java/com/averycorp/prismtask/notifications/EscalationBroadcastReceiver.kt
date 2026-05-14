package com.averycorp.prismtask.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.averycorp.prismtask.domain.model.notifications.EscalationStepAction
import com.averycorp.prismtask.domain.model.notifications.UrgencyTier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
 *
 * MH-first G4: consults [NotificationPauseGate] before posting; the
 * whole chain is task-class, so an active pause-all silences all
 * remaining steps. Medication exemption is enforced at call-site
 * granularity, not channel-by-channel — escalation never runs on
 * medication-class notifications.
 */
class EscalationBroadcastReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface PauseEntryPoint {
        fun notificationPauseGate(): NotificationPauseGate
    }

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

        val pauseGate = EntryPointAccessors
            .fromApplication(context.applicationContext, PauseEntryPoint::class.java)
            .notificationPauseGate()

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                if (pauseGate.isPausedNow()) {
                    Log.d(TAG, "Pause-all active — skipping escalation step $stepIndex task=$taskId")
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post escalation step $stepIndex task=$taskId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "EscalationReceiver"
    }
}
