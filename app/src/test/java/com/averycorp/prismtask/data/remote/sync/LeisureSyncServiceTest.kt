package com.averycorp.prismtask.data.remote.sync

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.remote.api.LeisureActivityCreateRequest
import com.averycorp.prismtask.data.remote.api.LeisureActivityRemoteResponse
import com.averycorp.prismtask.data.remote.api.LeisureActivityUpdateRequest
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
class LeisureSyncServiceTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: PrismTaskDatabase
    private lateinit var api: PrismTaskApi
    private lateinit var service: LeisureSyncService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room
            .inMemoryDatabaseBuilder(context, PrismTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = mockk(relaxed = true)
        service = LeisureSyncService(
            api = api,
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
        val localId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                name = "Walk",
                category = "PHYSICAL",
                defaultDurationMinutes = 20,
                enabled = true
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSyncService.ENTITY_TYPE,
                cloudId = "",
                pendingAction = "create"
            )
        )
        val createBody = slot<LeisureActivityCreateRequest>()
        coEvery { api.createLeisureActivity(capture(createBody)) } answers {
            val req = createBody.captured
            LeisureActivityRemoteResponse(
                id = req.id,
                name = req.name,
                category = req.category,
                defaultDurationMinutes = req.defaultDurationMinutes,
                enabled = req.enabled,
                createdAt = "2026-05-12T00:00:00Z",
                updatedAt = "2026-05-12T00:00:00Z"
            )
        }
        coEvery { api.listLeisureActivities(any()) } returns emptyList()

        service.sync()

        coVerify(exactly = 1) { api.createLeisureActivity(any()) }
        val saved = database.leisureActivityDao().getById(localId)!!
        assertNotNull(saved.cloudId)
        assertTrue(saved.cloudId!!.isNotBlank())
        val meta = database.syncMetadataDao().get(localId, LeisureSyncService.ENTITY_TYPE)!!
        assertNull(meta.pendingAction)
        assertEquals(saved.cloudId, meta.cloudId)
        assertEquals(createBody.captured.id, saved.cloudId)
    }

    @Test
    fun push_update_usesExistingCloudIdInPatch() = runTest {
        val cloudId = "cloud-123"
        val localId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                cloudId = cloudId,
                name = "Walk",
                category = "PHYSICAL",
                enabled = true
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSyncService.ENTITY_TYPE,
                cloudId = cloudId,
                pendingAction = "update"
            )
        )
        val patchedId = slot<String>()
        val patchedBody = slot<LeisureActivityUpdateRequest>()
        coEvery {
            api.updateLeisureActivity(capture(patchedId), capture(patchedBody))
        } answers {
            LeisureActivityRemoteResponse(
                id = patchedId.captured,
                name = patchedBody.captured.name ?: "Walk",
                category = patchedBody.captured.category ?: "PHYSICAL",
                defaultDurationMinutes = patchedBody.captured.defaultDurationMinutes,
                enabled = patchedBody.captured.enabled ?: true,
                createdAt = "2026-05-12T00:00:00Z",
                updatedAt = "2026-05-12T00:00:00Z"
            )
        }
        coEvery { api.listLeisureActivities(any()) } returns emptyList()

        service.sync()

        assertEquals(cloudId, patchedId.captured)
        val meta = database.syncMetadataDao().get(localId, LeisureSyncService.ENTITY_TYPE)!!
        assertNull(meta.pendingAction)
    }

    @Test
    fun push_update_withoutCloudId_promotesToCreate() = runTest {
        val localId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                name = "Walk",
                category = "PHYSICAL",
                enabled = true
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSyncService.ENTITY_TYPE,
                cloudId = "",
                pendingAction = "update"
            )
        )
        coEvery { api.createLeisureActivity(any()) } answers {
            val req = firstArg<LeisureActivityCreateRequest>()
            LeisureActivityRemoteResponse(
                id = req.id,
                name = req.name,
                category = req.category,
                enabled = req.enabled,
                createdAt = "2026-05-12T00:00:00Z",
                updatedAt = "2026-05-12T00:00:00Z"
            )
        }
        coEvery { api.listLeisureActivities(any()) } returns emptyList()

        service.sync()

        coVerify(exactly = 1) { api.createLeisureActivity(any()) }
        coVerify(exactly = 0) { api.updateLeisureActivity(any(), any()) }
    }

    @Test
    fun push_delete_callsApiAndClearsMetadata() = runTest {
        val cloudId = "cloud-del"
        val localId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                cloudId = cloudId,
                name = "Gone",
                category = "PASSIVE",
                enabled = false
            )
        )
        database.leisureActivityDao().deleteById(localId)
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSyncService.ENTITY_TYPE,
                cloudId = cloudId,
                pendingAction = "delete"
            )
        )
        coEvery { api.deleteLeisureActivity(cloudId) } returns Response.success<Unit>(204, Unit)
        coEvery { api.listLeisureActivities(any()) } returns emptyList()

        service.sync()

        coVerify(exactly = 1) { api.deleteLeisureActivity(cloudId) }
        val meta = database.syncMetadataDao().get(localId, LeisureSyncService.ENTITY_TYPE)
        assertNull(meta)
    }

    @Test
    fun pull_insertsNewRemoteRow() = runTest {
        coEvery { api.listLeisureActivities(any()) } returns listOf(
            LeisureActivityRemoteResponse(
                id = "remote-1",
                name = "Hike",
                category = "PHYSICAL",
                defaultDurationMinutes = 60,
                enabled = true,
                createdAt = "2026-05-10T12:00:00Z",
                updatedAt = "2026-05-10T12:00:00Z"
            )
        )

        service.sync()

        val rows = database.leisureActivityDao().getAllOnce()
        assertEquals(1, rows.size)
        assertEquals("Hike", rows[0].name)
        assertEquals("remote-1", rows[0].cloudId)
        val meta = database.syncMetadataDao().get(rows[0].id, LeisureSyncService.ENTITY_TYPE)
        assertNotNull(meta)
        assertEquals("remote-1", meta!!.cloudId)
    }

    @Test
    fun pull_skipsRowIfLocalNewer() = runTest {
        val cloudId = "cloud-merge"
        val localId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                cloudId = cloudId,
                name = "Local-Newest",
                category = "PHYSICAL",
                enabled = true,
                updatedAt = 9_999_999_999_999L
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSyncService.ENTITY_TYPE,
                cloudId = cloudId
            )
        )
        coEvery { api.listLeisureActivities(any()) } returns listOf(
            LeisureActivityRemoteResponse(
                id = cloudId,
                name = "Remote-Stale",
                category = "PASSIVE",
                enabled = false,
                createdAt = "2020-01-01T00:00:00Z",
                updatedAt = "2020-01-01T00:00:00Z"
            )
        )

        service.sync()

        val saved = database.leisureActivityDao().getById(localId)!!
        assertEquals("Local-Newest", saved.name)
        assertEquals("PHYSICAL", saved.category)
        assertTrue(saved.enabled)
    }

    @Test
    fun pull_reconcilesRemoteSideDeletes() = runTest {
        val cloudId = "cloud-orphan"
        val localId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                cloudId = cloudId,
                name = "DeletedElsewhere",
                category = "SOCIAL",
                enabled = true
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSyncService.ENTITY_TYPE,
                cloudId = cloudId,
                pendingAction = null
            )
        )
        coEvery { api.listLeisureActivities(any()) } returns emptyList()

        service.sync()

        assertNull(database.leisureActivityDao().getById(localId))
        assertNull(database.syncMetadataDao().get(localId, LeisureSyncService.ENTITY_TYPE))
    }

    @Test
    fun pull_preservesPendingLocalCreates_evenIfRemoteListIsEmpty() = runTest {
        val localId = database.leisureActivityDao().insert(
            LeisureActivityEntity(
                id = 0,
                cloudId = "pending-cloud",
                name = "PendingLocal",
                category = "CREATIVE",
                enabled = true
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                localId = localId,
                entityType = LeisureSyncService.ENTITY_TYPE,
                cloudId = "pending-cloud",
                pendingAction = "create"
            )
        )
        // Make push throw so pull runs alone — verifies the orphan
        // reconciler doesn't delete rows with a pending action.
        coEvery { api.createLeisureActivity(any()) } throws RuntimeException("offline")
        coEvery { api.listLeisureActivities(any()) } returns emptyList()

        service.sync()

        assertNotNull(database.leisureActivityDao().getById(localId))
    }
}
