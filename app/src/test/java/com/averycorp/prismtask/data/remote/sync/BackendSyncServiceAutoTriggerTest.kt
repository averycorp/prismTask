package com.averycorp.prismtask.data.remote.sync

import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.local.dao.DailyEssentialSlotCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.SyncMetadataDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.preferences.BackendSyncPreferences
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.UserInfoResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the `isSyncing` guard added to
 * [BackendSyncService.fullSync] so the new sign-in / reactive /
 * periodic auto-triggers can never double-fire the same push cycle.
 *
 * The class is heavy (20 deps), so the fixture relaxed-mocks everything
 * and pins only the calls the guard actually exercises:
 *  • `isConnected()` → access-token lookup returns a fake token.
 *  • `checkAdminStatus()` → `api.getMe()` returns a stub response.
 *  • The leisure-session sync is wedged on a `CompletableDeferred` so the
 *    first call blocks long enough to observe a concurrent second call.
 */
class BackendSyncServiceAutoTriggerTest {

    private fun newService(
        leisureSessionSyncService: LeisureSessionSyncService
    ): BackendSyncService {
        val authTokenPreferences = mockk<AuthTokenPreferences>(relaxed = true)
        coEvery { authTokenPreferences.getAccessToken() } returns "fake-token"
        val api = mockk<PrismTaskApi>(relaxed = true)
        coEvery { api.getMe() } returns UserInfoResponse(
            id = 1,
            email = "test@example.com",
            name = "Test User",
            tier = "FREE",
            isAdmin = false,
            effectiveTier = "FREE"
        )
        return BackendSyncService(
            api = api,
            taskDao = mockk<TaskDao>(relaxed = true),
            projectDao = mockk<ProjectDao>(relaxed = true),
            tagDao = mockk<TagDao>(relaxed = true),
            habitDao = mockk<HabitDao>(relaxed = true),
            habitCompletionDao = mockk<HabitCompletionDao>(relaxed = true),
            taskTemplateDao = mockk<TaskTemplateDao>(relaxed = true),
            slotCompletionDao = mockk<DailyEssentialSlotCompletionDao>(relaxed = true),
            medicationDao = mockk<MedicationDao>(relaxed = true),
            medicationSlotDao = mockk<MedicationSlotDao>(relaxed = true),
            medicationTierStateDao = mockk<MedicationTierStateDao>(relaxed = true),
            authTokenPreferences = authTokenPreferences,
            backendSyncPreferences = mockk<BackendSyncPreferences>(relaxed = true),
            templatePreferences = mockk<TemplatePreferences>(relaxed = true),
            billingManager = mockk<BillingManager>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            logger = mockk(relaxed = true),
            syncStateRepository = mockk<SyncStateRepository>(relaxed = true),
            syncMetadataDao = mockk<SyncMetadataDao>(relaxed = true),
            leisureSyncService = mockk<LeisureSyncService>(relaxed = true),
            leisureSessionSyncService = leisureSessionSyncService
        )
    }

    @Test
    fun `fullSync re-entrant call returns failure with already-in-progress`() = runTest {
        val gate = CompletableDeferred<Int>()
        val sessionSync = mockk<LeisureSessionSyncService>(relaxed = true)
        // Wedge the first fullSync inside the leisure-session step so the
        // second call observes isSyncing == true.
        coEvery { sessionSync.sync() } coAnswers { gate.await() }

        val service = newService(leisureSessionSyncService = sessionSync)

        val first = async { service.fullSync(trigger = "first") }
        // Yield enough for `first` to enter and reach the wedged step.
        advanceTimeBy(100)

        val second = service.fullSync(trigger = "second")
        assertTrue("expected re-entrant call to be Result.failure", second.isFailure)
        val cause = second.exceptionOrNull()
        assertNotNull(cause)
        assertTrue(
            "expected IllegalStateException with already-in-progress message, got $cause",
            cause is IllegalStateException &&
                cause.message?.contains("already in progress") == true
        )

        // Release the wedge so the first call can finish; verify the flag
        // actually clears and a third call after settle is admitted.
        gate.complete(0)
        first.await()
        val third = service.fullSync(trigger = "third")
        assertFalse(
            "expected third fullSync (post-settle) to pass the isSyncing gate",
            third.isFailure &&
                (third.exceptionOrNull()?.message?.contains("already in progress") == true)
        )
    }
}
