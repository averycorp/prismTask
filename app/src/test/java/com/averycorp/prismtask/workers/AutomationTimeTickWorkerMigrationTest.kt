package com.averycorp.prismtask.workers

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Migration regression-gate for the
 * [AutomationTimeTickWorker]'s `PeriodicWork(15min)` →
 * per-minute OneTimeWork chain swap (minute-cadence Phase 2, Path C).
 *
 * The migration relies on WorkManager treating
 * [AutomationTimeTickWorker.UNIQUE_WORK_NAME] as a dedup key regardless
 * of the underlying request type — `enqueueUniqueWork(..., REPLACE,
 * ...)` collapses any prior `PeriodicWork` registered under the same
 * name. Pinning this contract:
 *
 *  1. `UNIQUE_WORK_NAME` matches the legacy string `automation_time_tick`
 *     so first-launch-after-update collapses the prior registration.
 *  2. `schedule(context)` enqueues exactly one work item under that name
 *     with a positive initial delay.
 *
 * Robolectric + [WorkManagerTestInitHelper] gives a synchronous,
 * in-memory WorkManager that can be inspected without touching the OS
 * scheduler.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class AutomationTimeTickWorkerMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun uniqueWorkName_matchesLegacyPeriodicWorkRegistration() {
        // First-launch-after-update relies on this exact string matching
        // the name used by the prior PeriodicWork(15min) call site at
        // PrismTaskApplication.kt — change this and existing installs
        // will end up with two scheduling chains side-by-side.
        assertEquals("automation_time_tick", AutomationTimeTickWorker.UNIQUE_WORK_NAME)
    }

    @Test
    fun schedule_enqueuesExactlyOneOneTimeWorkUnderUniqueName() {
        AutomationTimeTickWorker.schedule(context)

        val infos = WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(AutomationTimeTickWorker.UNIQUE_WORK_NAME)
            .get()

        assertNotNull(infos)
        assertEquals(1, infos.size)
        // ENQUEUED is the post-`enqueueUniqueWork` state before the
        // delay elapses — the chain is alive and waiting.
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun schedule_replacesPreviousEnqueueUnderSameUniqueName() {
        // Two consecutive schedule() calls — the second replaces the
        // first under REPLACE policy. The unique-work registry must
        // still hold exactly one entry, not stack up duplicates.
        AutomationTimeTickWorker.schedule(context)
        AutomationTimeTickWorker.schedule(context)

        val infos = WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(AutomationTimeTickWorker.UNIQUE_WORK_NAME)
            .get()

        // After REPLACE, there's exactly one ENQUEUED row (the latest
        // scheduling); the prior one is in CANCELLED/SUCCEEDED state
        // depending on WorkManager internals — what we lock is that we
        // never end up with two ENQUEUED entries which would double the
        // wakeup rate.
        val enqueued = infos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, enqueued.size)
        assertFalse(
            "no two ENQUEUED chains should coexist after REPLACE",
            infos.count { it.state == WorkInfo.State.ENQUEUED } > 1
        )
        assertTrue(
            "at least one entry expected; got ${infos.size}",
            infos.isNotEmpty()
        )
    }
}
