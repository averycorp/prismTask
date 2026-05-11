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
 *
 * Test 1.6 (May 10, 2026) found the Snackbar never appeared because of a
 * timing gap: BatchPreview emits `notifyApplied` BEFORE the
 * `BatchEvent.Approved` collector pops the back stack. While BatchPreview
 * is still on top, Today's composition has been disposed (Compose
 * Navigation only composes the topmost destination), so Today's
 * `LaunchedEffect(batchUndoListener)` is NOT collecting `events`. With
 * `replay = 0` the emit goes to no subscribers and is lost; Today
 * re-subscribes after the pop but sees nothing.
 *
 * `replay = 1` keeps the most recent event for the next subscriber, which
 * is exactly the post-pop Today collector. `acknowledge()` clears the
 * cache so subsequent navigations back to Today don't re-show the
 * Snackbar.
 */
@Singleton
class BatchUndoEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<BatchAppliedEvent>(
        replay = 1,
        extraBufferCapacity = 4
    )
    val events: SharedFlow<BatchAppliedEvent> = _events.asSharedFlow()

    /**
     * Returns true if the event was buffered. With `replay = 1` the
     * latest event is held for whichever subscriber arrives next, which
     * defends the BatchPreview→pop→Today re-subscribe timing gap that
     * caused Test 1.6 to find no Snackbar.
     */
    fun notifyApplied(event: BatchAppliedEvent): Boolean = _events.tryEmit(event)

    /**
     * Drop the replay cache. Called by the consumer after showing the
     * Snackbar so re-navigating to Today later (for any unrelated
     * reason) doesn't redeliver the same "X changes applied" toast.
     */
    fun acknowledge() {
        _events.resetReplayCache()
    }
}

/**
 * Payload describing a freshly-committed batch. The receiver shows a
 * Snackbar with an "Undo" action that calls
 * `BatchOperationsRepository.undoBatch(batchId)`.
 */
data class BatchAppliedEvent(
    val batchId: String,
    val commandText: String,
    val appliedCount: Int,
    val skippedCount: Int
)
