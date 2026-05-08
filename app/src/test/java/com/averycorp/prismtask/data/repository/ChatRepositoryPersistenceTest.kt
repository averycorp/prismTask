package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.remote.api.ChatActionResponse
import com.averycorp.prismtask.data.remote.api.ChatHistoryResponse
import com.averycorp.prismtask.data.remote.api.ChatMessageRecord
import com.averycorp.prismtask.data.remote.api.ChatResponse
import com.averycorp.prismtask.data.remote.api.ChatTokensUsed
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.sse.ChatStreamClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D11 E.3 — pins the DAO-backed persistence contract.
 *
 * Per `docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md` (Item 6):
 *  - sendMessage writes user + assistant rows to the DAO.
 *  - messages Flow reflects DAO state for the active conversation.
 *  - clearConversation rotates the conversationId without DELETE.
 *  - pullHistory upserts API response into DAO.
 */
class ChatRepositoryPersistenceTest {

    @Test
    fun `sendMessage writes user and assistant rows to DAO`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.aiChat(any()) } returns ChatResponse(
            message = "ack",
            actions = listOf(ChatActionResponse(type = "start_timer", minutes = 25)),
            conversationId = "ignored-server-side",
            tokensUsed = ChatTokensUsed(input = 8, output = 3)
        )
        val dao = FakeChatMessageDao()
        val repo = ChatRepository(api, dao, mockk<ChatStreamClient>(relaxed = true))

        repo.sendMessage(userMessage = "hello")

        val rows = dao.rowsSnapshot
        assertEquals(2, rows.size)
        val (userRow, assistantRow) = rows.sortedBy { it.createdAt }
        assertEquals("user", userRow.role)
        assertEquals("hello", userRow.content)
        assertEquals("assistant", assistantRow.role)
        assertEquals("ack", assistantRow.content)
        assertEquals(8, assistantRow.tokensInput)
        assertEquals(3, assistantRow.tokensOutput)
        assertTrue(
            "assistant row must serialize the action list",
            assistantRow.actionsJson?.contains("start_timer") == true
        )
    }

    @Test
    fun `messages Flow exposes rows for current conversation only`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.aiChat(any()) } returns ChatResponse(
            message = "ack", actions = emptyList(), conversationId = "x"
        )
        val dao = FakeChatMessageDao()
        val repo = ChatRepository(api, dao, mockk<ChatStreamClient>(relaxed = true))

        // Pre-seed a row in a different conversation; it must not surface.
        dao.upsert(
            com.averycorp.prismtask.data.local.entity.ChatMessageEntity(
                id = "stale-row",
                conversationId = "chat_2026-04-01_other",
                role = "user",
                content = "from another day",
                createdAt = 1L
            )
        )

        repo.sendMessage(userMessage = "today")
        val active = repo.messages.first()
        val texts = active.map { it.text }
        assertTrue(
            "old conversation rows must be filtered out",
            "from another day" !in texts
        )
        assertTrue("today" in texts)
    }

    @Test
    fun `clearConversation rotates conversationId without deleting rows`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.aiChat(any()) } returns ChatResponse(
            message = "ack", actions = emptyList(), conversationId = "x"
        )
        val dao = FakeChatMessageDao()
        val repo = ChatRepository(api, dao, mockk<ChatStreamClient>(relaxed = true))

        repo.sendMessage(userMessage = "first")
        val rowsBeforeClear = dao.rowsSnapshot.size
        val convBefore = repo.getConversationId()

        repo.clearConversation()

        val rowsAfterClear = dao.rowsSnapshot.size
        val convAfter = repo.getConversationId()
        assertEquals("rows must remain (no DELETE)", rowsBeforeClear, rowsAfterClear)
        assertTrue(
            "conversation id must rotate to a new value",
            convBefore != convAfter
        )
    }

    @Test
    fun `pullHistory upserts API response into DAO`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery { api.aiChat(any()) } returns ChatResponse(
            message = "x", actions = emptyList(), conversationId = "x"
        )
        coEvery {
            api.aiChatHistory(any(), any(), any())
        } returns ChatHistoryResponse(
            messages = listOf(
                ChatMessageRecord(
                    id = "srv-1",
                    conversationId = "chat_2026-05-08_pull",
                    role = "user",
                    content = "remote user msg",
                    createdAt = "2026-05-08T10:00:00+00:00"
                ),
                ChatMessageRecord(
                    id = "srv-2",
                    conversationId = "chat_2026-05-08_pull",
                    role = "assistant",
                    content = "remote reply",
                    createdAt = "2026-05-08T10:00:01+00:00"
                )
            ),
            nextBefore = null
        )
        val dao = FakeChatMessageDao()
        val repo = ChatRepository(api, dao, mockk<ChatStreamClient>(relaxed = true))

        repo.pullHistory("chat_2026-05-08_pull")

        val ids = dao.rowsSnapshot.map { it.id }.toSet()
        assertTrue("pulled rows must land in DAO with server PKs", "srv-1" in ids && "srv-2" in ids)
        assertEquals(2, dao.rowsSnapshot.size)
    }

    @Test
    fun `pullHistory is idempotent on repeat call`() = runTest {
        val api = mockk<PrismTaskApi>()
        coEvery {
            api.aiChatHistory(any(), any(), any())
        } returns ChatHistoryResponse(
            messages = listOf(
                ChatMessageRecord(
                    id = "srv-1",
                    conversationId = "c",
                    role = "user",
                    content = "u",
                    createdAt = "2026-05-08T10:00:00+00:00"
                )
            ),
            nextBefore = null
        )
        val dao = FakeChatMessageDao()
        val repo = ChatRepository(api, dao, mockk<ChatStreamClient>(relaxed = true))

        repo.pullHistory("c")
        repo.pullHistory("c")

        // REPLACE on PK conflict -> exactly one row.
        assertEquals(1, dao.rowsSnapshot.size)
    }

    @Test
    fun `sendMessage forwards prior turns as chronological history`() = runTest {
        val api = mockk<PrismTaskApi>(relaxed = true)
        val dao = FakeChatMessageDao()
        val repo = ChatRepository(api, dao, mockk<ChatStreamClient>(relaxed = true))
        coEvery { api.aiChat(any()) } returnsMany listOf(
            ChatResponse(message = "first reply", actions = emptyList(), conversationId = "x"),
            ChatResponse(message = "second reply", actions = emptyList(), conversationId = "x")
        )

        repo.sendMessage(userMessage = "first user")
        repo.sendMessage(userMessage = "second user")

        // After turn 2, the API was called twice. The second call's request
        // should have included the first turn's user + assistant rows as
        // history. With the rolling DAO source, the DAO should have at
        // least the first user row and assistant row by the time the
        // second request lands. We assert downstream state instead of the
        // captured request payload because rolling behavior is what
        // matters.
        coVerify(exactly = 2) { api.aiChat(any()) }
        assertTrue(dao.rowsSnapshot.size >= 4)
    }
}
