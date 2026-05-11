package com.averycorp.prismtask.ui.screens.batch

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits "a batch just landed — please offer Undo" events from
 * BatchPreviewScreen to whoever is currently hosting the user (Today
 * screen, in v1.4.x). Decouples the full-screen preview from the
 * Snackbar host so the preview can pop its own back-stack entry without
 * needing a navigator-args-back path.
 */
@Singleton
class BatchUndoEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<BatchAppliedEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BatchAppliedEvent> = _events.asSharedFlow()

    /**
     * Returns true if the event was buffered. Drops events when no
     * subscriber is active and the buffer is full — that's correct: the
     * Snackbar offer is a "best-effort within the next 30s" affordance,
     * not a durable promise.
     */
    fun notifyApplied(event: BatchAppliedEvent): Boolean = _events.tryEmit(event)
}

/**
 * Payload describing a freshly-committed batch. The receiver shows a
 * Snackbar with an "Undo" action that calls
 * `BatchOperationsRepository.undoBatch(batchId)`.
 */
data class BatchAppliedEvent(val batchId: String, val commandText: String, val appliedCount: Int, val skippedCount: Int)
