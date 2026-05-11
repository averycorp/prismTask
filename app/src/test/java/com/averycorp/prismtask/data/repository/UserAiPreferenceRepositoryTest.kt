package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.UserAiPreferenceCreateRequest
import com.averycorp.prismtask.data.remote.api.UserAiPreferenceDto
import com.averycorp.prismtask.data.remote.api.UserAiPreferenceListResponse
import com.averycorp.prismtask.data.remote.api.UserAiPreferenceUpdateRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the AI-memory client contract:
 *  - chat-response mirror REPLACES the local list (idempotent on every turn).
 *  - refresh() fetches from the backend and replaces.
 *  - create/update/delete round-trip through the API and reflect in DAO.
 */
class UserAiPreferenceRepositoryTest {

    private fun dto(
        id: String,
        text: String,
        updatedAt: String = "2026-05-11T12:00:00+00:00",
        createdAt: String = "2026-05-11T12:00:00+00:00"
    ) = UserAiPreferenceDto(
        id = id,
        preferenceText = text,
        sourceMessageId = null,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    @Test
    fun `mirrorFromChat replaces local rows with server snapshot`() = runTest {
        val api = mockk<PrismTaskApi>(relaxed = true)
        val dao = FakeUserAiPreferenceDao()
        val repo = UserAiPreferenceRepository(api, dao)

        // Seed two pre-existing rows; mirror should drop them and keep
        // only what the new snapshot contains.
        repo.mirrorFromChat(listOf(dto("a", "stale a"), dto("b", "stale b")))
        repo.mirrorFromChat(listOf(dto("c", "fresh c"), dto("d", "fresh d")))

        val ids = dao.rowsSnapshot.map { it.id }.toSet()
        assertEquals(setOf("c", "d"), ids)
    }

    @Test
    fun `mirrorFromChat with empty list clears local mirror`() = runTest {
        val api = mockk<PrismTaskApi>(relaxed = true)
        val dao = FakeUserAiPreferenceDao()
        val repo = UserAiPreferenceRepository(api, dao)

        repo.mirrorFromChat(listOf(dto("a", "to be evicted")))
        assertEquals(1, dao.rowsSnapshot.size)

        repo.mirrorFromChat(emptyList())
        assertEquals(
            "empty server snapshot must drop all local rows",
            0,
            dao.rowsSnapshot.size
        )
    }

    @Test
    fun `refresh pulls server snapshot via the list endpoint`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.listAiMemory() } returns UserAiPreferenceListResponse(
            preferences = listOf(dto("x", "remote x"), dto("y", "remote y")),
            cap = 15
        )
        val dao = FakeUserAiPreferenceDao()
        val repo = UserAiPreferenceRepository(api, dao)

        repo.refresh()

        val ids = dao.rowsSnapshot.map { it.id }.toSet()
        assertEquals(setOf("x", "y"), ids)
        coVerify { api.listAiMemory() }
    }

    @Test
    fun `create round-trips through API and writes the returned row to DAO`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.createAiMemory(any()) } returns dto("new1", "fresh preference")
        val dao = FakeUserAiPreferenceDao()
        val repo = UserAiPreferenceRepository(api, dao)

        val result = repo.create("  fresh preference  ")

        assertEquals("new1", result.id)
        assertEquals("fresh preference", result.text)
        coVerify {
            api.createAiMemory(
                UserAiPreferenceCreateRequest(preferenceText = "fresh preference")
            )
        }
        assertTrue(dao.rowsSnapshot.any { it.id == "new1" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create rejects blank text`() = runTest {
        val api = mockk<PrismTaskApi>(relaxed = true)
        val dao = FakeUserAiPreferenceDao()
        val repo = UserAiPreferenceRepository(api, dao)
        repo.create("   ")
    }

    @Test
    fun `update writes the new text returned by the API`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.updateAiMemory(any(), any()) } returns
            dto("p1", "Updated text", updatedAt = "2026-05-11T13:00:00+00:00")
        val dao = FakeUserAiPreferenceDao()
        val repo = UserAiPreferenceRepository(api, dao)

        // Pre-seed local row at the same id with the old text.
        repo.mirrorFromChat(listOf(dto("p1", "Original text")))
        val updated = repo.update("p1", "Updated text")

        assertEquals("Updated text", updated.text)
        coVerify {
            api.updateAiMemory(
                preferenceId = "p1",
                request = UserAiPreferenceUpdateRequest(preferenceText = "Updated text")
            )
        }
        assertEquals(
            "Updated text",
            dao.rowsSnapshot.first { it.id == "p1" }.preferenceText
        )
    }

    @Test
    fun `delete removes the row locally after a successful API call`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.deleteAiMemory("p1") } returns Unit
        val dao = FakeUserAiPreferenceDao()
        val repo = UserAiPreferenceRepository(api, dao)

        repo.mirrorFromChat(listOf(dto("p1", "to delete"), dto("p2", "keep")))
        repo.delete("p1")

        coVerify { api.deleteAiMemory("p1") }
        val ids = dao.rowsSnapshot.map { it.id }.toSet()
        assertEquals(setOf("p2"), ids)
    }
}
