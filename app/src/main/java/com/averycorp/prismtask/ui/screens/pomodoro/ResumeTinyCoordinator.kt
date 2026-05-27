package com.averycorp.prismtask.ui.screens.pomodoro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dormancy Re-Entry: process-scoped hand-off for "Resume 5 min" requests.
 *
 * Decouples the trigger (the Today "Ready to Resume" CTA, or the widget deep
 * link `?action=resume_tiny&taskId=`) from [SmartPomodoroViewModel], which has
 * no nav arguments of its own. A trigger calls [request]; the Pomodoro VM
 * observes [pendingTaskId] and, once it has navigated/started, calls [consume].
 *
 * Singleton so the request survives the navigation that creates the VM.
 */
@Singleton
class ResumeTinyCoordinator @Inject constructor() {
    private val _pendingTaskId = MutableStateFlow<Long?>(null)
    val pendingTaskId: StateFlow<Long?> = _pendingTaskId.asStateFlow()

    /** Arm a Resume Tiny session for [taskId]. Overwrites any prior pending request. */
    fun request(taskId: Long) {
        _pendingTaskId.value = taskId
    }

    /** Clear the pending request once it has been started. Idempotent. */
    fun consume() {
        _pendingTaskId.value = null
    }
}
