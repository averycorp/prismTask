package com.averycorp.prismtask.ui.screens.batch

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the replay-1 timing contract introduced by Test 1.6 (May 10,
 * 2026). When BatchPreviewViewModel.approve() emits via the bus, the
 * Today destination is still in the back stack underneath the
 * BatchPreview composable — Today's `LaunchedEffect(batchUndoListener)`
 * is NOT collecting `events`. The bus must hold the emission so the
 * post-pop Today recomposition can still surface the Undo Snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchUndoEventBusTest {

    @Test
    fun lateSubscriber_receivesMostRecentEmittedEvent() = runTest {
        // Simulates Test 1.6's BatchPreview→pop→Today gap: emit fires
        // with zero subscribers, the (later) subscriber must still see it.
        val bus = BatchUndoEventBus()
        val event = BatchAppliedEvent(
            batchId = "batch-late",
            commandText = "complete all tasks today",
            appliedCount = 3,
            skippedCount = 0
        )

        bus.notifyApplied(event)

        bus.events.test {
            assertEquals(event, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun acknowledge_dropsReplayCacheSoFutureSubscribersGetNothing() = runTest {
        // Once the Today listener has shown the Snackbar, the cached
        // replay event must be cleared. Otherwise navigating away and
        // back to Today (e.g. tabbing to Tasks and returning) would
        // redeliver a stale "X changes applied" toast.
        val bus = BatchUndoEventBus()
        bus.notifyApplied(
            BatchAppliedEvent(
                batchId = "batch-ack",
                commandText = "anything",
                appliedCount = 1,
                skippedCount = 0
            )
        )

        // First subscriber drains the event.
        bus.events.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        bus.acknowledge()

        // Second subscriber must NOT see the stale event.
        bus.events.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun secondEmissionAfterAcknowledge_isDeliveredToNewSubscriber() = runTest {
        // Regression: acknowledge must not put the bus into a permanently
        // muted state. A subsequent batch landing must still surface its
        // own Snackbar event.
        val bus = BatchUndoEventBus()
        bus.notifyApplied(
            BatchAppliedEvent(
                batchId = "batch-first",
                commandText = "first",
                appliedCount = 1,
                skippedCount = 0
            )
        )
        bus.events.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        bus.acknowledge()

        val second = BatchAppliedEvent(
            batchId = "batch-second",
            commandText = "second",
            appliedCount = 2,
            skippedCount = 0
        )
        bus.notifyApplied(second)
        bus.events.test {
            assertEquals(second, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
