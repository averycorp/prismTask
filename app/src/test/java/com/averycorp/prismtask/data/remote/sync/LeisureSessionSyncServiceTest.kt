package com.averycorp.prismtask.data.remote.sync

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.remote.api.LeisureSessionCreateRequest
import com.averycorp.prismtask.data.remote.api.LeisureSessionRemoteResponse
import com.averycorp.prismtask.data.remote.api.LeisureSessionUpdateRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class LeisureSessionSyncServiceTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: PrismTaskDatabase
    private lateinit var api: PrismTaskApi
    private lateinit var service: LeisureSessionSyncService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room
            .inMemoryDatabaseBuilder(context, PrismTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = mockk(relaxed = true)
        service = LeisureSessionSyncService(
            api = api,
            sessionDao = database.leisureSessionDao(),
            activityDao = database.leisureActivityDao(),
            syncMetadataDao = database.syncMetadataDao(),
            logger = PrismSyncLogger()
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun push_create_mintsCloudIdAndPersistsLocally() = runTest {
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                category = "PHYSICAL",
                durationMinutes = 30,
                loggedAt = 1_715_000_000_000L,
                source = "MANUAL"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = "",
                pendingAction = "create"
            )
        )
        val createBody = slot<LeisureSessionCreateRequest>()
        coEvery { api.createLeisureSession(capture(createBody)) } answers {
            val req = createBody.captured
            LeisureSessionRemoteResponse(
                id = req.id,
                activityId = req.activityId,
                category = req.category,
                durationMinutes = req.durationMinutes,
                loggedAt = req.loggedAt,
                source = req.source,
                createdAt = "2026-05-12T00:00:00Z"
            )
        }
        coEvery { api.listLeisureSessions(any(), any()) } returns emptyList()

        service.sync()

        coVerify(exactly = 1) { api.createLeisureSession(any()) }
        val saved = database.leisureSessionDao().getById(localId)!!
        assertNotNull(saved.cloudId)
        assertTrue(saved.cloudId!!.isNotBlank())
        val meta = database.syncMetadataDao()
            .get(localId, LeisureSessionSyncService.ENTITY_TYPE)!!
        assertNull(meta.pendingAction)
        assertEquals(saved.cloudId, meta.cloudId)
        assertEquals(createBody.captured.id, saved.cloudId)
    }

    @Test
    fun push_create_translatesLocalActivityIdToCloudId() = runTest {
        val activityLocalId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                cloudId = "activity-cloud-1",
                name = "Walk",
                category = "PHYSICAL",
                enabled = true
            )
        )
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                activityId = activityLocalId,
                category = "PHYSICAL",
                durationMinutes = 20,
                loggedAt = 1_715_000_000_000L,
                source = "TIMER"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = "",
                pendingAction = "create"
            )
        )
        val createBody = slot<LeisureSessionCreateRequest>()
        coEvery { api.createLeisureSession(capture(createBody)) } answers {
            val req = createBody.captured
            LeisureSessionRemoteResponse(
                id = req.id,
                activityId = req.activityId,
                category = req.category,
                durationMinutes = req.durationMinutes,
                loggedAt = req.loggedAt,
                source = req.source,
                createdAt = "2026-05-12T00:00:00Z"
            )
        }
        coEvery { api.listLeisureSessions(any(), any()) } returns emptyList()

        service.sync()

        assertEquals("activity-cloud-1", createBody.captured.activityId)
    }

    @Test
    fun push_update_usesExistingCloudIdInPatch() = runTest {
        val cloudId = "session-cloud-123"
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                cloudId = cloudId,
                category = "SOCIAL",
                durationMinutes = 45,
                loggedAt = 1_715_111_111_111L,
                source = "MANUAL"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = cloudId,
                pendingAction = "update"
            )
        )
        val patchedId = slot<String>()
        val patchedBody = slot<LeisureSessionUpdateRequest>()
        coEvery {
            api.updateLeisureSession(capture(patchedId), capture(patchedBody))
        } answers {
            LeisureSessionRemoteResponse(
                id = patchedId.captured,
                activityId = null,
                category = "SOCIAL",
                durationMinutes = 45,
                loggedAt = patchedBody.captured.loggedAt ?: "2026-05-12T00:00:00Z",
                source = "MANUAL",
                createdAt = "2026-05-12T00:00:00Z"
            )
        }
        coEvery { api.listLeisureSessions(any(), any()) } returns emptyList()

        service.sync()

        assertEquals(cloudId, patchedId.captured)
        assertNotNull(patchedBody.captured.loggedAt)
        val meta = database.syncMetadataDao()
            .get(localId, LeisureSessionSyncService.ENTITY_TYPE)!!
        assertNull(meta.pendingAction)
    }

    @Test
    fun push_update_withoutCloudId_promotesToCreate() = runTest {
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                category = "CREATIVE",
                durationMinutes = 15,
                loggedAt = 1_715_000_000_000L,
                source = "MANUAL"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = "",
                pendingAction = "update"
            )
        )
        coEvery { api.createLeisureSession(any()) } answers {
            val req = firstArg<LeisureSessionCreateRequest>()
            LeisureSessionRemoteResponse(
                id = req.id,
                activityId = req.activityId,
                category = req.category,
                durationMinutes = req.durationMinutes,
                loggedAt = req.loggedAt,
                source = req.source,
                createdAt = "2026-05-12T00:00:00Z"
            )
        }
        coEvery { api.listLeisureSessions(any(), any()) } returns emptyList()

        service.sync()

        coVerify(exactly = 1) { api.createLeisureSession(any()) }
        coVerify(exactly = 0) { api.updateLeisureSession(any(), any()) }
    }

    @Test
    fun push_delete_callsApiAndClearsMetadata() = runTest {
        val cloudId = "session-cloud-del"
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                cloudId = cloudId,
                category = "PASSIVE",
                durationMinutes = 10,
                loggedAt = 1_715_000_000_000L,
                source = "MANUAL"
            )
        )
        database.leisureSessionDao().deleteById(localId)
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = cloudId,
                pendingAction = "delete"
            )
        )
        coEvery { api.deleteLeisureSession(cloudId) } returns
            Response.success<Unit>(204, Unit)
        coEvery { api.listLeisureSessions(any(), any()) } returns emptyList()

        service.sync()

        coVerify(exactly = 1) { api.deleteLeisureSession(cloudId) }
        val meta = database.syncMetadataDao()
            .get(localId, LeisureSessionSyncService.ENTITY_TYPE)
        assertNull(meta)
    }

    @Test
    fun pull_insertsNewRemoteRow_andTranslatesActivityCloudIdToLocal() = runTest {
        val activityLocalId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                cloudId = "activity-cloud-X",
                name = "Walk",
                category = "PHYSICAL",
                enabled = true
            )
        )
        coEvery { api.listLeisureSessions(any(), any()) } returns listOf(
            LeisureSessionRemoteResponse(
                id = "remote-session-1",
                activityId = "activity-cloud-X",
                category = "PHYSICAL",
                durationMinutes = 25,
                loggedAt = "2026-05-12T10:00:00Z",
                source = "TIMER",
                createdAt = "2026-05-12T10:00:00Z"
            )
        )

        service.sync()

        val rows = database.leisureSessionDao().getAllOnce()
        assertEquals(1, rows.size)
        assertEquals("remote-session-1", rows[0].cloudId)
        assertEquals(activityLocalId, rows[0].activityId)
        assertEquals(25, rows[0].durationMinutes)
        val meta = database.syncMetadataDao()
            .get(rows[0].id, LeisureSessionSyncService.ENTITY_TYPE)
        assertNotNull(meta)
        assertEquals("remote-session-1", meta!!.cloudId)
    }

    @Test
    fun pull_nullActivityIdWhenRemoteActivityNotInRoom() = runTest {
        // Activity cloud_id the local Room doesn't know about — the
        // pull should still land the session, just with a null FK.
        coEvery { api.listLeisureSessions(any(), any()) } returns listOf(
            LeisureSessionRemoteResponse(
                id = "remote-session-orphan",
                activityId = "missing-activity-cloud",
                category = "SOCIAL",
                durationMinutes = 15,
                loggedAt = "2026-05-12T10:00:00Z",
                source = "MANUAL",
                createdAt = "2026-05-12T10:00:00Z"
            )
        )

        service.sync()

        val saved = database.leisureSessionDao()
            .getByCloudIdOnce("remote-session-orphan")!!
        assertNull(saved.activityId)
        assertEquals("SOCIAL", saved.category)
    }

    @Test
    fun pull_updatesLoggedAtWhenRemoteChanged() = runTest {
        val cloudId = "session-cloud-moved"
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                cloudId = cloudId,
                category = "CREATIVE",
                durationMinutes = 30,
                loggedAt = 1_715_000_000_000L,
                source = "MANUAL"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = cloudId
            )
        )
        coEvery { api.listLeisureSessions(any(), any()) } returns listOf(
            LeisureSessionRemoteResponse(
                id = cloudId,
                activityId = null,
                category = "CREATIVE",
                durationMinutes = 30,
                loggedAt = "2026-05-12T10:00:00Z",
                source = "MANUAL",
                createdAt = "2026-05-12T10:00:00Z"
            )
        )

        service.sync()

        val saved = database.leisureSessionDao().getById(localId)!!
        assertEquals(
            isoToMillisOrNull("2026-05-12T10:00:00Z"),
            saved.loggedAt
        )
    }

    @Test
    fun pull_reconcilesRemoteSideDeletes_withinWindow() = runTest {
        val cloudId = "session-cloud-deleted-elsewhere"
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                cloudId = cloudId,
                category = "PHYSICAL",
                durationMinutes = 20,
                loggedAt = 1_715_000_000_000L,
                source = "MANUAL"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = cloudId,
                pendingAction = null
            )
        )
        // Remote list is empty AND smaller than PULL_LIMIT, so the
        // reconcile window is the full local history.
        coEvery { api.listLeisureSessions(any(), any()) } returns emptyList()

        service.sync()

        assertNull(database.leisureSessionDao().getById(localId))
        assertNull(
            database.syncMetadataDao()
                .get(localId, LeisureSessionSyncService.ENTITY_TYPE)
        )
    }

    @Test
    fun pull_preservesPendingLocalCreates_evenIfRemoteListIsEmpty() = runTest {
        val localId = database.leisureSessionDao().insert(
            LeisureSessionEntity(
                id = 0,
                cloudId = "pending-session-cloud",
                category = "PHYSICAL",
                durationMinutes = 10,
                loggedAt = 1_715_000_000_000L,
                source = "MANUAL"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSessionSyncService.ENTITY_TYPE,
                cloudId = "pending-session-cloud",
                pendingAction = "create"
            )
        )
        coEvery { api.createLeisureSession(any()) } throws
            RuntimeException("offline")
        coEvery { api.listLeisureSessions(any(), any()) } returns emptyList()

        service.sync()

        assertNotNull(database.leisureSessionDao().getById(localId))
    }
}
