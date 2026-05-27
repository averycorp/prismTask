package com.averycorp.prismtask.ui.screens.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTinyCoordinatorTest {

    @Test
    fun `request arms the pending task id`() {
        val coordinator = ResumeTinyCoordinator()
        assertNull(coordinator.pendingTaskId.value)
        coordinator.request(42L)
        assertEquals(42L, coordinator.pendingTaskId.value)
    }

    @Test
    fun `consume clears the pending request`() {
        val coordinator = ResumeTinyCoordinator()
        coordinator.request(42L)
        coordinator.consume()
        assertNull(coordinator.pendingTaskId.value)
    }

    @Test
    fun `latest request wins`() {
        val coordinator = ResumeTinyCoordinator()
        coordinator.request(1L)
        coordinator.request(2L)
        assertEquals(2L, coordinator.pendingTaskId.value)
    }
}
