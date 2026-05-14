package com.averycorp.prismtask.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.averycorp.prismtask.data.diagnostics.DiagnosticLogger
import com.averycorp.prismtask.domain.usecase.RecentMoodSignal
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderBroadcastReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderEntryPoint {
        fun recentMoodSignal(): RecentMoodSignal

        fun diagnosticLogger(): DiagnosticLogger
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra("taskId", -1L)
        if (taskId == -1L) return

        val title = intent.getStringExtra("taskTitle") ?: "Gentle Nudge"
        val description = intent.getStringExtra("taskDescription")

        Log.d("ReminderReceiver", "Alarm fired for task=$taskId title=$title")

        // showTaskReminder reads DataStore preferences for delivery style;
        // goAsync() keeps the receiver alive while the suspend call runs on
        // the IO dispatcher instead of blocking the Main thread.
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ReminderEntryPoint::class.java
                )
                // Mental-Health-First § G7 — task reminders are
                // non-critical cadence; suppress when the user has
                // logged ≤2/5 mood within the last 48h. Medication
                // reminders flow through a different receiver path and
                // are never gated.
                if (entryPoint.recentMoodSignal().isLowMoodWithin()) {
                    entryPoint.diagnosticLogger().info(
                        tag = "MoodGate",
                        message = "Task reminder $taskId skipped — recent low mood within 48h"
                    )
                    return@launch
                }
                NotificationHelper.showTaskReminder(context, taskId, title, description)
            } catch (e: Exception) {
                Log.e("ReminderReceiver", "Failed to show reminder task=$taskId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
